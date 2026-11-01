package com.zonepilot.backend.controller;

import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.service.TraccarIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Task 3 live pipe: HTTP ingest for Driver PWA GPS burst upload and batched
 * REPLAY frames. Same quality gate as the MQTT pipe; per-group ticks fan out
 * from recordPosition to {@code /topic/group/{id}}.
 */
@RestController
@RequestMapping("/api/ingest")
@Tag(name = "Ingest", description = "Live position ingest endpoints")
@Validated
public class IngestController {

    private final TraccarIngestService ingest;

    public IngestController(TraccarIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/traccar")
    @Operation(summary = "Ingest a live/replay position fix",
            description = "source LIVE (default) or REPLAY; explicit tour keys or resolve from active tour")
    public ResponseEntity<ApiResponse<PositionRecordResponse>> ingest(
            @Valid @RequestBody IngestFrame frame) {
        PositionSource source = PositionSource.LIVE;
        if (frame.source() != null) {
            try {
                source = PositionSource.valueOf(frame.source().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ValidationException("source must be LIVE or REPLAY");
            }
            if (source != PositionSource.LIVE && source != PositionSource.REPLAY) {
                throw new ValidationException("source must be LIVE or REPLAY");
            }
        }
        return ResponseEntity.ok(ApiResponse.success(ingest.ingest(
                frame.vehicleId(), frame.lat(), frame.lng(), frame.timestamp(),
                frame.speedKmh(), frame.accuracyM(), frame.headingDeg(),
                source, frame.driverId(), frame.tourId(), frame.groupId())));
    }

    public record IngestFrame(
            @NotNull Long vehicleId,
            @NotNull @DecimalMin("12.8") @DecimalMax("13.2") Double lat,
            @NotNull @DecimalMin("77.4") @DecimalMax("77.8") Double lng,
            Instant timestamp,
            BigDecimal speedKmh,
            Double accuracyM,
            Short headingDeg,
            String source,
            Long driverId,
            Long tourId,
            Long groupId) {}
}
