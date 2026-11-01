package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.ReplayStartRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.service.ReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/replay")
@Tag(name = "Replay", description = "Tour history replay endpoints")
public class ReplayController {

    private final ReplayService replay;

    public ReplayController(ReplayService replay) {
        this.replay = replay;
    }

    @PostMapping("/start")
    @Operation(summary = "Replay a tour's history at 1-30x as REPLAY fixes (manager-only)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> start(
            @Valid @RequestBody ReplayStartRequest request) {
        ReplayService.ReplayResult result = replay.start(request.getTourId(), request.getSpeed());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "tourId", result.tourId(),
                "speed", result.speed(),
                "points", result.points())));
    }
}
