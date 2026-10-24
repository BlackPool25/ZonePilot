package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateSlipRequest;
import com.zonepilot.backend.dto.request.SosRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.SosAlert;
import com.zonepilot.backend.entity.TripSlip;
import com.zonepilot.backend.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Slice 2 driver-app endpoints: trip card, Accept/Start, slips, SOS. DRIVER role only. */
@RestController
@RequestMapping("/api/driver")
@Tag(name = "DriverApp", description = "Slice 2 driver-app endpoints")
public class DriverAppController {

    private final DriverService driverService;

    public DriverAppController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping("/routes")
    @Operation(summary = "My current assignments (trip card)")
    public ResponseEntity<ApiResponse<List<DispatchRoute>>> myRoutes() {
        return ResponseEntity.ok(ApiResponse.success(driverService.myRoutes()));
    }

    @PostMapping("/routes/{id}/accept")
    @Operation(summary = "Accept an assigned trip")
    public ResponseEntity<ApiResponse<DispatchRoute>> accept(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(driverService.accept(id)));
    }

    @PostMapping("/routes/{id}/start")
    @Operation(summary = "Start an accepted trip (duty gate opens)")
    public ResponseEntity<ApiResponse<DispatchRoute>> start(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(driverService.start(id)));
    }

    @PostMapping("/routes/{id}/slips")
    @Operation(summary = "Record a fuel/toll slip against the active trip")
    public ResponseEntity<ApiResponse<TripSlip>> slip(
            @PathVariable Long id, @Valid @RequestBody CreateSlipRequest request) {
        return ResponseEntity.ok(ApiResponse.success(driverService.slip(id, request)));
    }

    @PostMapping("/sos")
    @Operation(summary = "Raise an SOS distress signal")
    public ResponseEntity<ApiResponse<SosAlert>> sos(@RequestBody SosRequest request) {
        return ResponseEntity.ok(ApiResponse.success(driverService.sos(request)));
    }
}
