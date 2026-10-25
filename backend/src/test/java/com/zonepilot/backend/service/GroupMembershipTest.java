package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Depot;
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
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1: fleet-group M2M membership. Needs Docker (PostGIS + pgRouting image).
 */
public class GroupMembershipTest extends SliceIT {

    @Autowired
    private FleetGroupRepository groups;
    @Autowired
    private GroupVehicleRepository groupVehicles;
    @Autowired
    private GroupDriverRepository groupDrivers;
    @Autowired
    private DriverRepository drivers;

    private Vehicle mkVehicle() {
        Depot depot = new Depot();
        depot.setName("Test Depot");
        depot.setAddress("Test Address");
        depot.setLocation(gf.createPoint(new Coordinate(77.6, 12.9)));
        depots.save(depot);
        Vehicle v = new Vehicle();
        v.setRegistrationNumber("KA01-GRP-" + Math.abs(System.nanoTime() % 100000));
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Owner");
        v.setDepot(depot);
        v.setIsActive(true);
        return vehicles.save(v);
    }

    private Driver mkDriver(Depot depot) {
        Driver d = new Driver();
        d.setDepot(depot);
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
        Driver d = mkDriver(v.getDepot());
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
