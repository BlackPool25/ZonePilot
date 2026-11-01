package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.service.GroupDispatchService;
import com.zonepilot.backend.service.GroupService;
import com.zonepilot.backend.service.TickBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Manager group endpoints: CRUD, membership, availability detail. */
@RestController
@RequestMapping("/api/groups")
@Tag(name = "Groups", description = "Fleet group management")
public class GroupController {

    private final GroupService groupService;
    private final GroupDispatchService dispatchService;
    private final FleetGroupRepository groups;
    private final GroupScope scope;
    private final TickBroadcaster ticks;

    public GroupController(GroupService groupService, GroupDispatchService dispatchService,
                           FleetGroupRepository groups, GroupScope scope,
                           TickBroadcaster ticks) {
        this.groupService = groupService;
        this.dispatchService = dispatchService;
        this.groups = groups;
        this.scope = scope;
        this.ticks = ticks;
    }

    @GetMapping
    @Operation(summary = "List all fleet groups")
    public ResponseEntity<ApiResponse<List<FleetGroup>>> list() {
        return ResponseEntity.ok(ApiResponse.success(groupService.listGroups()));
    }

    @PostMapping
    @Operation(summary = "Create a fleet group")
    public ResponseEntity<ApiResponse<FleetGroup>> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(groupService.createGroup(body.get("name"))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Group detail with member availability")
    public ResponseEntity<ApiResponse<GroupService.GroupDetail>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.groupDetail(id)));
    }

    @PostMapping("/{id}/vehicles/{vehicleId}")
    @Operation(summary = "Add a vehicle to the group")
    public ResponseEntity<ApiResponse<String>> addVehicle(@PathVariable Long id, @PathVariable Long vehicleId) {
        groupService.addVehicle(id, vehicleId);
        return ResponseEntity.ok(ApiResponse.success("added"));
    }

    @DeleteMapping("/{id}/vehicles/{vehicleId}")
    @Operation(summary = "Remove a vehicle from the group")
    public ResponseEntity<ApiResponse<String>> removeVehicle(@PathVariable Long id, @PathVariable Long vehicleId) {
        groupService.removeVehicle(id, vehicleId);
        return ResponseEntity.ok(ApiResponse.success("removed"));
    }

    @PostMapping("/{id}/drivers/{driverId}")
    @Operation(summary = "Add a driver to the group")
    public ResponseEntity<ApiResponse<String>> addDriver(@PathVariable Long id, @PathVariable Long driverId) {
        groupService.addDriver(id, driverId);
        return ResponseEntity.ok(ApiResponse.success("added"));
    }

    @DeleteMapping("/{id}/drivers/{driverId}")
    @Operation(summary = "Remove a driver from the group")
    public ResponseEntity<ApiResponse<String>> removeDriver(@PathVariable Long id, @PathVariable Long driverId) {
        groupService.removeDriver(id, driverId);
        return ResponseEntity.ok(ApiResponse.success("removed"));
    }

    @PostMapping("/{id}/dispatch")
    @Operation(summary = "Dispatch all group pairs on one cargo run")
    public ResponseEntity<ApiResponse<GroupDispatchService.DispatchResult>> dispatchGroup(
            @PathVariable Long id, @RequestBody DispatchRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dispatchService.dispatchGroup(
                id, request.originLat(), request.originLng(), request.destLat(), request.destLng())));
    }

    @PostMapping("/dispatch-solo")
    @Operation(summary = "Dispatch one vehicle + driver on a cargo run")
    public ResponseEntity<ApiResponse<GroupDispatchService.DispatchResult>> dispatchSolo(
            @RequestBody SoloDispatchRequest request) {
        return ResponseEntity.ok(ApiResponse.success(dispatchService.dispatchSolo(
                request.vehicleId(), request.driverId(),
                request.originLat(), request.originLng(), request.destLat(), request.destLng())));
    }

    @GetMapping("/{id}/ticks")
    @Operation(summary = "Missed ticks since a timestamp (WS reconnect backfill)",
            description = "Oldest first. Pass the last seen recordedAt as since; " +
                    "omit for the newest frames. Same JWT group scope as the WS subscribe.")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> ticks(
            @PathVariable Long id,
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "20") int limit) {
        FleetGroup group = groups.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", id));
        scope.requireGroupAccess(group);
        return ResponseEntity.ok(ApiResponse.success(ticks.recentTicks(id, since, limit)));
    }

    public record DispatchRequest(double originLat, double originLng, double destLat, double destLng) {}

    public record SoloDispatchRequest(Long vehicleId, Long driverId,
                                       double originLat, double originLng,
                                       double destLat, double destLng) {}
}
