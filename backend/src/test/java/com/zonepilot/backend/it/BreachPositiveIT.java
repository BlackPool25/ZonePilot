package com.zonepilot.backend.it;

import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneRestrictionRule;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.RestrictionType;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.service.BreachService;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Breach-positive proof + tour/group scoping (TDD).
 *
 * <ol>
 *   <li>Tour drives into a restricted zone inside its rule window:
 *       exactly one zone_breach_log row appears and a reroute is attempted
 *       (best-effort: no road tables in IT, so geometry stays null).</li>
 *   <li>Second fix inside the 60s window extends the row (one-per-entry).</li>
 *   <li>?tourId / ?groupId filters on breaches + positions + reports summary
 *       enforce group scope (cross-group -&gt; 403, no findAll fallback).</li>
 * </ol>
 */
class BreachPositiveIT extends SliceIT {

    private static final double LAT = 12.9352;
    private static final double LNG = 77.6245;

    @Autowired
    com.zonepilot.backend.repository.ZoneRestrictionRepository zones;
    @Autowired
    com.zonepilot.backend.repository.ZoneRestrictionRuleRepository rules;
    @Autowired
    com.zonepilot.backend.repository.ZoneBreachLogRepository breaches;
    @Autowired
    com.zonepilot.backend.repository.VehiclePositionLogRepository positions;
    @Autowired
    com.zonepilot.backend.repository.FleetGroupRepository groups;
    @Autowired
    com.zonepilot.backend.repository.DriverRepository drivers;
    @Autowired
    com.zonepilot.backend.repository.TourRepository tours;
    @Autowired
    com.zonepilot.backend.repository.DispatchRouteRepository routes;
    @Autowired
    com.zonepilot.backend.repository.GroupVehicleRepository groupVehicles;
    @Autowired
    com.zonepilot.backend.auth.AppUserRepository appUsers;

    @MockitoSpyBean
    BreachService breachService;

    private FleetGroup group(String name) {
        FleetGroup g = new FleetGroup();
        g.setName(name + " " + System.nanoTime());
        g.setActive(true);
        return groups.save(g);
    }

    private Vehicle vehicleOnTour(FleetGroup g, String reg) {
        Vehicle v = saveVehicle(reg + "-" + (System.nanoTime() % 100000));
        Driver d = new Driver();
        d.setName("Breach Driver");
        d.setLicenceNo("LIC-BP-" + System.nanoTime());
        drivers.save(d);
        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(v);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        tours.save(t);
        DispatchRoute r = new DispatchRoute();
        r.setVehicle(v);
        r.setDriver(d);
        r.setTour(t);
        r.setOriginPoint(gf.createPoint(new Coordinate(77.60, 12.91)));
        r.setDestinationPoint(gf.createPoint(new Coordinate(77.62, 12.93)));
        routes.save(r);
        v.setActiveDispatchRouteId(r.getId());
        vehicles.save(v);
        return v;
    }

    private ZoneRestriction restrictedZone(String name) {
        double d = 0.002;
        Coordinate[] ring = {
                new Coordinate(LNG - d, LAT - d), new Coordinate(LNG + d, LAT - d),
                new Coordinate(LNG + d, LAT + d), new Coordinate(LNG - d, LAT + d),
                new Coordinate(LNG - d, LAT - d)};
        var poly = gf.createPolygon(ring);
        poly.setSRID(4326);
        ZoneRestriction z = new ZoneRestriction();
        z.setName(name + " " + System.nanoTime());
        z.setRestrictionType(RestrictionType.TIME_RESTRICTED);
        z.setBoundary(poly);
        z.setIsActive(true);
        zones.save(z);
        ZoneRestrictionRule rule = new ZoneRestrictionRule();
        rule.setZone(z);
        rule.setApplicableVehicleClass(VehicleClass.ALL);
        rule.setRestrictionStartTime(LocalTime.of(0, 0));
        rule.setRestrictionEndTime(LocalTime.of(23, 59));
        rule.setDaysOfWeekBitmask((short) 127);
        rule.setIsActive(true);
        rules.save(rule);
        return z;
    }

