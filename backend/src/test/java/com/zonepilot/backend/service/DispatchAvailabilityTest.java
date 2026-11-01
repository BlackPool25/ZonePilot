package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.request.SosRequest;
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
    @Mock private DutyLogRepository dutyLogs;
    @Mock private com.zonepilot.backend.auth.AuthService authService;

    private DriverService driverService;
    private Driver driver;
    private Vehicle vehicle;

    @BeforeEach
    void setUp() {
        driverService = new DriverService(drivers, routes, documents, slips, sosAlerts,
                users, vehicleRepository, tourRepository, tourStops, dutyLogs, authService);
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
    void dutyDriving_flipsInTransit_doneKeepsReserved() {
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

        // Mid-tour OFF_DUTY holds the vehicle (RESERVED); only tour close frees it.
        TourResponse done = driverService.setTourDuty(60L, DutyStatus.OFF_DUTY);
        assertEquals(VehicleAvailability.RESERVED, vehicle.getAvailability());
        assertEquals("OFF_DUTY", done.getDutyStatus());
        // Every transition is timestamped in the ELD log.
        verify(dutyLogs, times(2)).save(any(DutyLog.class));
    }

    @Test
    void dutyOnDutyAndBreak_flipReserved() {
        Tour tour = mkTour(61L);
        when(tourRepository.findById(61L)).thenReturn(Optional.of(tour));
        when(tourStops.findByTourIdOrderBySeqAsc(61L)).thenReturn(List.of());
        when(routes.findByTourIdOrderByIdDesc(61L)).thenReturn(List.of());

        assertEquals("ON_DUTY", driverService.setTourDuty(61L, DutyStatus.ON_DUTY).getDutyStatus());
        assertEquals(VehicleAvailability.RESERVED, vehicle.getAvailability());
        assertEquals("BREAK", driverService.setTourDuty(61L, DutyStatus.BREAK).getDutyStatus());
        assertEquals(VehicleAvailability.RESERVED, vehicle.getAvailability());
        assertEquals("IN_TRANSIT", driverService.setTourDuty(61L, DutyStatus.IN_TRANSIT).getDutyStatus());
        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
    }

    @Test
    void tourComplete_flipsAvailable() {
        Tour tour = mkTour(62L);
        tour.setStatus(TourStatus.ACTIVE);
        TourStop stop = new TourStop();
        stop.setId(70L);
        stop.setTour(tour);
        stop.setSeq(0);
        stop.setPoint(new org.locationtech.jts.geom.GeometryFactory(
                new org.locationtech.jts.geom.PrecisionModel(), 4326)
                .createPoint(new org.locationtech.jts.geom.Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        DispatchRoute leg = new DispatchRoute();
        leg.setId(80L);
        leg.setVehicle(vehicle);
        leg.setDriver(driver);
        leg.setAssignmentStatus(AssignmentStatus.IN_TRANSIT);
        TourService tourService = new TourService(
                tourRepository, tourStops, mock(com.zonepilot.backend.repository.FleetGroupRepository.class),
                vehicleRepository, drivers, routes, mock(com.zonepilot.backend.auth.GroupScope.class),
                mock(TourOptimizerService.class), mock(RouteComplianceService.class),
                driverService, mock(PositionTrackingService.class),
                mock(com.zonepilot.backend.repository.GroupVehicleRepository.class), mock(com.zonepilot.backend.repository.GroupDriverRepository.class));
        when(tourRepository.findByIdWithLock(62L)).thenReturn(Optional.of(tour));
        when(tourRepository.findById(62L)).thenReturn(Optional.of(tour));
        when(tourStops.findById(70L)).thenReturn(Optional.of(stop));
        when(tourStops.findByTourIdOrderBySeqAsc(62L)).thenReturn(List.of(stop));
        when(routes.findByTourIdOrderByIdDesc(62L)).thenReturn(List.of(leg));
        vehicle.setAvailability(VehicleAvailability.IN_TRANSIT);

        TourResponse done = tourService.deliver(62L, 70L, 12.93, 77.62);

        // Last stop parks for approval; only approve releases the vehicle.
        assertEquals("PENDING_APPROVAL", done.getStatus());
        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
        TourResponse approved = tourService.approve(62L);
        assertEquals("COMPLETED", approved.getStatus());
        assertEquals(VehicleAvailability.AVAILABLE, vehicle.getAvailability());
    }

    @Test
    void sosWithoutCoords_storesUnknownLocation() {
        SosRequest req = new SosRequest();
        req.setLat(null);
        req.setLng(null);
        req.setRouteId(null);
        when(sosAlerts.save(any(SosAlert.class))).thenAnswer(i -> i.getArgument(0));

        SosAlert alert = driverService.sos(req);

        assertNull(alert.getLocation());
        verify(sosAlerts).save(alert);
    }

    private Tour mkTour(Long id) {
        Tour tour = new Tour();
        tour.setId(id);
        FleetGroup g = new FleetGroup();
        g.setId(2L);
        tour.setGroup(g);
        tour.setVehicle(vehicle);
        tour.setDriver(driver);
        tour.setStatus(TourStatus.ACTIVE);
        return tour;
    }
}
