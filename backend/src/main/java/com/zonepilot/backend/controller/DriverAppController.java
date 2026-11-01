package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateSlipRequest;
import com.zonepilot.backend.dto.request.SosRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.DutyLogResponse;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.SosAlert;
import com.zonepilot.backend.entity.TripSlip;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.service.DriverService;
import com.zonepilot.backend.service.TourService;
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
    private final TourService tourService;

    public DriverAppController(DriverService driverService, TourService tourService) {
        this.driverService = driverService;
        this.tourService = tourService;
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

    @GetMapping("/tours")
    @Operation(summary = "My assigned tours with ordered stops (PWA)")
    public ResponseEntity<ApiResponse<List<TourResponse>>> myTours() {
        return ResponseEntity.ok(ApiResponse.success(driverService.myTours()));
    }

    @PostMapping("/tours/{id}/duty")
    @Operation(summary = "Set duty state on my tour (drives vehicle availability)")
    public ResponseEntity<ApiResponse<TourResponse>> setDuty(
            @PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(
                driverService.setTourDuty(id, parseDuty(body.get("status")))));
    }

    @GetMapping("/tours/{id}/duty-log")
    @Operation(summary = "Timestamped duty history on my tour (ELD log)")
    public ResponseEntity<ApiResponse<List<DutyLogResponse>>> dutyLog(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(driverService.dutyLog(id)));
    }

    private static DutyStatus parseDuty(String status) {
        if (status == null) {
            throw new ValidationException("duty status is required");
        }
        try {
            return DutyStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("unknown duty status '" + status + "'");
        }
    }

    @PostMapping("/tours/{id}/stops/{stopId}/deliver")
    @Operation(summary = "Proof of delivery on my tour (tap + GPS stamp)")
    public ResponseEntity<ApiResponse<TourResponse>> deliver(@PathVariable Long id,
                                                             @PathVariable Long stopId,
                                                             @RequestBody java.util.Map<String, Double> body) {
        driverService.requireOwnedTour(id);
        return ResponseEntity.ok(ApiResponse.success(tourService.deliver(
                id, stopId, body.get("lat"), body.get("lng"))));
    }

    @PostMapping("/tours/{id}/stops/{stopId}/skip")
    @Operation(summary = "Skip a stop on my tour with reason")
    public ResponseEntity<ApiResponse<TourResponse>> skip(@PathVariable Long id,
                                                          @PathVariable Long stopId,
                                                          @RequestBody java.util.Map<String, String> body) {
        driverService.requireOwnedTour(id);
        return ResponseEntity.ok(ApiResponse.success(
                tourService.skip(id, stopId, body.get("reason"))));
    }
}
