package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.SosAlertResponse;
import com.zonepilot.backend.service.SosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Dispatcher SOS inbox (OWNER/DISPATCHER): newest first, unknown-location flagged. */
@RestController
@RequestMapping("/api/sos")
@Tag(name = "SOS", description = "Dispatcher SOS inbox endpoints")
public class SosController {

    private final SosService sos;

    public SosController(SosService sos) {
        this.sos = sos;
    }

    @GetMapping
    @Operation(summary = "List SOS alerts, newest first (dispatcher inbox)")
    public ResponseEntity<ApiResponse<List<SosAlertResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(sos.listForDispatch()));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Manager stand-down: resolve an SOS alert with a note")
    public ResponseEntity<ApiResponse<SosAlertResponse>> resolve(
            @PathVariable Long id, @RequestBody(required = false) java.util.Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return ResponseEntity.ok(ApiResponse.success(sos.resolve(id, note)));
    }
}
