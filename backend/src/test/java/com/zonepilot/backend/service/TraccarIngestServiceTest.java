package com.zonepilot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.repository.PositionQuarantineRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Live pipe: every ingest branch — bad topics ignored, bad payloads quarantined. */
@ExtendWith(MockitoExtension.class)
class TraccarIngestServiceTest {

    @Mock VehicleRepository vehicles;
    @Mock PositionTrackingService positions;
    @Mock QualityFilter quality;
    @Mock IngestState state;
    @Mock PositionQuarantineRepository quarantine;
    @Spy ObjectMapper mapper = new ObjectMapper();

    @InjectMocks TraccarIngestService ingest;

    private static final String TOPIC = "zonepilot/fleet/vehicles/7/position";

    private Vehicle vehicleWithDepot() {
        Vehicle v = new Vehicle();
        v.setId(7L);
        v.setIsActive(true);
        return v;
    }

    private PositionRecordResponse okResponse(Instant ts) {
        var detail = new PositionRecordResponse.PositionDetail();
        detail.setLatitude(12.9352);
        detail.setLongitude(77.6245);
        detail.setRecordedAt(ts);
        detail.setSpeedKmh(new BigDecimal("11.1"));
        var response = new PositionRecordResponse();
        response.setBreachDetected(false);
        response.setPosition(detail);
        return response;
    }

    @Test
    void malformedTopic_isIgnored() {
        ingest.handle("zonepilot/fleet/vehicles", "{}");
        ingest.handle("other/9/vehicles/7/position", "{}");
        ingest.handle("zonepilot/fleet/vehicles/abc/position", "{}");
        ingest.handle("zonepilot/fleet/vehicles/7/ping", "{}");
        verifyNoInteractions(vehicles, positions, quarantine);
    }

    @Test
    void statusEvent_isLoggedNotStored() {
        ingest.handle("zonepilot/fleet/vehicles/7/status", "{\"ignition\":true}");
        verifyNoInteractions(vehicles, positions, quarantine);
    }

    @Test
    void malformedJson_quarantined() {
        ingest.handle(TOPIC, "{not json");
        verify(quarantine).save(argThat(r ->
                r.getVehicleId() == 7L && "MALFORMED_JSON".equals(r.getReason())));
        verifyNoInteractions(positions);
    }

    @Test
    void unknownVehicle_quarantined() {
        when(vehicles.findById(7L)).thenReturn(Optional.empty());
        ingest.handle(TOPIC, "{\"lat\":12.9,\"lon\":77.6}");
        verify(quarantine).save(argThat(r -> "UNKNOWN_VEHICLE".equals(r.getReason())));
        verifyNoInteractions(positions);
    }

