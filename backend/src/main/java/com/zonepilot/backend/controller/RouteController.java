package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.ValidateRouteRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.service.RouteComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
@Tag(name = "Route", description = "Route validation and management endpoints")
public class RouteController {

    private final RouteComplianceService routeComplianceService;
    private final DispatchRouteRepository dispatchRouteRepository;

    public RouteController(RouteComplianceService routeComplianceService,
                           DispatchRouteRepository dispatchRouteRepository) {
        this.routeComplianceService = routeComplianceService;
        this.dispatchRouteRepository = dispatchRouteRepository;
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate route pre-dispatch", description = "Validates a route against zone restrictions using pgRouting")
    public ResponseEntity<ApiResponse<RouteValidationResponse>> validateRoute(
            @Valid @RequestBody ValidateRouteRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                routeComplianceService.validateRoute(
                        request.getVehicleId(),
                        request.getOriginLat(),
                        request.getOriginLng(),
                        request.getDestLat(),
                        request.getDestLng())));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get route detail", description = "Returns a dispatch route by ID")
    public ResponseEntity<ApiResponse<DispatchRoute>> getRoute(@PathVariable Long id) {
        return dispatchRouteRepository.findById(id)
                .map(route -> ResponseEntity.ok(ApiResponse.success(route)))
                .orElseGet(() -> ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("RESOURCE_NOT_FOUND", "Route not found with id: " + id)));
    }

    @GetMapping("/vehicle/{vehicleId}")
    @Operation(summary = "Get route history for vehicle", description = "Returns all dispatch routes for a vehicle")
    public ResponseEntity<ApiResponse<List<DispatchRoute>>> getVehicleRoutes(@PathVariable Long vehicleId) {
        return ResponseEntity.ok(ApiResponse.success(
                dispatchRouteRepository.findByVehicleIdOrderByCreatedAtDesc(vehicleId)));
    }
}
