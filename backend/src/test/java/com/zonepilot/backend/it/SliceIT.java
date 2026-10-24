package com.zonepilot.backend.it;

import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DepotRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared slice-3 integration footing: real PostGIS/pgRouting image + real
 * Redis. Subclasses add their own broker/container + DynamicPropertySource.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class SliceIT {

    @Container
    @SuppressWarnings("rawtypes")
    static GenericContainer postgres = new GenericContainer(
                    new ImageFromDockerfile("zonepilot-postgres-it", false)
                            .withDockerfile(Paths.get("../docker/postgres/Dockerfile").toAbsolutePath()))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "zonepilot")
            .withEnv("POSTGRES_USER", "zonepilot")
            .withEnv("POSTGRES_PASSWORD", "zonepilot")
            .waitingFor(Wait.forListeningPort());

    @Container
    @SuppressWarnings("rawtypes")
    static GenericContainer redis = new GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void infraProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/zonepilot");
        registry.add("spring.datasource.username", () -> "zonepilot");
        registry.add("spring.datasource.password", () -> "zonepilot");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected DepotRepository depots;
    @Autowired
    protected VehicleRepository vehicles;
    @Autowired
    protected AppUserRepository users;

    protected final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void cleanUsers() {
        users.deleteAll();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    protected Map<String, Object> post(String path, Object body, String token) {
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

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> data(Map<String, Object> result) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("body")).get("data");
    }

    protected static long dataId(Map<String, Object> result) {
        return ((Number) data(result).get("id")).longValue();
    }

    protected String registerOwner(String email) {
        Map<String, Object> reg = post("/api/auth/register",
                Map.of("email", email, "password", "owner-pass-1", "role", "OWNER"), null);
        if (!reg.get("status").equals(200)) {
            throw new IllegalStateException("register failed: " + reg);
        }
        return String.valueOf(data(reg).get("accessToken"));
    }

    protected Depot saveDepot(String name) {
        Depot d = new Depot();
        d.setName(name);
        d.setAddress(name);
        d.setLocation(gf.createPoint(new Coordinate(77.64, 12.91)));
        return depots.save(d);
    }

    protected Vehicle saveVehicle(Depot depot, String reg) {
        Vehicle v = new Vehicle();
        v.setRegistrationNumber(reg);
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("IT Owner");
        v.setDepot(depot);
        v.setIsActive(true);
        return vehicles.save(v);
    }
}