    private void awaitBreach(long vehicleId, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (breaches.findByVehicleIdOrderByBreachTimeDesc(vehicleId).size() >= expected) {
                return;
            }
            Thread.sleep(300);
        }
        assertThat(breaches.findByVehicleIdOrderByBreachTimeDesc(vehicleId)).hasSize(expected);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(null, headers), Map.class);
    }

    @Test
    void tourInsideRestrictedWindow_createsBreach_andAttemptsReroute() throws Exception {
        String owner = registerOwner("bp-owner-" + System.nanoTime() + "@zp.test");
        FleetGroup g = group("BreachGroup");
        Vehicle v = vehicleOnTour(g, "KA01-BP-1");
        ZoneRestriction z = restrictedZone("WindowZone");

        Instant t0 = Instant.now();
        Map<String, Object> fix = Map.of("lat", LAT, "lng", LNG,
                "timestamp", t0.toString(), "speedKmh", 20, "headingDeg", 90);
        Map<String, Object> res = post("/api/vehicles/" + v.getId() + "/positions", fix, owner);
        assertThat(res.get("status")).isEqualTo(200);

        awaitBreach(v.getId(), 1);
        List<ZoneBreachLog> rows = breaches.findByVehicleIdOrderByBreachTimeDesc(v.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getZone().getId()).isEqualTo(z.getId());
        assertThat(rows.get(0).getBreachType().name()).isEqualTo("TIME_WINDOW");

        // Reroute attempted best-effort (no road tables in IT: geometry stays null, row stays).
        verify(breachService, timeout(30_000)).computeReroute(any(), eq(v.getId()), eq(LAT), eq(LNG));

        // Second fix inside the 60s window extends the same row (one-per-entry).
        Map<String, Object> fix2 = Map.of("lat", LAT, "lng", LNG,
                "timestamp", t0.plusSeconds(30).toString(), "speedKmh", 20, "headingDeg", 90);
        Map<String, Object> res2 = post("/api/vehicles/" + v.getId() + "/positions", fix2, owner);
        assertThat(res2.get("status")).isEqualTo(200);
        Thread.sleep(4000);
        assertThat(breaches.findByVehicleIdOrderByBreachTimeDesc(v.getId())).hasSize(1);

        // Concurrent double-post inside the window: the tx-scoped advisory
        // lock serializes the two worker txns, so this is still one row.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int off : new int[]{40, 41}) {
            final int s = off;
            futures.add(pool.submit(() -> {
                gate.await();
                Map<String, Object> f = Map.of("lat", LAT, "lng", LNG,
                        "timestamp", t0.plusSeconds(s).toString(), "speedKmh", 20, "headingDeg", 90);
                post("/api/vehicles/" + v.getId() + "/positions", f, owner);
                return null;
            }));
        }
        gate.countDown();
        for (var f : futures) {
            f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        awaitBreach(v.getId(), 1);
        Thread.sleep(4000);
        assertThat(breaches.findByVehicleIdOrderByBreachTimeDesc(v.getId())).hasSize(1);
    }

    @Test
    void breachList_tourScoped_crossGroupForbidden() {
        String owner = registerOwner("sc-owner-" + System.nanoTime() + "@zp.test");
        String mgrAEmail = "sc-mgr-a-" + System.nanoTime() + "@zp.test";
        String mgrBEmail = "sc-mgr-b-" + System.nanoTime() + "@zp.test";
        String mgrA = registerManager(owner, mgrAEmail);
        String mgrB = registerManager(owner, mgrBEmail);
        FleetGroup gA = managedGroup("ScopeA", mgrA, mgrAEmail);
        FleetGroup gB = managedGroup("ScopeB", mgrB, mgrBEmail);
        try {
            Vehicle vA = vehicleOnTour(gA, "KA01-SC-A");
            Vehicle vB = vehicleOnTour(gB, "KA01-SC-B");
            Tour tourA = tours.findAll().stream()
                    .filter(t -> t.getVehicle().getId().equals(vA.getId())).findFirst().orElseThrow();
            Tour tourB = tours.findAll().stream()
                    .filter(t -> t.getVehicle().getId().equals(vB.getId())).findFirst().orElseThrow();
            ZoneRestriction z = restrictedZone("ScopeZone");
            seedBreach(vA, tourA, gA, z);
            seedBreach(vB, tourB, gB, z);

            // Cross-group tour filter -> 403.
            ResponseEntity<Map> forbidden =
                    get("/api/breaches?tourId=" + tourB.getId(), mgrA);
            assertThat(forbidden.getStatusCode().value()).isEqualTo(403);

            // Own tour filter -> only that tour's breach.
            ResponseEntity<Map> own = get("/api/breaches?tourId=" + tourA.getId(), mgrA);
            assertThat(own.getStatusCode().value()).isEqualTo(200);
            assertThat(breachIds(own)).hasSize(1);

            // No filter -> own-group default, NOT everything.
            ResponseEntity<Map> def = get("/api/breaches", mgrA);
            assertThat(def.getStatusCode().value()).isEqualTo(200);
            assertThat(breachIds(def)).hasSize(1);

            // Owner with no filter still sees everything.
            ResponseEntity<Map> all = get("/api/breaches", owner);
            assertThat(all.getStatusCode().value()).isEqualTo(200);
            assertThat(breachIds(all).size()).isGreaterThanOrEqualTo(2);

            // Positions history: cross-group tour -> 403, own tour -> 200.
            ResponseEntity<Map> posForbidden = get(
                    "/api/vehicles/" + vA.getId() + "/positions?tourId=" + tourB.getId(), mgrA);
            assertThat(posForbidden.getStatusCode().value()).isEqualTo(403);
            ResponseEntity<Map> posOwn = get(
                    "/api/vehicles/" + vA.getId() + "/positions?tourId=" + tourA.getId(), mgrA);
            assertThat(posOwn.getStatusCode().value()).isEqualTo(200);

            // Reports summary: cross-group groupId -> 403.
            ResponseEntity<Map> repForbidden =
                    get("/api/reports/summary?groupId=" + gB.getId(), mgrA);
            assertThat(repForbidden.getStatusCode().value()).isEqualTo(403);
            ResponseEntity<Map> repOwn =
                    get("/api/reports/summary?groupId=" + gA.getId(), mgrA);
            assertThat(repOwn.getStatusCode().value()).isEqualTo(200);
        } finally {
            // SliceIT.cleanUsers deletes users before each test: release the
            // manager FK first or the next deleteAll hits a FK violation.
            for (FleetGroup gg : groups.findAll()) {
                if (gg.getManager() != null) {
                    gg.setManager(null);
                    groups.save(gg);
                }
            }
        }
    }

    private String registerManager(String ownerToken, String email) {
        Map<String, Object> reg = post("/api/auth/register",
                Map.of("email", email, "password", "mgr-pass-1", "role", "DISPATCHER"), ownerToken);
        if (!reg.get("status").equals(200)) {
            throw new IllegalStateException("manager register failed: " + reg);
        }
        return String.valueOf(data(reg).get("accessToken"));
    }

    private FleetGroup managedGroup(String groupName, String regEmail, String managerEmail) {
        FleetGroup g = group(groupName);
        var user = appUsers.findByEmail(managerEmail).orElseThrow();
        g.setManager(user);
        groups.save(g);
        assertThat(regEmail).isNotBlank();
        return g;
    }

    private void seedBreach(Vehicle v, Tour t, FleetGroup g, ZoneRestriction z) {
        Point p = gf.createPoint(new Coordinate(LNG, LAT));
        p.setSRID(4326);
        VehiclePositionLog pl = new VehiclePositionLog();
        pl.setVehicleId(v.getId());
        pl.setDriverId(t.getDriver().getId());
        pl.setTourId(t.getId());
        pl.setGroupId(g.getId());
        pl.setPosition(p);
        pl.setRecordedAt(Instant.now().minusSeconds(3600 + (System.nanoTime() % 1000)));
        pl.setSource(PositionSource.LIVE);
        // @IdClass + sequence: the generated id is on the RETURNED instance,
        // not the passed one — use the return value.
        VehiclePositionLog saved = positions.saveAndFlush(pl);
        ZoneBreachLog b = new ZoneBreachLog();
        b.setVehicle(v);
        b.setZone(z);
        b.setPositionLogId(saved.getId());
        b.setBreachTime(pl.getRecordedAt());
        b.setEndTime(pl.getRecordedAt());
        b.setBreachType(com.zonepilot.backend.enums.BreachType.NO_ENTRY);
        b.setDistanceM(0.0);
        breaches.save(b);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> breachIds(ResponseEntity<Map> res) {
        Map<String, Object> body = res.getBody();
        Object data = body == null ? null : body.get("data");
        return data instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
