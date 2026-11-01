package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wave B fix 2 (TDD RED): PositionTrackingService must not trust
 * client-supplied tourId/driverId/groupId from a driver caller. Driver
 * fixes bind to the caller's owned tour; spoofed tourIds are rejected.
 */
@ExtendWith(MockitoExtension.class)
class IngestBindingTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private VehiclePositionLogRepository positionLogRepository;
    @Mock private DispatchRouteRepository dispatchRouteRepository;
    @Mock private TourRepository tourRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApplicationEventPublisher events;
    @Mock private TickBroadcaster broadcaster;
    @Mock private DriverRepository driverRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private com.zonepilot.backend.auth.GroupScope scope;

    private PositionTrackingService service;

    @BeforeEach
    void setUp() {
        service = new PositionTrackingService(vehicleRepository, positionLogRepository,
                dispatchRouteRepository, tourRepository, jdbcTemplate, events, broadcaster,
                driverRepository, appUserRepository, scope);
        // Vehicle 1 active, no route (keys must come from the caller/tour).
        Vehicle v = new Vehicle();
        v.setId(1L);
        v.setRegistrationNumber("KA01-ING-1");
        v.setVehicleClass(VehicleClass.LCV);
        v.setIsActive(true);
        lenient().when(vehicleRepository.findById(1L)).thenReturn(Optional.of(v));
        lenient().when(jdbcTemplate.query(anyString(),
                any(org.springframework.jdbc.core.RowMapper.class), (Object[]) any()))
                .thenReturn(List.of());
        com.zonepilot.backend.entity.VehiclePositionLog saved =
                new com.zonepilot.backend.entity.VehiclePositionLog();
        saved.setId(7L);
        lenient().when(positionLogRepository.saveAndFlush(any())).thenReturn(saved);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void loginAsDriver(String email) {
        DepotPrincipal p = new DepotPrincipal(email, null, Role.DRIVER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, List.of()));
    }

    private Tour tour(long id, long driverId, long groupId) {
        Tour t = mock(Tour.class);
        Driver d = mock(Driver.class);
        FleetGroup g = mock(FleetGroup.class);
        lenient().when(d.getId()).thenReturn(driverId);
        lenient().when(g.getId()).thenReturn(groupId);
        lenient().when(t.getId()).thenReturn(id);
        lenient().when(t.getDriver()).thenReturn(d);
        lenient().when(t.getGroup()).thenReturn(g);
        return t;
    }

    private void linkLogin(String email, long userId, long driverId) {
        AppUser u = mock(AppUser.class);
        lenient().when(u.getId()).thenReturn(userId);
        lenient().when(appUserRepository.findByEmail(email)).thenReturn(Optional.of(u));
        Driver d = mock(Driver.class);
        lenient().when(d.getId()).thenReturn(driverId);
        lenient().when(driverRepository.findByUserId(userId)).thenReturn(Optional.of(d));
    }

    @Test
    void driver_spoofedTourId_rejected() {
        loginAsDriver("spoof@example.com");
        linkLogin("spoof@example.com", 50L, 99L);
        // Tour 3 belongs to driver 9, not the caller (99).
        Tour other = tour(3L, 9L, 7L);
        when(tourRepository.findById(3L)).thenReturn(Optional.of(other));

        assertThrows(AccessDeniedException.class, () -> service.recordPosition(
                1L, 99L, 3L, 7L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE));
        verify(positionLogRepository, never()).saveAndFlush(any());
    }

    @Test
    void driver_ownTour_clientKeysOverridden() {
        loginAsDriver("me@example.com");
        linkLogin("me@example.com", 51L, 9L);
        Tour own = tour(3L, 9L, 7L);
        when(tourRepository.findById(3L)).thenReturn(Optional.of(own));

        // Client lies about driver/group; server binds the caller's own keys.
        service.recordPosition(1L, 999L, 3L, 888L, 12.9, 77.6,
                Instant.now(), null, null, PositionSource.LIVE);

        ArgumentCaptor<com.zonepilot.backend.entity.VehiclePositionLog> captor =
                ArgumentCaptor.forClass(com.zonepilot.backend.entity.VehiclePositionLog.class);
        verify(positionLogRepository).saveAndFlush(captor.capture());
        assertEquals(9L, captor.getValue().getDriverId());
        assertEquals(7L, captor.getValue().getGroupId());
        assertEquals(3L, captor.getValue().getTourId());
    }

    @Test
    void manager_spoofedGroupId_rejected() {
        DepotPrincipal p = new DepotPrincipal("mgr@example.com", null, Role.DISPATCHER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, List.of()));
        Tour t = tour(3L, 9L, 7L);
        when(tourRepository.findById(3L)).thenReturn(Optional.of(t));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(any(FleetGroup.class));

        assertThrows(AccessDeniedException.class, () -> service.recordPosition(
                1L, 9L, 3L, 777L, 12.9, 77.6, Instant.now(), null, null, PositionSource.LIVE));
        verify(positionLogRepository, never()).saveAndFlush(any());
    }
}
