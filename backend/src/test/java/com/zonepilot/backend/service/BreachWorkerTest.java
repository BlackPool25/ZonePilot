package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneRestrictionRule;
import com.zonepilot.backend.enums.BreachType;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 5: background breach worker. Owns zone_breach_log one-per-entry
 * (60s extend window) plus best-effort zone-avoiding reroute, in its OWN
 * transaction — an in-zone fix in an env without road tables must never
 * poison the ingest txn (Task 3 Major: HTTP 500).
 */
@ExtendWith(MockitoExtension.class)
class BreachWorkerTest {

    @Mock private ZoneBreachLogRepository breachLogRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private ZoneRestrictionRepository zoneRepository;
    @Mock private ZoneRestrictionRuleRepository ruleRepository;
    @Mock private BreachService breachService;
    @Mock private JdbcTemplate jdbcTemplate;

    private final FakeIngestState state = new FakeIngestState();
    private BreachWorker worker;

    private Vehicle vehicle;
    private ZoneRestriction zone;
    private ZoneRestrictionRule rule;

    @BeforeEach
    void setUp() {
        worker = new BreachWorker(breachLogRepository, vehicleRepository,
                zoneRepository, ruleRepository, breachService, jdbcTemplate, state);

        vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setRegistrationNumber("KA05-T5-0005");
        vehicle.setVehicleClass(VehicleClass.LCV);
        vehicle.setOwnerName("Test Owner");
        vehicle.setIsActive(true);

        zone = new ZoneRestriction();
        zone.setId(1L);
        zone.setName("Majestic Zone");

        rule = new ZoneRestrictionRule();
        rule.setId(9L);

        lenient().when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        lenient().when(zoneRepository.findById(1L)).thenReturn(Optional.of(zone));
        lenient().when(ruleRepository.findById(9L)).thenReturn(Optional.of(rule));
    }

