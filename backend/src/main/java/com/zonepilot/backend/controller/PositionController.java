package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.RecordPositionRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.dto.response.PositionResponse;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.service.PositionTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/vehicles/{vehicleId}/positions")
@Tag(name = "Position", description = "Vehicle position tracking endpoints")
public class PositionController {

    private final PositionTrackingService positionTrackingService;

    public PositionController(PositionTrackingService positionTrackingService) {
        this.positionTrackingService = positionTrackingService;
    }

    @PostMapping
    @Operation(summary = "Record live position", description = "Records a vehicle position and detects zone breaches")
    public ResponseEntity<ApiResponse<PositionRecordResponse>> recordPosition(
            @PathVariable Long vehicleId,
            @Valid @RequestBody RecordPositionRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                positionTrackingService.recordPosition(
                        vehicleId,
                        request.getLat(),
                        request.getLng(),
                        request.getTimestamp(),
                        request.getSpeedKmh(),
                        request.getHeadingDeg(),
                        PositionSource.LIVE)));
    }

    @GetMapping
    @Operation(summary = "Get position history", description = "Returns position history for a vehicle with optional date range")
    public ResponseEntity<ApiResponse<List<PositionResponse>>> getPositionHistory(
            @PathVariable Long vehicleId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        List<PositionResponse> positions = positionTrackingService.getPositionHistory(vehicleId, from, to);
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/latest")
    @Operation(summary = "Get latest position", description = "Returns the most recent position for a vehicle")
    public ResponseEntity<ApiResponse<PositionResponse>> getLatestPosition(
            @PathVariable Long vehicleId) {
        PositionResponse position = positionTrackingService.getLatestPosition(vehicleId);
        return ResponseEntity.ok(ApiResponse.success(position));
    }
}
