package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.GroupDriver;
import com.zonepilot.backend.entity.GroupVehicle;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.exception.ConflictException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Flat fleet groups: CRUD, M2M membership, availability detail.
 * Manager scoping is added in Task 4; depot removal in Task 5.
 */
@Service
public class GroupService {

    private static final List<AssignmentStatus> ON_TRIP =
            List.of(AssignmentStatus.ASSIGNED, AssignmentStatus.ACCEPTED, AssignmentStatus.IN_TRANSIT);

    private final FleetGroupRepository groups;
    private final GroupVehicleRepository groupVehicles;
    private final GroupDriverRepository groupDrivers;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final DispatchRouteRepository routes;

    public GroupService(FleetGroupRepository groups, GroupVehicleRepository groupVehicles,
                        GroupDriverRepository groupDrivers, VehicleRepository vehicles,
                        DriverRepository drivers, DispatchRouteRepository routes) {
        this.groups = groups;
        this.groupVehicles = groupVehicles;
        this.groupDrivers = groupDrivers;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.routes = routes;
    }

    @Transactional
    public FleetGroup createGroup(String name) {
        FleetGroup g = new FleetGroup();
        g.setName(name);
        g.setActive(true);
        return groups.save(g);
    }

    @Transactional(readOnly = true)
    public List<FleetGroup> listGroups() {
        return groups.findAll();
    }

    @Transactional
    public void addVehicle(Long groupId, Long vehicleId) {
        FleetGroup g = groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", groupId));
        Vehicle v = vehicles.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
        if (groupVehicles.existsByGroupIdAndVehicleId(groupId, vehicleId)) {
            throw new ConflictException("Vehicle already in group");
        }
        groupVehicles.save(new GroupVehicle(g, v));
    }

    @Transactional
    public void addDriver(Long groupId, Long driverId) {
        FleetGroup g = groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", groupId));
        Driver d = drivers.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", driverId));
        if (groupDrivers.existsByGroupIdAndDriverId(groupId, driverId)) {
            throw new ConflictException("Driver already in group");
        }
        groupDrivers.save(new GroupDriver(g, d));
    }

    @Transactional
    public void removeVehicle(Long groupId, Long vehicleId) {
        groupVehicles.deleteByGroupIdAndVehicleId(groupId, vehicleId);
    }

    @Transactional
    public void removeDriver(Long groupId, Long driverId) {
        groupDrivers.deleteByGroupIdAndDriverId(groupId, driverId);
    }

    @Transactional(readOnly = true)
    public GroupDetail groupDetail(Long groupId) {
        if (!groups.existsById(groupId)) {
            throw new ResourceNotFoundException("FleetGroup", "id", groupId);
        }
        List<MemberStatus> vehicleStatuses = groupVehicles.findByGroupId(groupId).stream()
                .map(gv -> new MemberStatus(
                        gv.getVehicle().getId(),
                        gv.getVehicle().getRegistrationNumber(),
                        routes.existsByVehicleIdAndAssignmentStatusIn(gv.getVehicle().getId(), ON_TRIP)))
                .toList();
        List<MemberStatus> driverStatuses = groupDrivers.findByGroupId(groupId).stream()
                .map(gd -> new MemberStatus(
                        gd.getDriver().getId(),
                        gd.getDriver().getName(),
                        routes.existsByDriverIdAndAssignmentStatusIn(gd.getDriver().getId(), ON_TRIP)))
                .toList();
        return new GroupDetail(groupId, vehicleStatuses, driverStatuses);
    }

    public record MemberStatus(Long id, String label, boolean busy) {}

    public record GroupDetail(Long groupId, List<MemberStatus> vehicles, List<MemberStatus> drivers) {}
}
