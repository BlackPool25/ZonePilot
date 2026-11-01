package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.service.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
@Tag(name = "Report", description = "Reporting and analytics endpoints")
public class ReportController {

    private final ReportingService reportingService;

    public ReportController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Fleet breach summary", description = "Returns aggregate breach statistics per vehicle; tourId/groupId scope to one tour or group (managers see own groups only)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getSummary(
            @RequestParam(required = false) Long tourId,
            @RequestParam(required = false) Long groupId) {
        return ResponseEntity.ok(ApiResponse.success(reportingService.getVehicleBreachSummary(tourId, groupId)));
    }

    @GetMapping("/zones/{zoneId}/violations")
    @Operation(summary = "Zone violation stats", description = "Returns violation statistics for a specific zone")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getZoneViolations(
            @PathVariable Long zoneId) {
        return ResponseEntity.ok(ApiResponse.success(reportingService.getZoneViolationStatsByZoneId(zoneId)));
    }

    @GetMapping("/active-restrictions")
    @Operation(summary = "Current active restrictions", description = "Returns zones that are currently active based on time")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getActiveRestrictions() {
        return ResponseEntity.ok(ApiResponse.success(reportingService.getCurrentlyActiveRestrictions()));
    }
}
