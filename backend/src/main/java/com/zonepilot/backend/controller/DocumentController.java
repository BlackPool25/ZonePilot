package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.request.SaveDocumentRequest;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.DocumentResponse;
import com.zonepilot.backend.enums.DocType;
import com.zonepilot.backend.service.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Slice 2 vehicle-document wall endpoints. */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "Slice 2 vehicle-document wall endpoints")
public class DocumentController {

    private final DriverService driverService;

    public DocumentController(DriverService driverService) {
        this.driverService = driverService;
    }

    @PostMapping
    @Operation(summary = "Save (or renew) a vehicle document")
    public ResponseEntity<ApiResponse<DocumentResponse>> saveDocument(
            @Valid @RequestBody SaveDocumentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(driverService.saveDocument(
                request.getVehicleId(), DocType.valueOf(request.getType()),
                request.getDocNo(), request.getExpiryDate())));
    }

    @GetMapping("/expiring")
    @Operation(summary = "Documents expiring within N days, soonest first")
    public ResponseEntity<ApiResponse<List<DocumentResponse>>> expiring(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.success(driverService.expiringDocs(days)));
    }
}