    @Test
    void missingCoords_quarantined() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        ingest.handle(TOPIC, "{\"vehicleId\":7,\"ts\":1700000000000}");
        verify(quarantine).save(argThat(r -> "MISSING_COORDS".equals(r.getReason())));
    }

    @Test
    void topicMismatch_quarantined() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        ingest.handle(TOPIC, "{\"vehicleId\":8,\"lat\":12.9,\"lon\":77.6}");
        verify(quarantine).save(argThat(r -> "TOPIC_MISMATCH".equals(r.getReason())));
    }

    @Test
    void badSource_quarantined() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        ingest.handle(TOPIC, "{\"vehicleId\":7,\"lat\":12.9,\"lon\":77.6,\"source\":\"NOPE\"}");
        verify(quarantine).save(argThat(r -> "BAD_SOURCE".equals(r.getReason())));
        verifyNoInteractions(positions);
    }

    @Test
    void qualityReject_quarantinedWithReason() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        when(quality.check(eq(7L), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new QualityFilter.Result(false, "TELEPORT"));
        ingest.handle(TOPIC, "{\"vehicleId\":7,\"lat\":12.99,\"lon\":77.68,\"ts\":1700000000000}");
        verify(quarantine).save(argThat(r -> "TELEPORT".equals(r.getReason())));
        verifyNoInteractions(positions);
    }

    @Test
    void happyPath_recordsFixAndSavesState() {
        Instant ts = Instant.ofEpochMilli(1700000000000L);
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        when(quality.check(eq(7L), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new QualityFilter.Result(true, null));
        when(positions.recordPosition(eq(7L), isNull(), isNull(), isNull(),
                eq(12.9352), eq(77.6245), eq(ts),
                any(), any(), eq(PositionSource.TRACCAR))).thenReturn(okResponse(ts));
        ingest.handle(TOPIC,
                "{\"vehicleId\":7,\"lat\":12.9352,\"lon\":77.6245,\"ts\":1700000000000,\"speedKmh\":11.1}");
        verify(state).saveFix(7L, 12.9352, 77.6245, ts);
        verify(quarantine, never()).save(any());
    }

    @Test
    void quarantineCounter_countsByReason() {
        long before = TraccarIngestService.quarantineCount("MALFORMED_JSON");
        long totalBefore = TraccarIngestService.quarantineCount();
        ingest.handle(TOPIC, "{not json");
        assertThat(TraccarIngestService.quarantineCount("MALFORMED_JSON")).isEqualTo(before + 1);
        assertThat(TraccarIngestService.quarantineCount()).isEqualTo(totalBefore + 1);
    }

    @Test
    void replaySource_accepted() {
        Instant ts = Instant.now();
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        when(quality.check(eq(7L), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new QualityFilter.Result(true, null));
        ArgumentCaptor<PositionSource> source = ArgumentCaptor.forClass(PositionSource.class);
        when(positions.recordPosition(eq(7L), any(), any(), any(), anyDouble(), anyDouble(),
                any(), any(), any(), source.capture())).thenReturn(okResponse(ts));
        ingest.handle(TOPIC,
                "{\"vehicleId\":7,\"lat\":12.9,\"lon\":77.6,\"source\":\"REPLAY\"}");
        assertThat(source.getValue()).isEqualTo(PositionSource.REPLAY);
    }

    @Test
    void isoTimestampString_parsed() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        when(quality.check(eq(7L), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new QualityFilter.Result(true, null));
        ArgumentCaptor<Instant> ts = ArgumentCaptor.forClass(Instant.class);
        when(positions.recordPosition(eq(7L), any(), any(), any(), anyDouble(), anyDouble(),
                ts.capture(), any(), any(), any())).thenReturn(okResponse(Instant.now()));
        ingest.handle(TOPIC, "{\"vehicleId\":7,\"lat\":12.9,\"lon\":77.6,\"ts\":\"2026-10-24T10:00:00Z\"}");
        assertThat(ts.getValue()).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
    }

    @Test
    void uniqueViolation_swallowedAsRedelivery() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        when(quality.check(eq(7L), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new QualityFilter.Result(true, null));
        when(positions.recordPosition(anyLong(), any(), any(), any(), anyDouble(), anyDouble(),
                any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("dup"));
        ingest.handle(TOPIC, "{\"vehicleId\":7,\"lat\":12.9,\"lon\":77.6,\"ts\":1700000000000}");
        verify(quarantine, never()).save(any());
    }

    @Test
    void unexpectedError_quarantinedAsIngestError() {
        when(vehicles.findById(7L)).thenReturn(Optional.of(vehicleWithDepot()));
        when(quality.check(eq(7L), anyDouble(), anyDouble(), any(), any(), any()))
                .thenReturn(new QualityFilter.Result(true, null));
        when(positions.recordPosition(anyLong(), any(), any(), any(), anyDouble(), anyDouble(),
                any(), any(), any(), any()))
                .thenThrow(new RuntimeException("pgrouting down"));
        ingest.handle(TOPIC, "{\"vehicleId\":7,\"lat\":12.9,\"lon\":77.6,\"ts\":1700000000000}");
        verify(quarantine).save(argThat(r -> "INGEST_ERROR".equals(r.getReason())));
    }
}
