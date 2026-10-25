package com.zonepilot.backend.tour;

import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.TourStopRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 3: tour lifecycle — create (optimized), deliver, skip, guards.
 * The compliance leg is stubbed (no OSM road network in IT); routing itself
 * is covered by RouteComplianceServiceTest. This tests tour orchestration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TourApiTest {

    @Container
    @SuppressWarnings("rawtypes")
    static GenericContainer postgres = new GenericContainer(
                    new ImageFromDockerfile("zonepilot-postgres-test", false)
                            .withDockerfile(Paths.get("../docker/postgres/Dockerfile").toAbsolutePath()))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "zonepilot")
            .withEnv("POSTGRES_USER", "zonepilot")
            .withEnv("POSTGRES_PASSWORD", "zonepilot")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/zonepilot");
        registry.add("spring.datasource.username", () -> "zonepilot");
        registry.add("spring.datasource.password", () -> "zonepilot");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    DispatchRouteRepository routeRepository;
    @Autowired
    TourStopRepository stopRepository;
    @Autowired
    TourRepository tourRepository;
    @Autowired
    GroupVehicleRepository groupVehicleRepository;
    @Autowired
    GroupDriverRepository groupDriverRepository;
    @Autowired
    FleetGroupRepository groupRepository;
    @Autowired
    DriverRepository driverRepository;
    @Autowired
    VehicleRepository vehicleRepository;
    @Autowired
    AppUserRepository userRepository;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate db;

    private String ownerToken;
    private long groupId;
    private long vehicleId;
    private long driverId;

    @BeforeEach
    void setup() {
        // FK order: routes -> stops -> tours -> memberships -> groups -> drivers -> users.
        routeRepository.deleteAll();
        stopRepository.deleteAll();
        tourRepository.deleteAll();
        groupVehicleRepository.deleteAll();
        groupDriverRepository.deleteAll();
        groupRepository.deleteAll();
        driverRepository.deleteAll();
        userRepository.deleteAll();
        ownerToken = registerOwner("tour-owner-" + System.nanoTime() + "@zp.test");
        groupId = dataId(post("/api/groups", Map.of("name", "Tour Group"), ownerToken));
        vehicleId = dataId(post("/api/vehicles",
                Map.of("registrationNumber", "KA07-TOUR-" + (System.nanoTime() % 100000),
                        "vehicleClass", "LCV", "ownerName", "Tour Owner"), ownerToken));
        String drvEmail = "tour-drv-" + System.nanoTime() + "@zp.test";
        driverId = dataId(post("/api/drivers",
                Map.of("name", "Tour Driver", "licenceNo", "LIC-TAPI-" + System.nanoTime(),
                        "licenceExpiry", "2030-01-01", "phone", "9886000001",
                        "salaryMode", "PER_TRIP", "email", drvEmail,
                        "password", "driver-pass-1"), ownerToken));
        stubRealNetwork();
    }

    /**
     * Synthetic 2-D grid road network (test-only fixture, NOT a migration —
     * production loads real OSM). Lets tour legs run through real pgRouting
     * Dijkstra + sp_validate_route instead of a stubbed compliance service.
     * Grid covers 77.570–77.650 / 12.926–12.938, bidirectional, 50 km/h costs.
     */
    private void stubRealNetwork() {
        db.execute("CREATE TABLE IF NOT EXISTS blr_2po_4pgr_vertices_pgr"
                + " (id BIGINT PRIMARY KEY, the_geom geometry(Point,4326))");
        db.execute("CREATE TABLE IF NOT EXISTS blr_2po_4pgr (id BIGINT PRIMARY KEY,"
                + " source BIGINT, target BIGINT, cost_time_sec DOUBLE PRECISION,"
                + " reverse_cost DOUBLE PRECISION, the_geom geometry(LineString,4326))");
        db.execute("CREATE INDEX IF NOT EXISTS idx_test_vertices_geom"
                + " ON blr_2po_4pgr_vertices_pgr USING GIST (the_geom)");
        db.execute("DELETE FROM blr_2po_4pgr");
        db.execute("DELETE FROM blr_2po_4pgr_vertices_pgr");

        double[] lngs = new double[17];
        for (int i = 0; i < lngs.length; i++) lngs[i] = 77.570 + i * 0.005;
        double[] lats = new double[7];
        for (int i = 0; i < lats.length; i++) lats[i] = 12.926 + i * 0.002;
        long edgeId = 1;
        for (int r = 0; r < lats.length; r++) {
            for (int c = 0; c < lngs.length; c++) {
                long node = r * 100L + c;
                db.update("INSERT INTO blr_2po_4pgr_vertices_pgr(id, the_geom) VALUES (?,"
                        + " ST_SetSRID(ST_Point(?, ?), 4326))", node, lngs[c], lats[r]);
                if (c + 1 < lngs.length) {
                    edgeId = link(edgeId, node, r * 100L + c + 1,
                            lngs[c], lats[r], lngs[c + 1], lats[r]);
                }
                if (r + 1 < lats.length) {
                    edgeId = link(edgeId, node, (r + 1) * 100L + c,
                            lngs[c], lats[r], lngs[c], lats[r + 1]);
                }
            }
        }
    }

    private long link(long edgeId, long from, long to,
                      double lng1, double lat1, double lng2, double lat2) {
        // Bidirectional: reverse_cost == cost so directed Dijkstra traverses both ways.
        double cost = com.zonepilot.backend.service.TourOptimizerService
                .haversineMeters(lat1, lng1, lat2, lng2) / (50_000.0 / 3600.0);
        db.update("INSERT INTO blr_2po_4pgr(id, source, target, cost_time_sec,"
                + " reverse_cost, the_geom) VALUES (?,?,?,?,?,"
                + " ST_SetSRID(ST_GeomFromText(?), 4326))",
                edgeId, from, to, cost, cost,
                "LINESTRING(" + lng1 + " " + lat1 + "," + lng2 + " " + lat2 + ")");
        return edgeId + 1;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tour(long tourId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> res = rest.exchange(url("/api/tours/" + tourId), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        return (Map<String, Object>) ((Map<String, Object>) res.getBody()).get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> track(long tourId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(ownerToken);
        ResponseEntity<Map> res = rest.exchange(url("/api/tours/" + tourId + "/track"), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        return (Map<String, Object>) ((Map<String, Object>) res.getBody()).get("data");
    }

    private Map<String, Object> createBody() {
        return Map.of("groupId", groupId, "vehicleId", vehicleId, "driverId", driverId,
                "originLat", 12.930, "originLng", 77.580,
                "stops", List.of(
                        Map.of("lat", 12.935, "lng", 77.640, "address", "Far",
                                "quantity", 5, "codAmount", 100),
                        Map.of("lat", 12.932, "lng", 77.600, "address", "Near",
                                "recipientName", "Rani", "recipientPhone", "9886000002")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void track_returnsLegsAndLivePosition() {
        long tourId = dataId(post("/api/tours", createBody(), ownerToken));

        Map<String, Object> before = track(tourId);
        assertThat(((Map<String, Object>) before.get("latestPosition")).get("source")).isEqualTo("NONE");
        List<Map<String, Object>> legs0 = (List<Map<String, Object>>) before.get("legs");
        assertThat(legs0).hasSize(1);
        assertThat(legs0.get(0).get("geometryWkt")).isNotNull();
        assertThat(((Number) legs0.get(0).get("fromSeq")).intValue()).isEqualTo(-1);

        Map<String, Object> posPost = post("/api/vehicles/" + vehicleId + "/positions",
                Map.of("lat", 12.931, "lng", 77.590, "speedKmh", 30, "headingDeg", 90), ownerToken);
        assertThat(posPost.get("status")).isEqualTo(200);

        Map<String, Object> after = track(tourId);
        Map<String, Object> pos = (Map<String, Object>) after.get("latestPosition");
        assertThat(((Number) pos.get("latitude")).doubleValue()).isCloseTo(12.931, within(0.001));
        assertThat(((Number) pos.get("longitude")).doubleValue()).isCloseTo(77.590, within(0.001));
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_optimizesFarLast_and_deliverAdvances() {
        long tourId = dataId(post("/api/tours", createBody(), ownerToken));

        Map<String, Object> created = tour(tourId);
        List<Map<String, Object>> stops = (List<Map<String, Object>>) created.get("stops");
        assertThat(stops).hasSize(2);
        // Near (77.600) must come before Far (77.640) after fastest-time ordering.
        assertThat(stops.get(0).get("address")).isEqualTo("Near");
        assertThat(stops.get(1).get("address")).isEqualTo("Far");
        assertThat(created.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) created.get("activeRouteId")).longValue()).isPositive();

        // Deliver stop 0 → next leg becomes the active route.
        Map<String, Object> afterDeliver = post(
                "/api/tours/" + tourId + "/stops/" + stops.get(0).get("id") + "/deliver",
                Map.of("lat", 12.932, "lng", 77.600), ownerToken);
        assertThat(afterDeliver.get("status")).isEqualTo(200);
        List<Map<String, Object>> stops2 =
                (List<Map<String, Object>>) tour(tourId).get("stops");
        assertThat(stops2.get(0).get("status")).isEqualTo("DELIVERED");
        assertThat(stops2.get(1).get("status")).isEqualTo("PENDING");
    }

    @Test
    @SuppressWarnings("unchecked")
    void etas_increaseAlongTour_and_advanceReanchors() {
        long tourId = dataId(post("/api/tours", createBody(), ownerToken));

        List<Map<String, Object>> stops =
                (List<Map<String, Object>>) tour(tourId).get("stops");
        Instant eta0 = Instant.parse((String) stops.get(0).get("etaAt"));
        Instant eta1 = Instant.parse((String) stops.get(1).get("etaAt"));
        assertThat(eta0).isAfter(Instant.now().minusSeconds(300));
        assertThat(eta1).isAfter(eta0);

        // Deliver stop 0 (real leg actual via Dijkstra) → stop 1 re-anchored.
        Map<String, Object> afterDeliver = post(
                "/api/tours/" + tourId + "/stops/" + stops.get(0).get("id") + "/deliver",
                Map.of("lat", 12.932, "lng", 77.600), ownerToken);
        assertThat(afterDeliver.get("status")).isEqualTo(200);
        // No Google key in IT, so the haversine single-leg estimate is the actual.
        double expectedSec = com.zonepilot.backend.service.TourOptimizerService
                .haversineMeters(12.932, 77.600, 12.935, 77.640) / (50_000.0 / 3600.0);
        Instant anchored = Instant.now().plusSeconds(Math.round(expectedSec));
        List<Map<String, Object>> stops2 =
                (List<Map<String, Object>>) tour(tourId).get("stops");
        Instant newEta1 = Instant.parse((String) stops2.get(1).get("etaAt"));
        assertThat(Math.abs(newEta1.getEpochSecond() - anchored.getEpochSecond())).isLessThan(180);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skip_requiresReason_and_lastStop_completes() {
        long tourId = dataId(post("/api/tours", createBody(), ownerToken));
        List<Map<String, Object>> stops =
                (List<Map<String, Object>>) tour(tourId).get("stops");

        Map<String, Object> noReason = post(
                "/api/tours/" + tourId + "/stops/" + stops.get(0).get("id") + "/skip",
                Map.of(), ownerToken);
        assertThat(noReason.get("status")).isEqualTo(400);

        Map<String, Object> skipped = post(
                "/api/tours/" + tourId + "/stops/" + stops.get(0).get("id") + "/skip",
                Map.of("reason", "gate closed"), ownerToken);
        assertThat(skipped.get("status")).isEqualTo(200);

        // Deliver the final stop → tour COMPLETED.
        List<Map<String, Object>> mid =
                (List<Map<String, Object>>) tour(tourId).get("stops");
        Map<String, Object> done = post(
                "/api/tours/" + tourId + "/stops/" + mid.get(1).get("id") + "/deliver",
                Map.of("lat", 12.935, "lng", 77.640), ownerToken);
        assertThat(done.get("status")).isEqualTo(200);
        assertThat(tour(tourId).get("status")).isEqualTo("COMPLETED");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> post(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<Map> res = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        return Map.of("status", res.getStatusCode().value(), "body",
                res.getBody() == null ? Map.of() : res.getBody());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> result) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("body")).get("data");
    }

    private static long dataId(Map<String, Object> result) {
        return ((Number) data(result).get("id")).longValue();
    }

    private static String dataToken(Map<String, Object> result, String field) {
        return String.valueOf(data(result).get(field));
    }

    private String registerOwner(String email) {
        Map<String, Object> reg = post("/api/auth/register",
                Map.of("email", email, "password", "owner-pass-1", "role", "OWNER"), null);
        assertThat(reg.get("status")).isEqualTo(200);
        return dataToken(reg, "accessToken");
    }
}
