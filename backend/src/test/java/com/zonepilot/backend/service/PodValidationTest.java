package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
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
import static org.mockito.Mockito.*;

/**
 * Round-1 finding 2: PoD requires a GPS fix (400 on null) and must land
 * within 100m of the current stop; order is still enforced. Offline-queue
 * replays funnel through the same service method, so they re-validate.
 */
@ExtendWith(MockitoExtension.class)
class PodValidationTest {

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
                scope, optimizer, compliance, driverService, positions,
                mock(com.zonepilot.backend.repository.GroupVehicleRepository.class), mock(com.zonepilot.backend.repository.GroupDriverRepository.class));
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
    void nullGps_rejected400() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> tourService.deliver(23L, 24L, null, null));
        assertTrue(e.getMessage().contains("GPS fix"));
        // Tour untouched: still active, stop still pending.
        assertEquals(TourStatus.ACTIVE, tour.getStatus());
        assertEquals(StopStatus.PENDING, stop.getStatus());
    }

    @Test
    void farPod_rejected400() {
        // ~3km away: outside the 100m fence.
        ValidationException e = assertThrows(ValidationException.class,
                () -> tourService.deliver(23L, 24L, 12.95, 77.64));
        assertTrue(e.getMessage().contains("limit 100"), e.getMessage());
        assertEquals(StopStatus.PENDING, stop.getStatus());
    }

    @Test
    void nearPod_acceptedAndParksForApproval() {
        // ~70m away: inside the fence; single stop parks the tour for
        // manager approval instead of auto-completing.
        var response = tourService.deliver(23L, 24L, 12.9305, 77.6205);
        assertEquals("PENDING_APPROVAL", response.getStatus());
        assertEquals(StopStatus.DELIVERED, stop.getStatus());
        assertEquals(VehicleAvailability.IN_TRANSIT, vehicle.getAvailability());
    }

    @Test
    void outOfOrder_rejected() {
        TourStop second = new TourStop();
        second.setId(25L);
        second.setTour(tour);
        second.setSeq(1);
        second.setPoint(GF.createPoint(new Coordinate(77.63, 12.94)));
        second.setStatus(StopStatus.PENDING);
        when(stops.findById(25L)).thenReturn(Optional.of(second));
        when(stops.findByTourIdOrderBySeqAsc(23L)).thenReturn(List.of(stop, second));

        ValidationException e = assertThrows(ValidationException.class,
                () -> tourService.deliver(23L, 25L, 12.94, 77.63));
        assertTrue(e.getMessage().contains("current stop"), e.getMessage());
    }
}
