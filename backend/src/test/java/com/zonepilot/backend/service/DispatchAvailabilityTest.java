package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Task 4: stored availability auto-maps assign → RESERVED,
 * start/DRIVING → IN_TRANSIT, done → AVAILABLE.
 */
@ExtendWith(MockitoExtension.class)
class DispatchAvailabilityTest {

    @Mock private DriverRepository drivers;
    @Mock private DispatchRouteRepository routes;
    @Mock private VehicleDocumentRepository documents;
    @Mock private TripSlipRepository slips;
    @Mock private SosAlertRepository sosAlerts;
    @Mock private AppUserRepository users;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private TourRepository tourRepository;
    @Mock private TourStopRepository tourStops;
    @Mock private com.zonepilot.backend.auth.AuthService authService;

    private DriverService driverService;
    private Driver driver;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        driverService = new DriverService(drivers, routes, documents, slips, sosAlerts,
                users, vehicleRepository, tourRepository, tourStops, authService);
        DepotPrincipal p = new DepotPrincipal("driver@zp.test", null, Role.DRIVER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, List.of()));
        AppUser user = new AppUser();
        user.setId(7L);
        lenient().when(users.findByEmail("driver@zp.test")).thenReturn(Optional.of(user));
        driver = new Driver();
        driver.setId(3L);
        driver.setName("Duty Dan");
        driver.setLicenceNo("LIC-DUTY-1");
        driver.setLicenceExpiry(java.time.LocalDate.now().plusYears(1));
        lenient().when(drivers.findByUserId(7L)).thenReturn(Optional.of(driver));
        lenient().when(drivers.findById(3L)).thenReturn(Optional.of(driver));
        vehicle = new Vehicle();
        vehicle.setId(9L);
        vehicle.setRegistrationNumber("KA01-DUTY-9");
        vehicle.setAvailability(VehicleAvailability.AVAILABLE);
        lenient().when(documents.findByVehicleId(9L)).thenReturn(List.of());
        lenient().when(routes.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assign_flipsReserved() {
        DispatchRoute route = new DispatchRoute();
        route.setId(50L);
        route.setVehicle(vehicle);
        route.setAssignmentStatus(AssignmentStatus.UNASSIGNED);
        when(routes.findById(50L)).thenReturn(Optional.of(route));

        driverService.assign(50L, 3L);

        assertEquals(VehicleAvailability.RESERVED, vehicle.getAvailability());
    }

    @Test
    void start_flipsInTransit() {
        DispatchRoute route = new DispatchRoute();
        route.setId(51L);
        route.setVehicle(vehicle);
        route.setDriver(driver);
        route.setAssignmentStatus(AssignmentStatus.ACCEPTED);
        when(routes.findById(51L)).thenReturn(Optional.of(route));

        driverService.start(51L);

        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
    }

    @Test
    void dutyDriving_flipsInTransit_doneFlipsAvailable() {
        Tour tour = new Tour();
        tour.setId(60L);
        FleetGroup g = new FleetGroup();
        g.setId(2L);
        tour.setGroup(g);
        tour.setVehicle(vehicle);
        tour.setDriver(driver);
        tour.setStatus(TourStatus.ACTIVE);
        when(tourRepository.findById(60L)).thenReturn(Optional.of(tour));
        when(tourStops.findByTourIdOrderBySeqAsc(60L)).thenReturn(List.of());
        when(routes.findByTourIdOrderByIdDesc(60L)).thenReturn(List.of());

        TourResponse driving = driverService.setTourDuty(60L, DutyStatus.DRIVING);
        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
        assertEquals("DRIVING", driving.getDutyStatus());

        TourResponse done = driverService.setTourDuty(60L, DutyStatus.OFF_DUTY);
        assertEquals(VehicleAvailability.AVAILABLE, vehicle.getAvailability());
        assertEquals("OFF_DUTY", done.getDutyStatus());
    }
}
