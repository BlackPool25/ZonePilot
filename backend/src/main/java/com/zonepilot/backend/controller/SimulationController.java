package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.StartSimulationRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.SimulationStateResponse;
import com.zonepilot.backend.dto.response.SimulationTickResponse;
import com.zonepilot.backend.service.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/simulation")
@Tag(name = "Simulation", description = "Simulation control endpoints")
public class SimulationController {

    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/start")
    @Operation(summary = "Start simulation scenarios", description = "Activates selected simulation paths")
    public ResponseEntity<ApiResponse<String>> startSimulation(
            @Valid @RequestBody StartSimulationRequest request) {
        simulationService.startScenarios(request.getScenarios());
        return ResponseEntity.ok(ApiResponse.success("Simulation started for scenarios: " + request.getScenarios()));
    }

    @PostMapping("/tick")
    @Operation(summary = "Advance simulation one tick", description = "Moves all active vehicles one step and returns positions + breaches")
    public ResponseEntity<ApiResponse<SimulationTickResponse>> tick() {
        return ResponseEntity.ok(ApiResponse.success(simulationService.tick()));
    }

    @GetMapping("/state")
    @Operation(summary = "Get simulation state", description = "Returns current state of all simulation paths")
    public ResponseEntity<ApiResponse<List<SimulationStateResponse>>> getState() {
        return ResponseEntity.ok(ApiResponse.success(simulationService.getState()));
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset simulation", description = "Resets all simulation paths to step 0 and deactivates them")
    public ResponseEntity<ApiResponse<String>> reset() {
        simulationService.reset();
        return ResponseEntity.ok(ApiResponse.success("Simulation reset"));
    }
}
