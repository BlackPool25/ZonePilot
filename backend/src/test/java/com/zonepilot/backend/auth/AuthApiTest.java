package com.zonepilot.backend.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Slice 1 integration: Flyway migrations run for real on our
 * PostGIS/pgRouting image; auth + single-fleet access end to end.
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
    AppUserRepository users;

    @BeforeEach
    void seedUsers() {
        // app_user has no child FKs: reset so each test can bootstrap its OWNER.
        users.deleteAll();
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

        // OWNER creates a dispatcher (single fleet, no depot assignment)
        Map<String, Object> regDisp = post("/api/auth/register",
                Map.of("email", "disp@hsr.test", "password", "disp-pass-1",
                        "role", "DISPATCHER"), ownerAccess);
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
    void dispatcher_seesWholeFleet() {
        Map<String, Object> regOwner = post("/api/auth/register",
                Map.of("email", "owner2@zonepilot.io", "password", "owner-pass-1", "role", "OWNER"), null);
        String ownerAccess = dataField(regOwner, "accessToken");

        Map<String, Object> regDisp = post("/api/auth/register",
                Map.of("email", "disp2@hsr.test", "password", "disp-pass-1",
                        "role", "DISPATCHER"), ownerAccess);
        String dispAccess = dataField(regDisp, "accessToken");

        Map<String, Object> vehA = post("/api/auth/register", Map.of(), ownerAccess); // placeholder, replaced below
        assertThat(vehA.get("status")).isEqualTo(400); // validation error path (empty body)

        Map<String, Object> createdA = post("/api/vehicles",
                Map.of("registrationNumber", "KA05-AA-1001", "vehicleClass", "LCV",
                        "ownerName", "Owner A"), ownerAccess);
        assertThat(createdA.get("status")).isEqualTo(200);
        Map<String, Object> createdB = post("/api/vehicles",
                Map.of("registrationNumber", "KA05-AA-1002", "vehicleClass", "HCV",
                        "ownerName", "Owner B"), ownerAccess);
        assertThat(createdB.get("status")).isEqualTo(200);

        // single fleet: dispatcher sees every vehicle
        ResponseEntity<String> list = get("/api/vehicles", dispAccess);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("KA05-AA-1001");
        assertThat(list.getBody()).contains("KA05-AA-1002");
    }
}
