package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.GroupDriver;
import com.zonepilot.backend.entity.GroupVehicle;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.it.SliceIT;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1: fleet-group M2M membership. Needs Docker (PostGIS + pgRouting image).
 *
 * Standalone container (not SliceIT): SliceIT's static containers go stale
 * across subclasses in a full-suite run.
 */
@SpringBootTest
@Testcontainers
public class GroupMembershipTest {

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

    @Autowired
    private FleetGroupRepository groups;
    @Autowired
    private VehicleRepository vehicles;
    @Autowired
    private GroupVehicleRepository groupVehicles;
    @Autowired
    private GroupDriverRepository groupDrivers;
    @Autowired
    private DriverRepository drivers;

    private Vehicle mkVehicle() {
        Vehicle v = new Vehicle();
        v.setRegistrationNumber("KA01-GRP-" + Math.abs(System.nanoTime() % 100000));
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Owner");
        v.setIsActive(true);
        return vehicles.save(v);
    }

    private Driver mkDriver() {
        Driver d = new Driver();
        d.setName("Driver");
        d.setLicenceNo("LIC-" + Math.abs(System.nanoTime()));
        return drivers.save(d);
    }

    private FleetGroup mkGroup() {
        FleetGroup g = new FleetGroup();
        g.setName("Group-" + Math.abs(System.nanoTime()));
        g.setActive(true);
        return groups.save(g);
    }

    @Test
    void duplicateVehicleMembership_rejected() {
        FleetGroup g = mkGroup();
        Vehicle v = mkVehicle();
        groupVehicles.save(new GroupVehicle(g, v));
        assertThrows(DataIntegrityViolationException.class,
                () -> groupVehicles.saveAndFlush(new GroupVehicle(g, v)));
    }

    @Test
    void duplicateDriverMembership_rejected() {
        FleetGroup g = mkGroup();
        Vehicle v = mkVehicle();
        Driver d = mkDriver();
        groupDrivers.save(new GroupDriver(g, d));
        assertThrows(DataIntegrityViolationException.class,
                () -> groupDrivers.saveAndFlush(new GroupDriver(g, d)));
    }

    @Test
    void sameVehicleInTwoGroups_allowed() {
        FleetGroup g1 = mkGroup();
        FleetGroup g2 = mkGroup();
        Vehicle v = mkVehicle();
        groupVehicles.save(new GroupVehicle(g1, v));
        groupVehicles.saveAndFlush(new GroupVehicle(g2, v));
        assertEquals(2, groupVehicles.findByVehicleId(v.getId()).size());
    }
}
