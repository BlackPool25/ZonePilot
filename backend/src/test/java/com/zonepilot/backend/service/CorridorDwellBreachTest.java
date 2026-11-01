package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneRestrictionRule;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.RuleDirection;
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
 * RED: corridor (ST_DWithin + 60s-outside) + dwell/entry-exit engine.
 * Corridor breach only after &gt;60s continuously outside the corridor;
 * dwell breach only after inside continuously &gt;= threshold;
 * EXIT-only rules never fire on entry.
 */
@ExtendWith(MockitoExtension.class)
class CorridorDwellBreachTest {

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
        zone.setName("Corridor Zone");

        rule = new ZoneRestrictionRule();
        rule.setId(9L);

        lenient().when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        lenient().when(zoneRepository.findById(1L)).thenReturn(Optional.of(zone));
        lenient().when(ruleRepository.findById(9L)).thenReturn(Optional.of(rule));
        lenient().when(breachLogRepository.save(any(ZoneBreachLog.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(breachLogRepository.findOpenBreachForUpdate(eq(5L), eq(1L), any(Instant.class)))
                .thenReturn(Optional.empty());
    }

    private static List<Object[]> polygonRows() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, 9L, "NO_ENTRY"});
        return rows;
    }

    private static List<Object[]> corridorRows(boolean inside) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{1L, 9L, "NO_ENTRY", inside});
        return rows;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPolygon(List<Object[]> first, List<Object[]>... rest) {
        lenient().when(jdbcTemplate.query(argThat((String sql) -> sql.contains("ST_Covers")),
                any(RowMapper.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString(), anyInt(),
                any(java.sql.Time.class), any(java.sql.Time.class), any(java.sql.Time.class)))
                .thenReturn((List) first, (List[]) rest);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubCorridor(List<Object[]> first, List<Object[]>... rest) {
        lenient().when(jdbcTemplate.query(argThat((String sql) -> sql.contains("ST_DWithin") && sql.contains("CORRIDOR")),
                any(RowMapper.class),
                anyDouble(), anyDouble(), anyLong(), anyString(), anyInt(),
                any(java.sql.Time.class), any(java.sql.Time.class), any(java.sql.Time.class)))
                .thenReturn((List) first, (List[]) rest);
    }

    private BreachWorker.PositionFix fix(Instant at) {
        return new BreachWorker.PositionFix(5L, 100L, 12.9352, 77.6245, at, PositionSource.LIVE);
    }

    @Test
    void corridorInside_noBreach() {
        vehicle.setActiveDispatchRouteId(500L);
        stubPolygon(Collections.emptyList());
        stubCorridor(corridorRows(true));

        worker.onEvent(fix(Instant.parse("2026-10-20T08:00:00Z")));

        verify(breachLogRepository, never()).save(any());
        verify(breachService, never()).computeReroute(any(), anyLong(), anyDouble(), anyDouble());
    }

    @Test
    void corridorOutside70s_oneBreach() {
        vehicle.setActiveDispatchRouteId(500L);
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubPolygon(Collections.emptyList());
        stubCorridor(corridorRows(false), corridorRows(false), corridorRows(false));

        worker.onEvent(fix(t0));
        worker.onEvent(fix(t0.plusSeconds(30)));
        worker.onEvent(fix(t0.plusSeconds(70)));

        ArgumentCaptor<ZoneBreachLog> captor = ArgumentCaptor.forClass(ZoneBreachLog.class);
        verify(breachLogRepository, times(1)).save(captor.capture());
        assertEquals(t0.plusSeconds(70), captor.getValue().getBreachTime());
    }

    @Test
    void corridorOutside30sThenInside_none() {
        vehicle.setActiveDispatchRouteId(500L);
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubPolygon(Collections.emptyList());
        stubCorridor(corridorRows(false), corridorRows(false), corridorRows(true));

        worker.onEvent(fix(t0));
        worker.onEvent(fix(t0.plusSeconds(30)));
        worker.onEvent(fix(t0.plusSeconds(50)));

        verify(breachLogRepository, never()).save(any());
    }

    @Test
    void dwellDrivePast20s_noBreach() {
        rule.setDwellThresholdMin(5);
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubPolygon(polygonRows(), polygonRows());

        worker.onEvent(fix(t0));
        worker.onEvent(fix(t0.plusSeconds(20)));

        verify(breachLogRepository, never()).save(any());
    }

    @Test
    void dwellFiveMinuteStop_oneBreach() {
        rule.setDwellThresholdMin(5);
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubPolygon(polygonRows(), polygonRows(), polygonRows(),
                polygonRows(), polygonRows(), polygonRows());

        for (int m = 0; m <= 5; m++) {
            worker.onEvent(fix(t0.plusSeconds(m * 60L)));
        }

        ArgumentCaptor<ZoneBreachLog> captor = ArgumentCaptor.forClass(ZoneBreachLog.class);
        verify(breachLogRepository, times(1)).save(captor.capture());
        assertEquals(t0.plusSeconds(300), captor.getValue().getBreachTime());
    }

    @Test
    void exitOnlyRule_entryNoBreachThenExitBreach() {
        rule.setDirection(RuleDirection.EXIT);
        Instant t0 = Instant.parse("2026-10-20T08:00:00Z");
        stubPolygon(polygonRows(), Collections.emptyList());

        worker.onEvent(fix(t0));
        verify(breachLogRepository, never()).save(any());

        worker.onEvent(fix(t0.plusSeconds(30)));
        ArgumentCaptor<ZoneBreachLog> captor = ArgumentCaptor.forClass(ZoneBreachLog.class);
        verify(breachLogRepository, times(1)).save(captor.capture());
        assertEquals(t0.plusSeconds(30), captor.getValue().getBreachTime());
    }
}
