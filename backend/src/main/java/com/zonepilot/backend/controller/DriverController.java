package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.CreateDriverRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.DriverResponse;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.enums.SalaryMode;
import com.zonepilot.backend.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Slice 2 dispatcher/owner roster endpoints. */
@RestController
@RequestMapping("/api/drivers")
@Tag(name = "Drivers", description = "Slice 2 driver roster endpoints")
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @PostMapping
    @Operation(summary = "Register a driver (creates DRIVER login + roster record)")
    public ResponseEntity<ApiResponse<DriverResponse>> createDriver(
            @Valid @RequestBody CreateDriverRequest request) {
        return ResponseEntity.ok(ApiResponse.success(driverService.createDriver(
                request.getName(), request.getPhone(), request.getLicenceNo(),
                request.getLicenceExpiry(), SalaryMode.valueOf(request.getSalaryMode()),
                request.getEmail(), request.getPassword())));
    }

    @GetMapping
    @Operation(summary = "List all drivers")
    public ResponseEntity<ApiResponse<List<DriverResponse>>> listDrivers() {
        return ResponseEntity.ok(ApiResponse.success(driverService.listDrivers()));
    }
}
