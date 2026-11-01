package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.DutyLog;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.DutyLogRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ELD auto-duty (FMCSA motion pattern): speed &gt; 8 km/h sustained 60s
 * auto-sets DRIVING; stopped 5 min drops back to ON_DUTY. Sub-threshold
 * motion never flips duty. Driver override stays on the manual duty endpoint.
 */
@SpringBootTest
@Testcontainers
class EldAutoDutyTest {

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

    @Autowired PositionTrackingService positions;
    @Autowired VehicleRepository vehicles;
    @Autowired DriverRepository drivers;
    @Autowired FleetGroupRepository groups;
    @Autowired TourRepository tours;
    @Autowired DutyLogRepository dutyLogs;

    private Tour mkTour() {
        FleetGroup g = new FleetGroup();
        g.setName("EldGroup " + System.nanoTime());
        g.setActive(true);
        groups.save(g);
        Driver d = new Driver();
        d.setName("Eld Driver");
        d.setLicenceNo("LIC-ELD-" + System.nanoTime());
        drivers.save(d);
        Vehicle v = new Vehicle();
        v.setRegistrationNumber("KA01-ELD-" + (System.nanoTime() % 100000));
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Eld Owner");
        v.setIsActive(true);
        vehicles.save(v);
        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(v);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        return tours.save(t);
    }

    private void fix(Tour t, Instant ts, double speedKmh) {
        positions.recordPosition(
                t.getVehicle().getId(), t.getDriver().getId(), t.getId(), t.getGroup().getId(),
                12.93, 77.62, ts, BigDecimal.valueOf(speedKmh), (short) 90, PositionSource.LIVE);
    }

    private DutyStatus dutyOf(Long tourId) {
        return tours.findById(tourId).orElseThrow().getDutyStatus();
    }

    @Test
    void movingFixSequence_flipsDutyToDriving() {
        Tour t = mkTour();
        Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(5);
        // 30 km/h every 15s for 60s, ending now-ish.
        for (int s = 60; s >= 0; s -= 15) {
            fix(t, end.minusSeconds(s), 30.0);
        }
        assertThat(dutyOf(t.getId())).isEqualTo(DutyStatus.DRIVING);
        assertThat(dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(t.getId()))
                .extracting(DutyLog::getDutyStatus)
                .contains(DutyStatus.DRIVING);
    }

    @Test
    void stoppedFiveMinutes_flipsBackToOnDuty() {
        Tour t = mkTour();
        Instant driveEnd = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(400);
        for (int s = 60; s >= 0; s -= 15) {
            fix(t, driveEnd.minusSeconds(s), 30.0);
        }
        assertThat(dutyOf(t.getId())).isEqualTo(DutyStatus.DRIVING);
        // Stopped (0 km/h) every 60s for 5+ min after the drive.
        for (int s = 300; s >= 0; s -= 60) {
            fix(t, driveEnd.plusSeconds(360).minusSeconds(s), 0.0);
        }
        assertThat(dutyOf(t.getId())).isEqualTo(DutyStatus.ON_DUTY);
    }

    @Test
    void subThresholdMotion_neverFlipsDuty() {
        Tour t = mkTour();
        Instant end = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(5);
        // 5 km/h (<=5mph FMCSA pattern) sustained: no auto-DRIVING.
        for (int s = 60; s >= 0; s -= 15) {
            fix(t, end.minusSeconds(s), 5.0);
        }
        assertThat(dutyOf(t.getId())).isNotEqualTo(DutyStatus.DRIVING);
        assertThat(dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(t.getId())).isEmpty();
    }
}
