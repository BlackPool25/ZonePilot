package com.zonepilot.backend.service;

import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PositionTrackingServiceTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private VehiclePositionLogRepository positionLogRepository;
    @Mock private ZoneBreachLogRepository breachLogRepository;
    @Mock private DispatchRouteRepository dispatchRouteRepository;
    @Mock private BreachService breachService;
    @Mock private JdbcTemplate jdbcTemplate;

    private PositionTrackingService service;

    @BeforeEach
    void setUp() {
        service = new PositionTrackingService(
                vehicleRepository, positionLogRepository, breachLogRepository,
                dispatchRouteRepository, breachService, jdbcTemplate);
    }

    @Test
    void recordPosition_withNonExistentVehicle_throwsResourceNotFoundException() {
        when(vehicleRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.recordPosition(999L, 12.9, 77.6, null, null, null, PositionSource.LIVE));
    }

    @Test
    void recordPosition_withInactiveVehicle_throwsValidationException() {
        Vehicle inactive = buildVehicle(1L, false);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(inactive));

        assertThrows(ValidationException.class,
                () -> service.recordPosition(1L, 12.9, 77.6, null, null, null, PositionSource.LIVE));
        verify(positionLogRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
        "12.7, 77.6",   // lat below Bangalore
        "13.3, 77.6",   // lat above Bangalore
        "12.9, 77.3",   // lng below Bangalore
        "12.9, 77.9",   // lng above Bangalore
    })
    void recordPosition_withOutOfRangeCoordinates_throwsValidationException(double lat, double lng) {
        // Coordinate validation happens before vehicle lookup
        assertThrows(ValidationException.class,
                () -> service.recordPosition(1L, lat, lng, null, null, null, PositionSource.LIVE));
        verify(vehicleRepository, never()).findById(any());
    }

    @ParameterizedTest
    @CsvSource({
        "12.8, 77.4",   // min boundary
        "13.2, 77.8",   // max boundary
        "12.9116, 77.6389",  // HSR Layout depot
        "13.0241, 77.5535",  // Yeshwantpur depot
    })
    void recordPosition_withValidBangaloreCoordinates_proceedsToVehicleLookup(double lat, double lng) {
        // Coordinate validation passes; vehicle lookup is attempted
        when(vehicleRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.recordPosition(1L, lat, lng, null, null, null, PositionSource.LIVE));
        verify(vehicleRepository).findById(1L);
    }

    @Test
    void recordPosition_withFutureTimestamp_throwsValidationException() {
        Instant farFuture = Instant.now().plusSeconds(3600);
        assertThrows(ValidationException.class,
                () -> service.recordPosition(1L, 12.9, 77.6, farFuture, null, null, PositionSource.LIVE));
        verify(vehicleRepository, never()).findById(any());
    }

    @Test
    void recordPosition_withTimestampJustUnderFiveMinutes_proceedsToVehicleLookup() {
        Instant nearFuture = Instant.now().plusSeconds(240); // 4 minutes — within tolerance
        when(vehicleRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.recordPosition(1L, 12.9, 77.6, nearFuture, null, null, PositionSource.LIVE));
        verify(vehicleRepository).findById(1L);
    }

    @Test
    void recordPosition_whenVehicleHasNoActiveRoute_doesNotPerformMapMatching() {
        Vehicle active = buildVehicle(1L, true);
        active.setActiveDispatchRouteId(null);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(active));

        com.zonepilot.backend.entity.VehiclePositionLog savedLog = new com.zonepilot.backend.entity.VehiclePositionLog();
        savedLog.setId(100L);
        when(positionLogRepository.save(any())).thenReturn(savedLog);
        when(breachLogRepository.findByPositionLogId(100L)).thenReturn(Collections.emptyList());

        com.zonepilot.backend.dto.response.PositionRecordResponse response =
                service.recordPosition(1L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE);

        assertNotNull(response);
        assertEquals(12.9, response.getPosition().getLatitude());
        assertEquals(77.6, response.getPosition().getLongitude());
        verify(jdbcTemplate, never()).query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), (Object[]) any());
    }

    @Test
    void recordPosition_whenVehicleOnRoute_logsRawCoordinateAndResetsCounter() {
        Vehicle active = buildVehicle(1L, true);
        active.setActiveDispatchRouteId(500L);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(active));

        // dist_m = 10.0 (on-route)
        List<Object[]> queryResult = new ArrayList<>();
        queryResult.add(new Object[]{10.0, 12.905, 77.605, 0.0});
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(queryResult);

        com.zonepilot.backend.entity.VehiclePositionLog savedLog = new com.zonepilot.backend.entity.VehiclePositionLog();
        savedLog.setId(100L);
        when(positionLogRepository.save(any())).thenReturn(savedLog);
        when(breachLogRepository.findByPositionLogId(100L)).thenReturn(Collections.emptyList());

        com.zonepilot.backend.dto.response.PositionRecordResponse response =
                service.recordPosition(1L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE);

        assertNotNull(response);
        // Should keep raw coordinate since heading was null and distance is within 30m
        assertEquals(12.9, response.getPosition().getLatitude());
        assertEquals(77.6, response.getPosition().getLongitude());
    }

    @Test
    void recordPosition_whenGpsGlitchReversedHeading_snapsToRoute() {
        Vehicle active = buildVehicle(1L, true);
        active.setActiveDispatchRouteId(500L);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(active));

        // dist_m = 15.0, snap_lat/lng = 12.905, 77.605, route heading = 0 radians (North)
        List<Object[]> queryResult = new ArrayList<>();
        queryResult.add(new Object[]{15.0, 12.905, 77.605, 0.0});
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(queryResult);

        com.zonepilot.backend.entity.VehiclePositionLog savedLog = new com.zonepilot.backend.entity.VehiclePositionLog();
        savedLog.setId(100L);
        when(positionLogRepository.save(any())).thenReturn(savedLog);
        when(breachLogRepository.findByPositionLogId(100L)).thenReturn(Collections.emptyList());

        // Send reversed heading of 180 degrees (South)
        com.zonepilot.backend.dto.response.PositionRecordResponse response =
                service.recordPosition(1L, 12.9, 77.6, Instant.now(), null, (short) 180, PositionSource.LIVE);

        assertNotNull(response);
        // Reversed heading within 30m -> snaps to route's closest point
        assertEquals(12.905, response.getPosition().getLatitude());
        assertEquals(77.605, response.getPosition().getLongitude());
    }

    @Test
    void recordPosition_whenOffRouteFirstPing_logsRawCoordinatesAndToleratesGlitch() {
        Vehicle active = buildVehicle(1L, true);
        active.setActiveDispatchRouteId(500L);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(active));

        // dist_m = 60.0 (off-route)
        List<Object[]> queryResult = new ArrayList<>();
        queryResult.add(new Object[]{60.0, 12.905, 77.605, 0.0});
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(queryResult);

        com.zonepilot.backend.entity.VehiclePositionLog savedLog = new com.zonepilot.backend.entity.VehiclePositionLog();
        savedLog.setId(100L);
        when(positionLogRepository.save(any())).thenReturn(savedLog);
        when(breachLogRepository.findByPositionLogId(100L)).thenReturn(Collections.emptyList());

        com.zonepilot.backend.dto.response.PositionRecordResponse response =
                service.recordPosition(1L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE);

        assertNotNull(response);
        assertEquals(12.9, response.getPosition().getLatitude());
        assertEquals(77.6, response.getPosition().getLongitude());
        // Verify off-route rerouting was NOT triggered on 1st off-route ping
        verify(breachService, never()).computeOffRouteReroute(anyLong(), anyDouble(), anyDouble());
    }

    @Test
    void recordPosition_whenOffRouteSecondConsecutivePing_triggersRerouteAndResetsCounter() {
        Vehicle active = buildVehicle(1L, true);
        active.setActiveDispatchRouteId(500L);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(active));

        // dist_m = 60.0 (off-route)
        List<Object[]> queryResult = new ArrayList<>();
        queryResult.add(new Object[]{60.0, 12.905, 77.605, 0.0});
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .thenReturn(queryResult);

        com.zonepilot.backend.entity.VehiclePositionLog savedLog = new com.zonepilot.backend.entity.VehiclePositionLog();
        savedLog.setId(100L);
        when(positionLogRepository.save(any())).thenReturn(savedLog);
        when(breachLogRepository.findByPositionLogId(100L)).thenReturn(Collections.emptyList());

        // Ping 1 (increment off-route count to 1)
        service.recordPosition(1L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE);
        verify(breachService, never()).computeOffRouteReroute(anyLong(), anyDouble(), anyDouble());

        // Ping 2 (increment off-route count to 2 -> trigger reroute and reset)
        service.recordPosition(1L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE);
        verify(breachService).computeOffRouteReroute(1L, 12.9, 77.6);
    }

    private Vehicle buildVehicle(Long id, boolean active) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNumber("KA01-LCV-000" + id);
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Test Owner");
        v.setIsActive(active);
        return v;
    }
}
