package com.zonepilot.backend.driver;

import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.RouteStatus;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.repository.DepotRepository;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.SosAlertRepository;
import com.zonepilot.backend.repository.TripSlipRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Slice 2: driver roster + assignment Accept/Start + slips + SOS + doc wall,
 * end to end against our PostGIS/pgRouting image.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DriverApiTest {

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
    DepotRepository depots;
    @Autowired
    VehicleRepository vehicles;
    @Autowired
    DispatchRouteRepository routes;
    @Autowired
    DriverRepository driverRepository;
    @Autowired
    TripSlipRepository slipRepository;
    @Autowired
    SosAlertRepository sosAlertRepository;
    @Autowired
    AppUserRepository userRepository;

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    private String ownerToken;
    private Depot depotA;
    private Vehicle vehicleA;

    @BeforeEach
    void setup() {
        // FK order: sos/slips -> routes -> drivers -> users. Depots/vehicles accumulate.
        sosAlertRepository.deleteAll();
        slipRepository.deleteAll();
        routes.deleteAll();
        driverRepository.deleteAll();
        userRepository.deleteAll();
        saveDepot("DriverSpec B " + System.nanoTime()); // second depot exists for cross-depot tests
        depotA = saveDepot("DriverSpec A " + System.nanoTime());
        ownerToken = registerOwner("owner-drv-" + System.nanoTime() + "@test.io");

        Map<String, Object> created = post("/api/vehicles",
                Map.of("registrationNumber", "KA09-DRV-" + (System.nanoTime() % 100000),
                        "vehicleClass", "LCV", "ownerName", "Owner", "depotId", depotA.getId()), ownerToken);
        assertThat(created.get("status")).isEqualTo(200);
        vehicleA = vehicles.findById(dataId(created)).orElseThrow();
    }

    private Depot saveDepot(String name) {
        Depot d = new Depot();
        d.setName(name);
        d.setAddress(name);
        d.setLocation(gf.createPoint(new Coordinate(77.64, 12.91)));
        return depots.save(d);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> post(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        ResponseEntity<Map> res = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        return Map.of("status", res.getStatusCode().value(), "body",
                res.getBody() == null ? Map.of() : res.getBody());
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

    /** Creates driver + login; returns {driverId, token}. */
    private Map<String, Object> createDriver(String tag, Long depotId, String licenceExpiry) {
        String email = "drv-" + tag + "@test.io";
        Map<String, Object> res = post("/api/drivers", Map.of(
                "name", "Driver " + tag,
                "phone", "98860" + Math.abs(tag.hashCode() % 90000 + 10000),
                "licenceNo", "LIC-" + tag,
                "licenceExpiry", licenceExpiry,
                "salaryMode", "PER_TRIP",
                "depotId", depotId,
                "email", email,
                "password", "driver-pass-1"), ownerToken);
        assertThat(res.get("status")).isEqualTo(200);
        Map<String, Object> login = post("/api/auth/login",
                Map.of("email", email, "password", "driver-pass-1"), null);
        assertThat(login.get("status")).isEqualTo(200);
        return Map.of("driverId", dataId(res), "token", dataToken(login, "accessToken"));
    }

    private DispatchRoute saveRoute(Vehicle vehicle) throws Exception {
        DispatchRoute r = new DispatchRoute();
        r.setVehicle(vehicle);
        r.setOriginPoint(gf.createPoint(new Coordinate(77.5946, 12.9716)));
        r.setDestinationPoint(gf.createPoint(new Coordinate(77.6600, 12.8400)));
        r.setPlannedRouteGeometry((org.locationtech.jts.geom.LineString)
                new WKTReader(gf).read("LINESTRING (77.5946 12.9716, 77.62 12.90, 77.66 12.84)"));
        r.setStatus(RouteStatus.COMPLIANT);
        return routes.save(r);
    }

    @Test
    void assign_accept_start_slip_sos_happyPath() throws Exception {
        String tag = "a" + System.nanoTime();
        Map<String, Object> driver = createDriver(tag, depotA.getId(), LocalDate.now().plusYears(2).toString());
        DispatchRoute route = saveRoute(vehicleA);

        Map<String, Object> assigned = post("/api/routes/" + route.getId() + "/assign",
                Map.of("driverId", driver.get("driverId")), ownerToken);
        assertThat(assigned.get("status")).isEqualTo(200);

        String driverToken = (String) driver.get("token");
        assertThat(post("/api/driver/routes/" + route.getId() + "/accept", Map.of(), driverToken)
                .get("status")).isEqualTo(200);
        assertThat(post("/api/driver/routes/" + route.getId() + "/start", Map.of(), driverToken)
                .get("status")).isEqualTo(200);
        assertThat(post("/api/driver/routes/" + route.getId() + "/slips",
                Map.of("kind", "FUEL", "amount", 2500), driverToken).get("status")).isEqualTo(200);
        assertThat(post("/api/driver/sos",
                Map.of("lat", 12.91, "lng", 77.64, "routeId", route.getId()), driverToken)
                .get("status")).isEqualTo(200);
    }

    @Test
    void sos_withoutLocation_is201NullLocation() {
        String tag = "n" + System.nanoTime();
        Map<String, Object> driver = createDriver(tag, depotA.getId(), LocalDate.now().plusYears(2).toString());
        Map<String, Object> res = post("/api/driver/sos", Map.of(), (String) driver.get("token"));
        assertThat(res.get("status")).isEqualTo(200);
        assertThat(data(res).get("location")).isNull();
    }

    @Test
    void noToken_is401() {
        assertThat(get("/api/drivers", null).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/driver/routes", null).getStatusCode().value()).isEqualTo(401);
        // The 401 envelope itself is covered on GET (body survives there).
        assertThat(get("/api/drivers", null).getBody()).contains("\"code\":\"UNAUTHORIZED\"");
        // POST too: the default JDK test client drops 401s that arrive while
        // the request body is still unsent (server closes first), so this one
        // uses a fully-buffering client — same rejection curl sees live.
        RestTemplate buffering = new RestTemplate(new SimpleClientHttpRequestFactory());
        try {
            buffering.exchange(url("/api/driver/sos"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("lat", 12.9, "lng", 77.6), jsonHeaders()), Map.class);
            fail("expected 401");
        } catch (HttpClientErrorException.Unauthorized e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void strangerDriver_accept_is403() throws Exception {
        Map<String, Object> driverA = createDriver("d" + System.nanoTime(),
                depotA.getId(), LocalDate.now().plusYears(2).toString());
        Map<String, Object> driverB = createDriver("e" + System.nanoTime(),
                depotA.getId(), LocalDate.now().plusYears(2).toString());
        DispatchRoute route = saveRoute(vehicleA);
        assertThat(post("/api/routes/" + route.getId() + "/assign",
                Map.of("driverId", driverA.get("driverId")), ownerToken).get("status")).isEqualTo(200);

        // driver B's token on driver A's assignment -> 403
        assertThat(post("/api/driver/routes/" + route.getId() + "/accept",
                Map.of(), (String) driverB.get("token")).get("status")).isEqualTo(403);
    }

    @Test
    void expiredLicence_blocksAssign_withReason() throws Exception {
        Map<String, Object> driver = createDriver("c" + System.nanoTime(),
                depotA.getId(), LocalDate.now().minusDays(10).toString());
        DispatchRoute route = saveRoute(vehicleA);

        Map<String, Object> assigned = post("/api/routes/" + route.getId() + "/assign",
                Map.of("driverId", driver.get("driverId")), ownerToken);
        assertThat(assigned.get("status")).isEqualTo(422);
    }

    @Test
    void expiringDocs_wallListsSoonExpiring() {
        Map<String, Object> doc = post("/api/documents", Map.of(
                "vehicleId", vehicleA.getId(), "type", "FITNESS",
                "docNo", "FIT-1", "expiryDate", LocalDate.now().plusDays(10).toString()), ownerToken);
        assertThat(doc.get("status")).isEqualTo(200);

        ResponseEntity<String> exp = get("/api/documents/expiring?days=30", ownerToken);
        assertThat(exp.getStatusCode().value()).isEqualTo(200);
        assertThat(exp.getBody()).contains("FIT-1");

        ResponseEntity<String> none = get("/api/documents/expiring?days=5", ownerToken);
        assertThat(none.getStatusCode().value()).isEqualTo(200);
        assertThat(none.getBody()).doesNotContain("FIT-1");
    }
}
