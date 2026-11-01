package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2: strict position keys. Every fix carries driver+tour+group
 * (strict FK, tour-scoped unique); pre-tour rows backfill to a dummy tour.
 *
 * Standalone containers (not SliceIT): static containers go stale across
 * subclasses in a full-suite run. Own Redis: recordPosition touches IngestState.
 */
@SpringBootTest
@Testcontainers
class PositionKeysTest {

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

    @Autowired
    PositionTrackingService positions;
    @Autowired
    VehicleRepository vehicles;
    @Autowired
    DriverRepository drivers;
    @Autowired
    FleetGroupRepository groups;
    @Autowired
    TourRepository tours;
    @Autowired
    VehiclePositionLogRepository logs;
    @Autowired
    JdbcTemplate jdbc;

    private FleetGroup mkGroup() {
        FleetGroup g = new FleetGroup();
        g.setName("KeyGroup " + System.nanoTime());
        g.setActive(true);
        return groups.save(g);
    }

    private Driver mkDriver() {
        Driver d = new Driver();
        d.setName("Key Driver");
        d.setLicenceNo("LIC-KEY-" + System.nanoTime());
        return drivers.save(d);
    }

    private Vehicle mkVehicle(String reg) {
        Vehicle v = new Vehicle();
        v.setRegistrationNumber(reg);
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Key Owner");
        v.setIsActive(true);
        return vehicles.save(v);
    }

    @BeforeEach
    void ownerAuth() {
        // Direct recordPosition calls run as OWNER (manager path with group
        // access); null-auth now requires worker identity.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal("owner@zp.test", null, Role.OWNER), null, java.util.List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private Tour mkTour() {
        Tour t = new Tour();
        t.setGroup(mkGroup());
        t.setVehicle(mkVehicle("KA01-KEY-" + (System.nanoTime() % 100000)));
        t.setDriver(mkDriver());
        t.setStatus(TourStatus.ACTIVE);
        return tours.save(t);
    }

    @Test
    void insertWithoutTour_rejected() {
        Vehicle v = mkVehicle("KA01-NOTOUR-" + (System.nanoTime() % 100000));
        Instant ts = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        // Missing tour/driver/group columns: NOT NULL rejects.
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO vehicle_position_log (vehicle_id, position, recorded_at, source) VALUES ("
                        + v.getId() + ", ST_SetSRID(ST_Point(77.62, 12.93), 4326), '" + ts + "', 'LIVE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Dangling tour FK rejects.
        assertThatThrownBy(() -> jdbc.execute(
                "INSERT INTO vehicle_position_log (vehicle_id, driver_id, tour_id, group_id, position, recorded_at, source) VALUES ("
                        + v.getId() + ", 1, -999999, 1, ST_SetSRID(ST_Point(77.62, 12.93), 4326), '" + ts + "', 'LIVE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void legacyRows_backfilledToDummyTour() {
        Long nulls = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_position_log WHERE tour_id IS NULL OR driver_id IS NULL OR group_id IS NULL",
                Long.class);
        assertThat(nulls).isZero();
        Long dummyTours = jdbc.queryForObject(
                "SELECT count(*) FROM tour t JOIN vehicle v ON v.id = t.vehicle_id "
                        + "WHERE v.registration_number = 'HISTORIC'",
                Long.class);
        assertThat(dummyTours).isEqualTo(1L);
    }

    @Test
    void burstSameMsDuplicate_ignoredNot500() {
        Tour t = mkTour();
        Instant ts = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        PositionRecordResponse first = positions.recordPosition(
                t.getVehicle().getId(), t.getDriver().getId(), t.getId(), t.getGroup().getId(),
                12.93, 77.62, ts, null, null, PositionSource.LIVE);
        assertThat(first.getPosition().getRecordedAt()).isEqualTo(ts);
        // Same-ms redelivery: accepted response, still exactly one row.
        PositionRecordResponse redelivery = positions.recordPosition(
                t.getVehicle().getId(), t.getDriver().getId(), t.getId(), t.getGroup().getId(),
                12.93, 77.62, ts, null, null, PositionSource.LIVE);
        assertThat(redelivery.getPosition().getRecordedAt()).isEqualTo(ts);
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle_position_log WHERE vehicle_id = ? AND tour_id = ? AND recorded_at = ?",
                Long.class, t.getVehicle().getId(), t.getId(), java.sql.Timestamp.from(ts));
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void midTourSwap_acceptedSameTour() {
        Tour t = mkTour();
        Driver relief = mkDriver();
        Instant ts1 = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
        Instant ts2 = ts1.plusSeconds(30);
        positions.recordPosition(t.getVehicle().getId(), t.getDriver().getId(), t.getId(), t.getGroup().getId(),
                12.93, 77.62, ts1, null, null, PositionSource.LIVE);
        // Same tour, new driver keys: accepted, not a unique clash.
        positions.recordPosition(t.getVehicle().getId(), relief.getId(), t.getId(), t.getGroup().getId(),
                12.94, 77.63, ts2, null, null, PositionSource.LIVE);
        assertThat(logs.findByVehicleIdAndTourIdAndRecordedAt(
                t.getVehicle().getId(), t.getId(), ts2)).isPresent();
        assertThat(logs.findByVehicleIdAndTourIdAndRecordedAt(
                t.getVehicle().getId(), t.getId(), ts2).orElseThrow().getDriverId())
                .isEqualTo(relief.getId());
    }
}
