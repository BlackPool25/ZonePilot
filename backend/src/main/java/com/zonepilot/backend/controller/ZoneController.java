package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateZoneRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.ZoneResponse;
import com.zonepilot.backend.service.ZoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/zones")
@Tag(name = "Zone", description = "Zone restriction management endpoints")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    @GetMapping
    @Operation(summary = "List all zones", description = "Returns all zone restrictions with their boundaries")
    public ResponseEntity<ApiResponse<List<ZoneResponse>>> getAllZones() {
        return ResponseEntity.ok(ApiResponse.success(zoneService.getAllZones()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get zone by ID", description = "Returns a single zone with its rules")
    public ResponseEntity<ApiResponse<ZoneResponse>> getZone(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(zoneService.getZoneById(id)));
    }

    @GetMapping("/active")
    @Operation(summary = "List active zones", description = "Returns all currently active zone restrictions")
    public ResponseEntity<ApiResponse<List<ZoneResponse>>> getActiveZones() {
        return ResponseEntity.ok(ApiResponse.success(zoneService.getActiveZones()));
    }

    @PostMapping
    @Operation(summary = "Create a new zone", description = "Creates a zone restriction with a GeoJSON boundary")
    public ResponseEntity<ApiResponse<ZoneResponse>> createZone(
            @Valid @RequestBody CreateZoneRequest request) {
        return ResponseEntity.ok(ApiResponse.success(zoneService.createZone(request)));
    }
}
