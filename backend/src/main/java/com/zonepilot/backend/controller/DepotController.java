package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateDepotRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.DepotResponse;
import com.zonepilot.backend.service.DepotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/depots")
@Tag(name = "Depot", description = "Depot management endpoints")
public class DepotController {

    private final DepotService depotService;

    public DepotController(DepotService depotService) {
        this.depotService = depotService;
    }

    @GetMapping
    @Operation(summary = "List all depots", description = "Returns all depots with their locations")
    public ResponseEntity<ApiResponse<List<DepotResponse>>> getAllDepots() {
        return ResponseEntity.ok(ApiResponse.success(depotService.getAllDepots()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get depot by ID", description = "Returns a single depot by its ID")
    public ResponseEntity<ApiResponse<DepotResponse>> getDepot(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(depotService.getDepotById(id)));
    }

    @PostMapping
    @Operation(summary = "Create a new depot", description = "Creates a depot with name, address, and coordinates")
    public ResponseEntity<ApiResponse<DepotResponse>> createDepot(
            @Valid @RequestBody CreateDepotRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                depotService.createDepot(request.getName(), request.getAddress(),
                        request.getLat(), request.getLng())));
    }
}
