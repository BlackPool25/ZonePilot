package com.zonepilot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RED: audit feedback — MQTT per-device auth, server-side binding, worker identity,
 * throttle + version quarantine, opId idempotency on positions.
 */
@ExtendWith(MockitoExtension.class)
class IngestAuthBindingTest {

    @Mock VehicleRepository vehicles;
    @Mock PositionTrackingService positions;
    @Mock PositionQuarantineRepository quarantineRepo;
    @Mock DispatchRouteRepository dispatchRoutes;
    @Mock TourRepository tourRepo;
    @Mock JdbcTemplate jdbc;
    @Mock ApplicationEventPublisher events;
    @Mock TickBroadcaster broadcaster;
    @Mock DriverRepository driverRepo;
    @Mock AppUserRepository appUsers;
    @Mock GroupScope scope;
    @Mock FleetGroupRepository fleetGroups;
    @Mock GroupVehicleRepository groupVehicles;

    private Vehicle vehicle;
    private Tour serverTour;

    @BeforeEach
    void setUp() {
        TraccarIngestService.resetThrottle();
        TraccarIngestService.setThrottleLimits(10000, 10000);
        vehicle = new Vehicle();
        vehicle.setId(1L);
        vehicle.setRegistrationNumber("KA01-AUTH-1");
        vehicle.setVehicleClass(VehicleClass.LCV);
        vehicle.setIsActive(true);
        vehicle.setDeviceApiKey("dev-key-1");
        lenient().when(vehicles.findById(1L)).thenReturn(Optional.of(vehicle));
        lenient().when(jdbc.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class), (Object[]) any()))
                .thenReturn(List.of());
        serverTour = mock(Tour.class);
        FleetGroup g = mock(FleetGroup.class);
        lenient().when(g.getId()).thenReturn(7L);
        lenient().when(serverTour.getId()).thenReturn(3L);
        lenient().when(serverTour.getGroup()).thenReturn(g);
        lenient().when(serverTour.getStatus()).thenReturn(TourStatus.ACTIVE);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
        TraccarIngestService.resetThrottle();
        TraccarIngestService.setThrottleLimits(10000, 10000);
    }

    private TraccarIngestService ingest() {
        return new TraccarIngestService(vehicles, positions,
                new QualityFilter(new FakeIngestState()), new FakeIngestState(),
                quarantineRepo, new ObjectMapper(), dispatchRoutes, tourRepo,
                AuditService.noop(), new IdempotencyService());
    }

    private void linkActiveRoute() {
        vehicle.setActiveDispatchRouteId(9L);
        DispatchRoute route = mock(DispatchRoute.class);
        lenient().when(route.getTour()).thenReturn(serverTour);
        lenient().when(dispatchRoutes.findById(9L)).thenReturn(Optional.of(route));
        // PositionTrackingService mock is bypassed here: unit-test the ingest
        // layer binding by stubbing recordPosition to succeed.
    }

    @Test
    void mqtt_wrongDeviceKey_quarantinedAuthReject() {
        TraccarIngestService svc = ingest();
        svc.handle("zonepilot/fleet/vehicles/1/position",
                "{\"lat\":12.9,\"lon\":77.6,\"deviceKey\":\"wrong\"}");
        ArgumentCaptor<PositionQuarantine> cap =
                ArgumentCaptor.forClass(PositionQuarantine.class);
        verify(quarantineRepo, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream()
                .anyMatch(q -> "AUTH_REJECT".equals(q.getReason())));
    }

    @Test
    void mqtt_clientGroupMismatch_ignoredAndQuarantined() {
        // Server binding says group 7; forged client groupId 888 is rejected.
        vehicle.setActiveDispatchRouteId(9L);
        DispatchRoute route = mock(DispatchRoute.class);
        when(route.getTour()).thenReturn(serverTour);
        when(dispatchRoutes.findById(9L)).thenReturn(Optional.of(route));
        ingest().handle("zonepilot/fleet/vehicles/1/position",
                "{\"lat\":12.9,\"lon\":77.6,\"deviceKey\":\"dev-key-1\",\"groupId\":888}");
        verify(quarantineRepo, atLeastOnce()).save(argThat(
                q -> "GROUP_MISMATCH".equals(q.getReason())));
    }

    @Test
    void mqtt_clientTourMismatch_quarantined() {
        vehicle.setActiveDispatchRouteId(9L);
        DispatchRoute route = mock(DispatchRoute.class);
        when(route.getTour()).thenReturn(serverTour);
        when(dispatchRoutes.findById(9L)).thenReturn(Optional.of(route));
        ingest().handle("zonepilot/fleet/vehicles/1/position",
                "{\"lat\":12.9,\"lon\":77.6,\"deviceKey\":\"dev-key-1\",\"tourId\":77}");
        verify(quarantineRepo, atLeastOnce()).save(argThat(
                q -> "TOUR_MISMATCH".equals(q.getReason())));
    }

    @Test
    void mqtt_schemaVersionConflict_quarantined() {
        ingest().handle("zonepilot/fleet/vehicles/1/position",
                "{\"lat\":12.9,\"lon\":77.6,\"deviceKey\":\"dev-key-1\",\"v\":2}");
        verify(quarantineRepo, atLeastOnce()).save(argThat(
                q -> "VERSION_CONFLICT".equals(q.getReason())));
    }

    @Test
    void throttle_excessFixes_quarantined() {
        TraccarIngestService.setThrottleLimits(2, 10000);
        TraccarIngestService svc = ingest();
        String payload = "{\"lat\":12.9,\"lon\":77.6,\"deviceKey\":\"dev-key-1\"}";
        svc.handle("zonepilot/fleet/vehicles/1/position", payload);
        svc.handle("zonepilot/fleet/vehicles/1/position", payload);
        svc.handle("zonepilot/fleet/vehicles/1/position", payload);
        verify(quarantineRepo, atLeastOnce()).save(argThat(
                q -> "THROTTLED".equals(q.getReason())));
    }

    @Test
    void http_duplicateOpId_conflicts() {
        TraccarIngestService svc = ingest();
        var ok = new com.zonepilot.backend.dto.response.PositionRecordResponse();
        var detail = new com.zonepilot.backend.dto.response.PositionRecordResponse.PositionDetail();
        detail.setLatitude(12.9);
        detail.setLongitude(77.6);
        detail.setRecordedAt(Instant.now());
        ok.setPosition(detail);
        when(positions.recordPosition(anyLong(), any(), any(), any(), anyDouble(),
                anyDouble(), any(), any(), any(), any()))
                .thenReturn(ok);
        svc.ingest(1L, 12.9, 77.6, Instant.now(), null, null, null,
                PositionSource.LIVE, null, 3L, 7L, "op-1");
        assertThrows(com.zonepilot.backend.exception.ConflictException.class, () ->
                svc.ingest(1L, 12.9, 77.6, Instant.now(), null, null, null,
                        PositionSource.LIVE, null, 3L, 7L, "op-1"));
    }

    @Test
    void bindKeys_managerNullTour_requiresGroupAccess() {
        // tourId null → resolve from vehicle route; an authenticated manager
        // must still pass the group check on the resolved tour.
        DepotPrincipal p = new DepotPrincipal("mgr@zp.test", null, Role.DISPATCHER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, List.of()));
        vehicle.setActiveDispatchRouteId(9L);
        DispatchRoute route = mock(DispatchRoute.class);
        when(route.getTour()).thenReturn(serverTour);
        when(dispatchRoutes.findById(9L)).thenReturn(Optional.of(route));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(any(FleetGroup.class));

        PositionTrackingService svc = new PositionTrackingService(vehicles,
                mock(VehiclePositionLogRepository.class), dispatchRoutes, tourRepo,
                jdbc, events, broadcaster, driverRepo, appUsers, scope,
                fleetGroups, groupVehicles);
        // recordPosition resolves tour 3 from the vehicle route (tourId null)
        // and must hit the group check for the manager caller.
        lenient().when(serverTour.getDriver()).thenReturn(mock(Driver.class));
        assertThrows(AccessDeniedException.class, () -> svc.recordPosition(
                1L, null, null, null, 12.9, 77.6, Instant.now(),
                null, null, PositionSource.LIVE));
    }

    @Test
    void bindKeys_nullCallerWithoutWorker_rejected() {
        // No auth + no worker identity: the null-caller defaulting path is gone.
        vehicle.setActiveDispatchRouteId(9L);
        DispatchRoute route = mock(DispatchRoute.class);
        when(route.getTour()).thenReturn(serverTour);
        when(dispatchRoutes.findById(9L)).thenReturn(Optional.of(route));
        PositionTrackingService svc = new PositionTrackingService(vehicles,
                mock(VehiclePositionLogRepository.class), dispatchRoutes, tourRepo,
                jdbc, events, broadcaster, driverRepo, appUsers, scope,
                fleetGroups, groupVehicles);
        assertThrows(AccessDeniedException.class, () -> svc.recordPosition(
                1L, null, null, null, 12.9, 77.6, Instant.now(),
                BigDecimal.ZERO, null, PositionSource.LIVE));
    }

    @Test
    void bindKeys_workerIdentity_allowedWithoutCaller() {
        vehicle.setActiveDispatchRouteId(9L);
        DispatchRoute route = mock(DispatchRoute.class);
        when(route.getTour()).thenReturn(serverTour);
        when(dispatchRoutes.findById(9L)).thenReturn(Optional.of(route));
        Driver d = mock(Driver.class);
        lenient().when(d.getId()).thenReturn(11L);
        lenient().when(serverTour.getDriver()).thenReturn(d);
        VehiclePositionLogRepository logs = mock(VehiclePositionLogRepository.class);
        var saved = new VehiclePositionLog();
        saved.setId(5L);
        when(logs.saveAndFlush(any())).thenReturn(saved);
        PositionTrackingService svc = new PositionTrackingService(vehicles, logs,
                dispatchRoutes, tourRepo, jdbc, events, broadcaster, driverRepo,
                appUsers, scope, fleetGroups, groupVehicles);
        IngestWorkerContext.runAsWorker(() ->
                svc.recordPosition(1L, null, null, null, 12.9, 77.6,
                        Instant.now(), null, null, PositionSource.LIVE));
        verify(logs).saveAndFlush(any());
        // No group-scope check for the trusted worker path.
        verify(scope, never()).requireGroupAccess(any(FleetGroup.class));
    }
}
