package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.response.DutyLogResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Round-1 finding 1: duty transitions append timestamped rows to duty_log
 * (ELD hybrid), IN_TRANSIT is a first-class duty state.
 */
@ExtendWith(MockitoExtension.class)
class EldDutyLogTest {

    @Mock private DriverRepository drivers;
    @Mock private DispatchRouteRepository routes;
    @Mock private VehicleDocumentRepository documents;
    @Mock private TripSlipRepository slips;
    @Mock private SosAlertRepository sosAlerts;
    @Mock private AppUserRepository users;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private TourRepository tourRepository;
    @Mock private TourStopRepository tourStops;
    @Mock private DutyLogRepository dutyLogs;
    @Mock private com.zonepilot.backend.auth.AuthService authService;

    private DriverService driverService;
    private Driver driver;
    private Vehicle vehicle;
    private Tour tour;

    @BeforeEach
    void setUp() {
        driverService = new DriverService(drivers, routes, documents, slips, sosAlerts,
                users, vehicleRepository, tourRepository, tourStops, dutyLogs, authService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal("eld@zp.test", null, Role.DRIVER), null, List.of()));
        AppUser user = new AppUser();
        user.setId(11L);
        lenient().when(users.findByEmail("eld@zp.test")).thenReturn(Optional.of(user));
        driver = new Driver();
        driver.setId(12L);
        driver.setName("Eld Ella");
        lenient().when(drivers.findByUserId(11L)).thenReturn(Optional.of(driver));
        vehicle = new Vehicle();
        vehicle.setId(13L);
        vehicle.setRegistrationNumber("KA01-ELD-13");
        tour = new Tour();
        tour.setId(14L);
        FleetGroup g = new FleetGroup();
        g.setId(3L);
        tour.setGroup(g);
        tour.setVehicle(vehicle);
        tour.setDriver(driver);
        tour.setStatus(TourStatus.ACTIVE);
        lenient().when(tourRepository.findById(14L)).thenReturn(Optional.of(tour));
        lenient().when(tourStops.findByTourIdOrderBySeqAsc(14L)).thenReturn(List.of());
        lenient().when(routes.findByTourIdOrderByIdDesc(14L)).thenReturn(List.of());
        lenient().when(dutyLogs.save(any(DutyLog.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eachTransition_appendsTimestampedLogRow() {
        driverService.setTourDuty(14L, DutyStatus.ON_DUTY);
        driverService.setTourDuty(14L, DutyStatus.DRIVING);

        ArgumentCaptor<DutyLog> captor = ArgumentCaptor.forClass(DutyLog.class);
        verify(dutyLogs, times(2)).save(captor.capture());
        List<DutyLog> rows = captor.getAllValues();
        assertEquals(DutyStatus.ON_DUTY, rows.get(0).getDutyStatus());
        assertEquals(DutyStatus.DRIVING, rows.get(1).getDutyStatus());
        assertEquals(14L, rows.get(0).getTour().getId());
        assertEquals(12L, rows.get(0).getDriver().getId());
        assertNotNull(rows.get(0).getRecordedAt());
        assertNotNull(rows.get(1).getRecordedAt());
    }

    @Test
    void inTransitDuty_mapsToInTransitAvailability() {
        driverService.setTourDuty(14L, DutyStatus.IN_TRANSIT);
        assertEquals(com.zonepilot.backend.enums.VehicleAvailability.IN_TRANSIT,
                vehicle.getAvailability());
    }

    @Test
    void dutyLog_listsRowsInRecordedOrder() {
        DutyLog first = new DutyLog();
        first.setDutyStatus(DutyStatus.ON_DUTY);
        first.setRecordedAt(Instant.parse("2026-11-01T10:00:00Z"));
        DutyLog second = new DutyLog();
        second.setDutyStatus(DutyStatus.DRIVING);
        second.setRecordedAt(Instant.parse("2026-11-01T10:05:00Z"));
        when(dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(14L))
                .thenReturn(List.of(first, second));

        List<DutyLogResponse> log = driverService.dutyLog(14L);

        assertEquals(List.of("ON_DUTY", "DRIVING"), log.stream().map(DutyLogResponse::getStatus).toList());
    }

    @Test
    void foreignTour_dutyRejected() {
        Driver stranger = new Driver();
        stranger.setId(99L);
        tour.setDriver(stranger);

        assertThrows(AccessDeniedException.class,
                () -> driverService.setTourDuty(14L, DutyStatus.DRIVING));
        verify(dutyLogs, never()).save(any());
    }
}
