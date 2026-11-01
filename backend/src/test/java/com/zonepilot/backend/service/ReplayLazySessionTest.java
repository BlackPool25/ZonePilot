package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * RED: ReplayService.start must not 500 with LazyInitializationException on the
 * lazy FleetGroup proxy at scope.requireGroupAccess(tour.getGroup()).
 * Uses a non-owner DISPATCHER principal so the scope check actually touches
 * the proxy (OWNER short-circuits before touching it).
 */
@SpringBootTest
@Testcontainers
class ReplayLazySessionTest {

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

    @Autowired ReplayService replay;
    @Autowired TourRepository tours;
    @Autowired FleetGroupRepository groups;
    @Autowired DriverRepository drivers;
    @Autowired VehicleRepository vehicles;
    @Autowired VehiclePositionLogRepository logs;
    @Autowired AppUserRepository users;

    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void start_asManager_withHistory_noLazyInit500() {
        String email = "replay-mgr-" + System.nanoTime() + "@test.com";
        AppUser mgr = new AppUser();
        mgr.setEmail(email);
        mgr.setPasswordHash("x");
        mgr.setRole(Role.DISPATCHER);
        users.save(mgr);

        FleetGroup g = new FleetGroup();
        g.setName("ReplayLazy " + System.nanoTime());
        g.setActive(true);
        g.setManager(mgr);
        groups.save(g);

        Driver d = new Driver();
        d.setName("Replay Driver");
        d.setLicenceNo("LIC-REPLAY-" + System.nanoTime());
        drivers.save(d);

        Vehicle v = new Vehicle();
        v.setRegistrationNumber("KA01-RPL-" + (System.nanoTime() % 100000));
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Replay Owner");
        v.setIsActive(true);
        vehicles.save(v);

        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(v);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        tours.save(t);

        VehiclePositionLog fix = new VehiclePositionLog();
        fix.setVehicleId(v.getId());
        fix.setDriverId(d.getId());
        fix.setTourId(t.getId());
        fix.setGroupId(g.getId());
        fix.setPosition(gf.createPoint(new Coordinate(77.62, 12.93)));
        fix.setRecordedAt(Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS));
        fix.setSpeedKmh(BigDecimal.valueOf(30));
        fix.setHeadingDeg((short) 90);
        fix.setSource(PositionSource.LIVE);
        logs.save(fix);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal(email, null, Role.DISPATCHER), null, List.of()));

        assertThatNoException()
                .as("start() must not throw LazyInitializationException (was HTTP 500)")
                .isThrownBy(() -> {
                    try {
                        ReplayService.ReplayResult r = replay.start(t.getId(), 5);
                        assertThat(r.points()).isPositive();
                    } catch (LazyInitializationException e) {
                        throw new AssertionError("LazyInitializationException: " + e.getMessage(), e);
                    }
                });
    }
}
