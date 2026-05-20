package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateVehicleRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.VehicleResponse;
import com.zonepilot.backend.dto.response.ZoneResponse;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
@Tag(name = "Vehicle", description = "Vehicle management endpoints")
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping
    @Operation(summary = "List all vehicles", description = "Returns all vehicles with optional filtering by class and active status")
    public ResponseEntity<ApiResponse<List<VehicleResponse>>> getAllVehicles(
            @RequestParam(required = false) VehicleClass vehicleClass,
            @RequestParam(required = false) Boolean isActive) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.getAllVehicles(vehicleClass, isActive)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get vehicle by ID", description = "Returns a single vehicle by its ID")
    public ResponseEntity<ApiResponse<VehicleResponse>> getVehicle(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.getVehicleById(id)));
    }

    @PostMapping
    @Operation(summary = "Register a new vehicle", description = "Creates a new vehicle in the fleet")
    public ResponseEntity<ApiResponse<VehicleResponse>> createVehicle(
            @Valid @RequestBody CreateVehicleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.createVehicle(request)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a vehicle", description = "Updates an existing vehicle's details")
    public ResponseEntity<ApiResponse<VehicleResponse>> updateVehicle(
            @PathVariable Long id,
            @Valid @RequestBody CreateVehicleRequest request) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.updateVehicle(id, request)));
    }

    @GetMapping("/{id}/zones-at-location")
    @Operation(summary = "Get active zones at a location", description = "Returns zones that contain a given point")
    public ResponseEntity<ApiResponse<List<ZoneResponse>>> getZonesAtLocation(
            @PathVariable Long id,
            @RequestParam double lat,
            @RequestParam double lng) {
        return ResponseEntity.ok(ApiResponse.success(vehicleService.getActiveZonesAtPoint(lat, lng)));
    }
}
