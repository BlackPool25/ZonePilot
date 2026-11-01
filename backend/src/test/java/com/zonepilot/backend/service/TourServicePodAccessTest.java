package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Role-split deliver: the manager path rejects DRIVER logins (they use the
 * driver endpoints, which check the owned tour); cross-tour drivers get 403.
 */
@ExtendWith(MockitoExtension.class)
class TourServicePodAccessTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @Mock private TourRepository tours;
    @Mock private TourStopRepository stops;
    @Mock private FleetGroupRepository groups;
    @Mock private VehicleRepository vehicles;
    @Mock private DriverRepository drivers;
    @Mock private DispatchRouteRepository routes;
    @Mock private GroupScope scope;
    @Mock private TourOptimizerService optimizer;
    @Mock private RouteComplianceService compliance;
    @Mock private DriverService driverService;
    @Mock private PositionTrackingService positions;

    private TourService tourService;
    private Tour tour;
    private FleetGroup group;
    private TourStop stop;

    @BeforeEach
    void setUp() {
        tourService = new TourService(tours, stops, groups, vehicles, drivers, routes,
                scope, optimizer, compliance, driverService, positions,
                mock(com.zonepilot.backend.repository.GroupVehicleRepository.class), mock(com.zonepilot.backend.repository.GroupDriverRepository.class));
        Vehicle vehicle = new Vehicle();
        vehicle.setId(21L);
        vehicle.setRegistrationNumber("KA01-POD-21");
        vehicle.setAvailability(VehicleAvailability.IN_TRANSIT);
        Driver driver = new Driver();
        driver.setId(22L);
        tour = new Tour();
        tour.setId(23L);
        group = new FleetGroup();
        group.setId(4L);
        tour.setGroup(group);
        tour.setVehicle(vehicle);
        tour.setDriver(driver);
        tour.setStatus(TourStatus.ACTIVE);
        stop = new TourStop();
        stop.setId(24L);
        stop.setTour(tour);
        stop.setSeq(0);
        stop.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        stop.setStatus(StopStatus.PENDING);
        lenient().when(tours.findByIdWithLock(23L)).thenReturn(Optional.of(tour));
        lenient().when(tours.findById(23L)).thenReturn(Optional.of(tour));
        lenient().when(stops.findById(24L)).thenReturn(Optional.of(stop));
        lenient().when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop));
        lenient().when(routes.findByTourIdOrderByIdDesc(23L)).thenReturn(List.of());
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private void login(Role role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal("u@zp.test", null, role), null, List.of()));
    }

    private com.zonepilot.backend.dto.request.DeliverStopRequest reqAt(double lat, double lng) {
        var req = new com.zonepilot.backend.dto.request.DeliverStopRequest();
        req.setLat(lat);
        req.setLng(lng);
        return req;
    }

    @Test
    void deliverForDriver_ownedTourDriver_noGroupAccessNeeded() {
        login(Role.DRIVER);
        when(driverService.requireOwnedTour(23L)).thenReturn(tour);

        var response = tourService.deliverForDriver(23L, 24L, reqAt(12.9305, 77.6205));

        assertEquals("PENDING_APPROVAL", response.getStatus());
        verify(scope, never()).requireGroupAccess(any(FleetGroup.class));
    }

    @Test
    void deliver_managerPath_rejectsDriver() {
        login(Role.DRIVER);

        assertThrows(AccessDeniedException.class,
                () -> tourService.deliver(23L, 24L, reqAt(12.9305, 77.6205)));
        assertEquals(StopStatus.PENDING, stop.getStatus());
    }

    @Test
    void deliverForDriver_crossTourDriver_403() {
        login(Role.DRIVER);
        when(driverService.requireOwnedTour(23L))
                .thenThrow(new AccessDeniedException("Tour is not your assignment"));

        assertThrows(AccessDeniedException.class,
                () -> tourService.deliverForDriver(23L, 24L, reqAt(12.9305, 77.6205)));
        assertEquals(StopStatus.PENDING, stop.getStatus());
        verify(scope, never()).requireGroupAccess(any(FleetGroup.class));
    }

    @Test
    void skipForDriver_ownedTourDriver_noGroupAccessNeeded() {
        login(Role.DRIVER);
        when(driverService.requireOwnedTour(23L)).thenReturn(tour);

        var response = tourService.skipForDriver(23L, 24L, "closed", null);

        assertEquals("PENDING_APPROVAL", response.getStatus());
        verify(scope, never()).requireGroupAccess(any(FleetGroup.class));
    }

    @Test
    void deliver_managerPath_stillUsesGroupAccess() {
        // Manager login: role-gated straight to the group check, which owns
        // the decision (no owned-tour probe, no swallowed TX poison).
        login(Role.DISPATCHER);

        var response = tourService.deliver(23L, 24L, reqAt(12.9305, 77.6205));

        assertEquals("PENDING_APPROVAL", response.getStatus());
        verify(scope).requireGroupAccess(group);
    }
}
