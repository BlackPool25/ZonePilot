package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

/**
 * Task 6: PoD tap within 100m + order auto-completes the stop; the last
 * stop parks the tour in PENDING_APPROVAL (vehicle stays IN_TRANSIT) and
 * only manager approve closes the tour + flips the vehicle AVAILABLE.
 */
@ExtendWith(MockitoExtension.class)
class PodApprovalTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @Mock private TourRepository tours;
    @Mock private TourStopRepository stops;
    @Mock private FleetGroupRepository groups;
    @Mock private VehicleRepository vehicles;
    @Mock private DriverRepository drivers;
    @Mock private DispatchRouteRepository routes;
    @Mock private com.zonepilot.backend.auth.GroupScope scope;
    @Mock private TourOptimizerService optimizer;
    @Mock private RouteComplianceService compliance;
    @Mock private DriverService driverService;
    @Mock private PositionTrackingService positions;

    private TourService tourService;
    private Tour tour;
    private Vehicle vehicle;
    private TourStop stop;

    @BeforeEach
    void setUp() {
        tourService = new TourService(tours, stops, groups, vehicles, drivers, routes,
                scope, optimizer, compliance, driverService, positions);
        vehicle = new Vehicle();
        vehicle.setId(21L);
        vehicle.setRegistrationNumber("KA01-POD-21");
        vehicle.setAvailability(VehicleAvailability.IN_TRANSIT);
        Driver driver = new Driver();
        driver.setId(22L);
        tour = new Tour();
        tour.setId(23L);
        FleetGroup g = new FleetGroup();
        g.setId(4L);
        tour.setGroup(g);
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

    @Test
    void farTap_rejected() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> tourService.deliver(23L, 24L, 12.95, 77.64));
        assertTrue(e.getMessage().contains("limit 100"), e.getMessage());
        assertEquals(StopStatus.PENDING, stop.getStatus());
    }

    @Test
    void nearTap_completesStop_parksTourForApproval() {
        var response = tourService.deliver(23L, 24L, 12.9305, 77.6205);
        assertEquals("PENDING_APPROVAL", response.getStatus());
        assertEquals(StopStatus.DELIVERED, stop.getStatus());
        // Vehicle NOT released until a manager approves.
        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
    }

    @Test
    void approve_closesTour_releasesVehicle() {
        tourService.deliver(23L, 24L, 12.9305, 77.6205);
        var response = tourService.approve(23L);
        assertEquals("COMPLETED", response.getStatus());
        assertEquals(VehicleAvailability.AVAILABLE, vehicle.getAvailability());
    }

    @Test
    void approve_activeTour_rejected() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> tourService.approve(23L));
        assertTrue(e.getMessage().contains("PENDING_APPROVAL"), e.getMessage());
        assertEquals(TourStatus.ACTIVE, tour.getStatus());
    }

    @Test
    void approve_crossGroup_denied() {
        tourService.deliver(23L, 24L, 12.9305, 77.6205);
        org.mockito.Mockito.doThrow(
                new org.springframework.security.access.AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(org.mockito.Mockito.any());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> tourService.approve(23L));
        assertEquals(TourStatus.PENDING_APPROVAL, tour.getStatus());
        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
    }

    @Test
    void completeBySeq_delegatesToDeliver() {
        lenient().when(stops.findByTourIdAndSeq(23L, 0)).thenReturn(Optional.of(stop));
        var response = tourService.completeBySeq(23L, 0, 12.9305, 77.6205);
        assertEquals("PENDING_APPROVAL", response.getStatus());
        assertEquals(StopStatus.DELIVERED, stop.getStatus());
    }

    @Test
    void completeBySeq_unknownSeq_notFound() {
        lenient().when(stops.findByTourIdAndSeq(23L, 7)).thenReturn(Optional.empty());
        assertThrows(com.zonepilot.backend.exception.ResourceNotFoundException.class,
                () -> tourService.completeBySeq(23L, 7, 12.93, 77.62));
        assertEquals(StopStatus.PENDING, stop.getStatus());
    }

    @Test
    void completeBySeq_outOfOrder_rejected() {
        TourStop second = new TourStop();
        second.setId(25L);
        second.setTour(tour);
        second.setSeq(1);
        second.setPoint(GF.createPoint(new Coordinate(77.63, 12.94)));
        second.setStatus(StopStatus.PENDING);
        lenient().when(stops.findById(25L)).thenReturn(Optional.of(second));
        lenient().when(stops.findByTourIdAndSeq(23L, 1)).thenReturn(Optional.of(second));
        lenient().when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop, second));
        ValidationException e = assertThrows(ValidationException.class,
                () -> tourService.completeBySeq(23L, 1, 12.94, 77.63));
        assertTrue(e.getMessage().contains("current stop"), e.getMessage());
    }
}
