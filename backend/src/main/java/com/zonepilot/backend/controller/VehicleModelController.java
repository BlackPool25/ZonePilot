package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.entity.VehicleModel;
import com.zonepilot.backend.repository.VehicleModelRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicle-models")
@Tag(name = "VehicleModel", description = "Seeded commercial-vehicle spec catalog")
public class VehicleModelController {

    private final VehicleModelRepository models;

    public VehicleModelController(VehicleModelRepository models) {
        this.models = models;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<VehicleModel>>> list() {
        return ResponseEntity.ok(ApiResponse.success(models.findAll()));
    }
}
