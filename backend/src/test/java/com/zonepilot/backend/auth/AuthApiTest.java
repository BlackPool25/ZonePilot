package com.zonepilot.backend.auth;

import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.repository.DepotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.core.ParameterizedTypeReference;import org.springframework.http.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 1 integration: Flyway V1–V22 migrate for real on our
 * PostGIS/pgRouting image; auth + depot RBAC end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthApiTest {

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
    AppUserRepository users;

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    private Depot depotA;
    private Depot depotB;

    @BeforeEach
    void seedDepots() {
        // app_user has no child FKs: reset so each test can bootstrap its OWNER.
        // (Depots/vehicles persist: vehicle + app_user rows FK-reference depots.)
        users.deleteAll();
        depotA = saveDepot("Depot A HSR " + System.nanoTime(), 12.91, 77.64);
        depotB = saveDepot("Depot B Peenya " + System.nanoTime(), 13.03, 77.52);
    }

    private Depot saveDepot(String name, double lat, double lng) {
        Depot d = new Depot();
        d.setName(name);
        d.setAddress(name + " address");
        d.setLocation(gf.createPoint(new Coordinate(lng, lat)));
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
        return Map.of("status", res.getStatusCode().value(), "body", res.getBody());
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private static String dataField(Map<String, Object> result, String field) {
        Map<String, Object> body = (Map<String, Object>) result.get("body");
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return String.valueOf(data.get(field));
    }

    @Test
    @SuppressWarnings("unchecked")
    void register_login_me_refresh_rotation_happyPath() {
        Map<String, Object> reg = post("/api/auth/register",
                Map.of("email", "owner@zonepilot.io", "password", "owner-pass-1", "role", "OWNER"), null);
        assertThat(reg.get("status")).isEqualTo(200);
        String ownerAccess = dataField(reg, "accessToken");
        String ownerRefresh = dataField(reg, "refreshToken");

        Map<String, Object> me = Map.of("status", get("/api/auth/me", ownerAccess).getStatusCode().value());
        assertThat(me.get("status")).isEqualTo(200);

        // OWNER creates a dispatcher in depot A
        Map<String, Object> regDisp = post("/api/auth/register",
                Map.of("email", "disp@hsr.test", "password", "disp-pass-1",
                        "role", "DISPATCHER", "depotId", depotA.getId()), ownerAccess);
        assertThat(regDisp.get("status")).isEqualTo(200);

        // wrong password -> 401, not 403
        Map<String, Object> badLogin = post("/api/auth/login",
                Map.of("email", "disp@hsr.test", "password", "nope-nope-nope"), null);
        assertThat(badLogin.get("status")).isEqualTo(401);

        // refresh rotation: old refresh must stop working
        Map<String, Object> rotated = post("/api/auth/refresh",
                Map.of("refreshToken", ownerRefresh), null);
        assertThat(rotated.get("status")).isEqualTo(200);
        Map<String, Object> replay = post("/api/auth/refresh",
                Map.of("refreshToken", ownerRefresh), null);
        assertThat(replay.get("status")).isEqualTo(403);
    }

    @Test
    void noToken_is401() {
        assertThat(get("/api/vehicles", null).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/auth/me", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void crossDepot_is403_andListIsScoped() {
        Map<String, Object> regOwner = post("/api/auth/register",
                Map.of("email", "owner2@zonepilot.io", "password", "owner-pass-1", "role", "OWNER"), null);
        String ownerAccess = dataField(regOwner, "accessToken");

        Map<String, Object> regDisp = post("/api/auth/register",
                Map.of("email", "disp2@hsr.test", "password", "disp-pass-1",
                        "role", "DISPATCHER", "depotId", depotA.getId()), ownerAccess);
        String dispAccess = dataField(regDisp, "accessToken");

        // OWNER creates one vehicle per depot
        Map<String, Object> vehA = post("/api/auth/register", Map.of(), ownerAccess); // placeholder, replaced below
        assertThat(vehA.get("status")).isEqualTo(400); // validation error path (empty body)

        Map<String, Object> createdA = post("/api/vehicles",
                Map.of("registrationNumber", "KA05-AA-1001", "vehicleClass", "LCV",
                        "ownerName", "Owner A", "depotId", depotA.getId()), ownerAccess);
        assertThat(createdA.get("status")).isEqualTo(200);
        Map<String, Object> createdB = post("/api/vehicles",
                Map.of("registrationNumber", "KA05-AA-1002", "vehicleClass", "HCV",
                        "ownerName", "Owner B", "depotId", depotB.getId()), ownerAccess);
        assertThat(createdB.get("status")).isEqualTo(200);

        Map<String, Object> bodyB = (Map<String, Object>) createdB.get("body");
        Map<String, Object> dataB = (Map<String, Object>) bodyB.get("data");
        long vehBId = ((Number) dataB.get("id")).longValue();

        // dispatcher of A reads vehicle of B -> 403
        assertThat(get("/api/vehicles/" + vehBId, dispAccess).getStatusCode().value()).isEqualTo(403);

        // dispatcher of A creates vehicle in B -> 403, client depot id untrusted
        Map<String, Object> crossCreate = post("/api/vehicles",
                Map.of("registrationNumber", "KA05-AA-1003", "vehicleClass", "LCV",
                        "ownerName", "Sneaky", "depotId", depotB.getId()), dispAccess);
        assertThat(crossCreate.get("status")).isEqualTo(403);

        // list as dispatcher A is depot-scoped (only A's vehicle visible)
        ResponseEntity<String> list = get("/api/vehicles", dispAccess);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("KA05-AA-1001");
        assertThat(list.getBody()).doesNotContain("KA05-AA-1002");
    }
}
