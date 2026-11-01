package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Group + solo dispatch: validate zone-compliant route per vehicle,
 * pair group members in list order, then assign the driver.
 * Ineligible pairs are skipped with a reason; never abort the batch.
 */
@Service
public class GroupDispatchService {

    private final FleetGroupRepository groups;
    private final GroupScope scope;
    private final GroupVehicleRepository groupVehicles;
    private final GroupDriverRepository groupDrivers;
    private final VehicleRepository vehicles;
    private final DriverRepository drivers;
    private final DriverService driverService;
    private final RouteComplianceService compliance;
    private final AuditService audit;

    public GroupDispatchService(FleetGroupRepository groups, GroupScope scope,
                                GroupVehicleRepository groupVehicles, GroupDriverRepository groupDrivers,
                                VehicleRepository vehicles, DriverRepository drivers,
                                DriverService driverService, RouteComplianceService compliance) {
        this(groups, scope, groupVehicles, groupDrivers, vehicles, drivers,
                driverService, compliance, AuditService.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GroupDispatchService(FleetGroupRepository groups, GroupScope scope,
                                GroupVehicleRepository groupVehicles, GroupDriverRepository groupDrivers,
                                VehicleRepository vehicles, DriverRepository drivers,
                                DriverService driverService, RouteComplianceService compliance,
                                AuditService audit) {
        this.groups = groups;
        this.scope = scope;
        this.groupVehicles = groupVehicles;
        this.groupDrivers = groupDrivers;
        this.vehicles = vehicles;
        this.drivers = drivers;
        this.driverService = driverService;
        this.compliance = compliance;
        this.audit = audit == null ? AuditService.noop() : audit;
    }

    @Transactional
    public DispatchResult dispatchGroup(Long groupId, double originLat, double originLng,
                                        double destLat, double destLng) {
        FleetGroup g = groups.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", groupId));
        scope.requireGroupAccess(g);
        List<Vehicle> vehicles = groupVehicles.findByGroupId(groupId).stream()
                .map(gv -> gv.getVehicle()).toList();
        List<Driver> drivers = groupDrivers.findByGroupId(groupId).stream()
                .map(gd -> gd.getDriver()).toList();
        List<Dispatched> dispatched = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        int pairs = Math.max(vehicles.size(), drivers.size());
        for (int i = 0; i < pairs; i++) {
            Vehicle v = i < vehicles.size() ? vehicles.get(i) : null;
            Driver d = i < drivers.size() ? drivers.get(i) : null;
            if (v == null) {
                skipped.add(new Skipped(null, d == null ? null : d.getId(), "no vehicle paired"));
                continue;
            }
            if (d == null) {
                skipped.add(new Skipped(v.getId(), null, "no driver paired"));
                continue;
            }
            try {
                // Fail fast before Google spend: only a free vehicle dispatches.
                // RESERVED/IN_TRANSIT means already on a tour (double-book guard).
                if (v.getAvailability() != VehicleAvailability.AVAILABLE) {
                    skipped.add(new Skipped(v.getId(), d.getId(), "vehicle "
                            + v.getAvailability().name().toLowerCase() + ", not dispatchable"));
                    continue;
                }
                driverService.checkEligible(d, v);
                RouteValidationResponse validation =
                        compliance.validateRoute(v.getId(), originLat, originLng, destLat, destLng);
                DispatchRoute route = driverService.assign(validation.getDispatchRouteId(), d.getId());
                dispatched.add(new Dispatched(route.getId(), v.getId(), d.getId()));
                audit.log(actor(), "DISPATCH", null, null,
                        "group=" + groupId + " vehicle=" + v.getId() + " driver=" + d.getId(),
                        null, null);
            } catch (RuntimeException e) {
                skipped.add(new Skipped(v.getId(), d.getId(), e.getMessage()));
            }
        }
        return new DispatchResult(dispatched, skipped);
    }

    @Transactional
    public DispatchResult dispatchSolo(Long vehicleId, Long driverId, double originLat, double originLng,
                                        double destLat, double destLng) {
        List<Dispatched> dispatched = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        try {
            // Eligibility gate before Google spend (assign re-checks at write time).
            Vehicle vehicle = vehicles.findById(vehicleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
            Driver driver = drivers.findById(driverId)
                    .orElseThrow(() -> new ResourceNotFoundException("Driver", "id", driverId));
            driverService.checkEligible(driver, vehicle);
            RouteValidationResponse validation =
                    compliance.validateRoute(vehicleId, originLat, originLng, destLat, destLng);
            DispatchRoute route = driverService.assign(validation.getDispatchRouteId(), driverId);
            dispatched.add(new Dispatched(route.getId(), vehicleId, driverId));
            audit.log(actor(), "DISPATCH", null, null,
                    "vehicle=" + vehicleId + " driver=" + driverId, null, null);
        } catch (RuntimeException e) {
            skipped.add(new Skipped(vehicleId, driverId, e.getMessage()));
        }
        return new DispatchResult(dispatched, skipped);
    }

    private static String actor() {
        com.zonepilot.backend.auth.DepotPrincipal caller =
                com.zonepilot.backend.auth.DepotScope.current();
        return caller == null ? "system" : caller.username();
    }

    public record Dispatched(Long routeId, Long vehicleId, Long driverId) {}

    public record Skipped(Long vehicleId, Long driverId, String reason) {}

    public record DispatchResult(List<Dispatched> dispatched, List<Skipped> skipped) {}
}
