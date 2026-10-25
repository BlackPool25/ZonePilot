package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.RecordLoadRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.entity.LoadEvent;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.LoadSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.service.LoadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@Tag(name = "Load", description = "Vehicle load event log (driver app + sidecar share this pipe)")
public class LoadEventController {

    private final LoadService loads;
    private final VehicleRepository vehicles;

    public LoadEventController(LoadService loads, VehicleRepository vehicles) {
        this.loads = loads;
        this.vehicles = vehicles;
    }

    @PostMapping("/api/vehicles/{id}/load-events")
    public ResponseEntity<ApiResponse<Map<String, Object>>> record(@PathVariable Long id,
                                                                   @RequestBody RecordLoadRequest req) {
        LoadSource source = req.getSource() == null
                ? LoadSource.DRIVER_APP : LoadSource.valueOf(req.getSource());
        LoadEvent e = loads.record(id, req.getTourId(), req.getStopId(), req.getDeltaKg(), source);
        Map<String, Object> body = new HashMap<>();
        body.put("eventId", e.getId());
        body.put("runningTotalKg", e.getRunningTotalKg());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/api/vehicles/{id}/load")
    public ResponseEntity<ApiResponse<Map<String, Object>>> current(@PathVariable Long id) {
        Vehicle v = vehicles.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", id));
        Double capacity = v.getModel() != null ? v.getModel().getPayloadKg() : null;
        double current = loads.currentLoad(id);
        Map<String, Object> body = new HashMap<>();
        body.put("vehicleId", id);
        body.put("currentLoadKg", current);
        body.put("capacityKg", capacity);
        body.put("utilizationPct", capacity != null && capacity > 0
                ? Math.round(current / capacity * 1000.0) / 10.0 : null);
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