    /** Zone-match row: [zoneId, ruleId, restrictionType]. */
    private static List<Object[]> zoneRow(Long zoneId, Long ruleId, String restrictionType) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{zoneId, ruleId, restrictionType});
        return rows;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubZoneMatch(List<Object[]> rows) {
        when(jdbcTemplate.query(argThat((String sql) -> sql.contains("ST_Covers")),
                any(RowMapper.class),
                anyDouble(), anyDouble(), anyString(), anyInt(),
                any(java.sql.Time.class), any(java.sql.Time.class), any(java.sql.Time.class)))
                .thenReturn((List) rows);
    }

    private void stubTickDistance(double meters) {
        when(jdbcTemplate.queryForObject(
                argThat((String sql) -> sql.contains("vehicle_position_log")),
                eq(Double.class), anyDouble(), anyDouble(), anyLong(), any(Instant.class)))
                .thenReturn(meters);
    }

    private void stubOffRouteDistance(Double meters) {
        when(jdbcTemplate.queryForObject(
                argThat((String sql) -> sql.contains("planned_route_geometry")),
                eq(Double.class), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(meters);
    }

    private BreachWorker.PositionFix fix(Instant at, PositionSource source) {
        return new BreachWorker.PositionFix(5L, 100L, 12.9352, 77.6245, at, source);
    }

    @Test
    void insideZoneTenMinutes_extendsSingleRow() {
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubZoneMatch(zoneRow(1L, 9L, "NO_ENTRY"));
        stubTickDistance(15.0);
        // No active route: off-route evaluation skipped.
        assertNull(vehicle.getActiveDispatchRouteId());

        ZoneBreachLog open = new ZoneBreachLog();
        open.setId(77L);
        open.setVehicle(vehicle);
        open.setZone(zone);
        open.setBreachType(BreachType.NO_ENTRY);
        open.setBreachTime(t0);
        open.setEndTime(t0);
        when(breachLogRepository.save(any(ZoneBreachLog.class))).thenAnswer(inv -> {
            ZoneBreachLog b = inv.getArgument(0);
            if (b.getId() == null) b.setId(77L);
            return b;
        });
        when(breachLogRepository.findOpenBreach(eq(5L), eq(1L), any(Instant.class)))
                .thenReturn(Optional.empty(), Optional.of(open), Optional.of(open));

        worker.onEvent(fix(t0, PositionSource.LIVE));
        worker.onEvent(fix(t0.plusSeconds(30), PositionSource.LIVE));
        worker.onEvent(fix(t0.plusSeconds(60), PositionSource.LIVE));

        ArgumentCaptor<ZoneBreachLog> captor = ArgumentCaptor.forClass(ZoneBreachLog.class);
        verify(breachLogRepository, times(3)).save(captor.capture());
        List<ZoneBreachLog> saved = captor.getAllValues();
        // First save opens the entry; later saves extend the SAME row.
        assertEquals(t0, saved.get(0).getBreachTime());
        assertEquals(t0, saved.get(0).getEndTime());
        assertEquals(t0.plusSeconds(60), saved.get(2).getEndTime());
        assertEquals(77L, saved.get(2).getId());
        // Reroute attempted best-effort for the new entry.
        verify(breachService, times(1)).computeReroute(any(), eq(5L), eq(12.9352), eq(77.6245));
    }

    @Test
    void reentryAfterGap_createsSecondRow() {
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubZoneMatch(zoneRow(1L, 9L, "NO_ENTRY"));

        when(breachLogRepository.save(any(ZoneBreachLog.class))).thenAnswer(inv -> inv.getArgument(0));
        // Gap > 60s: no open breach on re-entry.
        when(breachLogRepository.findOpenBreach(eq(5L), eq(1L), any(Instant.class)))
                .thenReturn(Optional.empty());

        worker.onEvent(fix(t0, PositionSource.LIVE));
        worker.onEvent(fix(t0.plusSeconds(600), PositionSource.LIVE));

        ArgumentCaptor<ZoneBreachLog> captor = ArgumentCaptor.forClass(ZoneBreachLog.class);
        verify(breachLogRepository, times(2)).save(captor.capture());
        assertEquals(t0, captor.getAllValues().get(0).getBreachTime());
        assertEquals(t0.plusSeconds(600), captor.getAllValues().get(1).getBreachTime());
    }

    @Test
    void roadTablesMissing_breachStillRecorded_workerNeverThrows() {
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubZoneMatch(zoneRow(1L, 9L, "NO_ENTRY"));
        // Reroute blows up as it did inline (poisoned txn -> HTTP 500).
        doThrow(new RuntimeException("current transaction is aborted"))
                .when(breachService).computeReroute(any(), eq(5L), anyDouble(), anyDouble());

        when(breachLogRepository.save(any(ZoneBreachLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(breachLogRepository.findOpenBreach(eq(5L), eq(1L), any(Instant.class)))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> worker.onEvent(fix(t0, PositionSource.LIVE)));
        verify(breachLogRepository, times(1)).save(any(ZoneBreachLog.class));
    }

    @Test
    void replaySource_retriggersEvaluation() {
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubZoneMatch(zoneRow(1L, 9L, "TIME_RESTRICTED"));

        when(breachLogRepository.save(any(ZoneBreachLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(breachLogRepository.findOpenBreach(eq(5L), eq(1L), any(Instant.class)))
                .thenReturn(Optional.empty());

        worker.onEvent(fix(t0, PositionSource.REPLAY));

        ArgumentCaptor<ZoneBreachLog> captor = ArgumentCaptor.forClass(ZoneBreachLog.class);
        verify(breachLogRepository, times(1)).save(captor.capture());
        assertEquals(BreachType.TIME_WINDOW, captor.getValue().getBreachType());
    }

    @Test
    void offRouteSecondConsecutivePing_triggersReroute() {
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubZoneMatch(Collections.emptyList());
        vehicle.setActiveDispatchRouteId(500L);
        stubOffRouteDistance(60.0);

        worker.onEvent(fix(t0, PositionSource.LIVE));
        verify(breachService, never()).computeOffRouteReroute(anyLong(), anyDouble(), anyDouble());

        worker.onEvent(fix(t0.plusSeconds(10), PositionSource.LIVE));
        verify(breachService, times(1)).computeOffRouteReroute(eq(5L), eq(12.9352), eq(77.6245));
        verify(breachLogRepository, never()).save(any());
    }

    @Test
    void noZoneNoOffRoute_nothingHappens() {
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubZoneMatch(Collections.emptyList());
        vehicle.setActiveDispatchRouteId(500L);
        stubOffRouteDistance(10.0);

        worker.onEvent(fix(t0, PositionSource.LIVE));

        verify(breachLogRepository, never()).save(any());
        verify(breachService, never()).computeReroute(any(), anyLong(), anyDouble(), anyDouble());
        verify(breachService, never()).computeOffRouteReroute(anyLong(), anyDouble(), anyDouble());
    }
}
