package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateTourRequest;
import com.zonepilot.backend.dto.request.DeliverStopRequest;
import com.zonepilot.backend.dto.request.SkipStopRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.dto.response.TourTrackResponse;
import com.zonepilot.backend.service.TourService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tours")
public class TourController {

    private final TourService tours;

    public TourController(TourService tours) {
        this.tours = tours;
    }

    @PostMapping
    @Operation(summary = "Create a tour: optimize stops, validate leg 1, assign driver")
    public ResponseEntity<ApiResponse<TourResponse>> create(@Valid @RequestBody CreateTourRequest req) {
        return ResponseEntity.ok(ApiResponse.success(tours.create(req)));
    }

    @GetMapping("/{tourId}")
    @Operation(summary = "Get tour with ordered stops")
    public ResponseEntity<ApiResponse<TourResponse>> get(@PathVariable Long tourId) {
        return ResponseEntity.ok(ApiResponse.success(tours.get(tourId)));
    }

    @PostMapping("/{tourId}/stops/{stopId}/deliver")
    @Operation(summary = "Proof of delivery: close current stop, open the next leg")
    public ResponseEntity<ApiResponse<TourResponse>> deliver(@PathVariable Long tourId,
                                                             @PathVariable Long stopId,
                                                             @Valid @RequestBody DeliverStopRequest req) {
        return ResponseEntity.ok(
                ApiResponse.success(tours.deliver(tourId, stopId, req.getLat(), req.getLng())));
    }

    @PostMapping("/{tourId}/stops/{stopId}/skip")
    @Operation(summary = "Skip current stop with reason, open the next leg")
    public ResponseEntity<ApiResponse<TourResponse>> skip(@PathVariable Long tourId,
                                                          @PathVariable Long stopId,
                                                          @Valid @RequestBody SkipStopRequest req) {
        return ResponseEntity.ok(
                ApiResponse.success(tours.skip(tourId, stopId, req.getReason())));
    }

    @GetMapping("/{tourId}/track")
    @Operation(summary = "Live tracing: stops with ETAs, per-leg route geometries, latest position")
    public ResponseEntity<ApiResponse<TourTrackResponse>> track(@PathVariable Long tourId) {
        return ResponseEntity.ok(ApiResponse.success(tours.track(tourId)));
    }
}
