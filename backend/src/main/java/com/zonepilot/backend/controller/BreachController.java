package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.BreachResponse;
import com.zonepilot.backend.service.BreachQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/breaches")
@Tag(name = "Breach", description = "Zone breach log endpoints")
public class BreachController {

    private final BreachQueryService breachQueryService;

    public BreachController(BreachQueryService breachQueryService) {
        this.breachQueryService = breachQueryService;
    }

    @GetMapping
    @Operation(summary = "List all breaches", description = "Returns breach logs with optional filters")
    public ResponseEntity<ApiResponse<List<BreachResponse>>> getBreaches(
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(required = false) Long zoneId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Boolean unacknowledged) {
        return ResponseEntity.ok(ApiResponse.success(
                breachQueryService.getBreaches(vehicleId, zoneId, from, to, unacknowledged)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get breach detail", description = "Returns a single breach log entry")
    public ResponseEntity<ApiResponse<BreachResponse>> getBreach(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(breachQueryService.getBreachById(id)));
    }

    @PutMapping("/{id}/acknowledge")
    @Operation(summary = "Acknowledge a breach", description = "Marks a breach as acknowledged")
    public ResponseEntity<ApiResponse<BreachResponse>> acknowledgeBreach(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(breachQueryService.acknowledgeBreach(id)));
    }
}
