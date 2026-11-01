package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayServiceTest {

    @Mock private TourRepository tours;
    @Mock private VehiclePositionLogRepository positions;
    @Mock private PositionTrackingService tracking;
    @Mock private GroupScope scope;
    @Mock private TickBroadcaster broadcaster;

    private ReplayService replay;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txm = mock(PlatformTransactionManager.class);
        lenient().when(txm.getTransaction(any()))
                .thenAnswer(i -> mock(TransactionStatus.class));
        replay = new ReplayService(tours, positions, tracking, scope,
                broadcaster, txm);
    }

    @Test
    void start_at30x_reinsertsHistoryAsReplayOrdered() {
        Tour tour = buildTour(9L, 11L, 21L, 31L);
        when(tours.findById(9L)).thenReturn(Optional.of(tour));
        when(positions.findByTourIdOrderByRecordedAtAsc(9L)).thenReturn(buildHistory(3));

        ReplayService.ReplayResult result = replay.start(9L, 30);

        assertEquals(9L, result.tourId());
        assertEquals(30, result.speed());
        assertEquals(3, result.points());

        ArgumentCaptor<Instant> ts = ArgumentCaptor.forClass(Instant.class);
        verify(tracking, times(3)).recordReplayFix(
                eq(11L), eq(21L), eq(9L), eq(31L),
                anyDouble(), anyDouble(), ts.capture(),
                any(), any(), eq(PositionSource.REPLAY));
        List<Instant> stamps = ts.getAllValues();
        assertTrue(stamps.get(0).isBefore(stamps.get(1)));
        assertTrue(stamps.get(1).isBefore(stamps.get(2)));
        // 30x spacing: 1000/30 = 33ms between consecutive re-inserts.
        assertEquals(33L, stamps.get(1).toEpochMilli() - stamps.get(0).toEpochMilli());
        // Throttled ticks: one summary tick, not one per fix.
        verify(broadcaster, times(1)).tick(eq(31L), eq(11L),
                anyDouble(), anyDouble(), any(), any(), eq(false));
    }

    @Test
    void start_whenNotManager_rejectedBeforeAnyInsert() {
        Tour tour = buildTour(9L, 11L, 21L, 31L);
        when(tours.findById(9L)).thenReturn(Optional.of(tour));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(any(FleetGroup.class));

        assertThrows(AccessDeniedException.class, () -> replay.start(9L, 5));
        verifyNoInteractions(tracking, broadcaster);
    }

    @Test
    void start_withSpeedOutOfRange_rejected() {
        assertThrows(ValidationException.class, () -> replay.start(9L, 0));
        assertThrows(ValidationException.class, () -> replay.start(9L, 31));
        verifyNoInteractions(tours);
    }

    @Test
    void start_withHugeHistory_capsInserts() {
        Tour tour = buildTour(9L, 11L, 21L, 31L);
        when(tours.findById(9L)).thenReturn(Optional.of(tour));
        when(positions.findByTourIdOrderByRecordedAtAsc(9L))
                .thenReturn(buildHistory(ReplayService.MAX_POINTS + 500));

        ReplayService.ReplayResult result = replay.start(9L, 5);

        assertEquals(ReplayService.MAX_POINTS, result.points());
        verify(tracking, times(ReplayService.MAX_POINTS)).recordReplayFix(
                any(), any(), any(), any(), anyDouble(), anyDouble(),
                any(), any(), any(), eq(PositionSource.REPLAY));
    }

    @Test
    void start_withHugeHistory_keepsLatestTail() {
        Tour tour = buildTour(9L, 11L, 21L, 31L);
        when(tours.findById(9L)).thenReturn(Optional.of(tour));
        when(positions.findByTourIdOrderByRecordedAtAsc(9L))
                .thenReturn(buildHistory(ReplayService.MAX_POINTS + 500));

        replay.start(9L, 5);

        // Tail window: first re-insert is history[500], not history[0].
        ArgumentCaptor<Double> lat = ArgumentCaptor.forClass(Double.class);
        verify(tracking, times(ReplayService.MAX_POINTS)).recordReplayFix(
                any(), any(), any(), any(), lat.capture(), anyDouble(),
                any(), any(), any(), eq(PositionSource.REPLAY));
        assertEquals(12.9 + 500 * 0.001, lat.getAllValues().get(0), 1e-9);
    }

    @Test
    void start_withEmptyHistory_400() {
        Tour tour = buildTour(9L, 11L, 21L, 31L);
        when(tours.findById(9L)).thenReturn(Optional.of(tour));
        when(positions.findByTourIdOrderByRecordedAtAsc(9L)).thenReturn(List.of());

        assertThrows(ValidationException.class, () -> replay.start(9L, 5));
        verifyNoInteractions(tracking, broadcaster);
    }

    @Test
    void start_withMissingTour_404() {
        when(tours.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> replay.start(9L, 5));
        verifyNoInteractions(positions, tracking, broadcaster);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Tour buildTour(Long tourId, Long vehicleId, Long driverId, Long groupId) {
        FleetGroup group = new FleetGroup();
        group.setId(groupId);
        group.setName("g");
        Vehicle vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setRegistrationNumber("KA01-0001");
        Driver driver = new Driver();
        driver.setId(driverId);
        driver.setName("d");
        Tour tour = new Tour();
        tour.setId(tourId);
        tour.setGroup(group);
        tour.setVehicle(vehicle);
        tour.setDriver(driver);
        return tour;
    }

    private List<VehiclePositionLog> buildHistory(int n) {
        List<VehiclePositionLog> out = new ArrayList<>(n);
        Instant base = Instant.parse("2026-10-01T10:00:00Z");
        for (int i = 0; i < n; i++) {
            VehiclePositionLog log = new VehiclePositionLog();
            log.setVehicleId(11L);
            log.setDriverId(21L);
            log.setTourId(9L);
            log.setGroupId(31L);
            log.setPosition(gf.createPoint(new Coordinate(77.6 + i * 0.001, 12.9 + i * 0.001)));
            log.setRecordedAt(base.plusSeconds(i * 10L));
            log.setSpeedKmh(BigDecimal.valueOf(30));
            log.setHeadingDeg((short) 90);
            log.setSource(PositionSource.LIVE);
            out.add(log);
        }
        return out;
    }
}
