package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.AuthService;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.StopStatus;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.TourStop;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.DutyLogRepository;
import com.zonepilot.backend.repository.SosAlertRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.TourStopRepository;
import com.zonepilot.backend.repository.TripSlipRepository;
import com.zonepilot.backend.repository.VehicleDocumentRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Wave B fix 4: myTours must batch stops/routes (1+1+1 queries), not
 * 3N via per-tour toTourResponse.
 */
@ExtendWith(MockitoExtension.class)
class DriverServiceMyToursTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

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
    @Mock private AuthService authService;

    private DriverService service;

    @BeforeEach
    void setUp() {
        service = new DriverService(drivers, routes, documents, slips, sosAlerts,
                users, vehicleRepository, tourRepository, tourStops, dutyLogs, authService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new DepotPrincipal("d@zp.test", null, Role.DRIVER), null, List.of()));
        AppUser u = new AppUser();
        u.setId(7L);
        lenient().when(users.findByEmail("d@zp.test")).thenReturn(Optional.of(u));
        Driver d = new Driver();
        d.setId(3L);
        lenient().when(drivers.findByUserId(7L)).thenReturn(Optional.of(d));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private Tour tour(long id) {
        Tour t = new Tour();
        t.setId(id);
        FleetGroup g = new FleetGroup();
        g.setId(4L);
        t.setGroup(g);
        Vehicle v = new Vehicle();
        v.setId(9L);
        t.setVehicle(v);
        Driver d = new Driver();
        d.setId(3L);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        t.setDutyStatus(DutyStatus.OFF_DUTY);
        return t;
    }

    private TourStop stop(long id, Tour t, int seq) {
        TourStop s = new TourStop();
        s.setId(id);
        s.setTour(t);
        s.setSeq(seq);
        s.setPoint(GF.createPoint(new Coordinate(77.62, 12.93)));
        s.setStatus(StopStatus.PENDING);
        return s;
    }

    @Test
    void myTours_batchesStopsAndRoutes() {
        Tour t1 = tour(101L);
        Tour t2 = tour(102L);
        when(tourRepository.findByDriverIdOrderByIdDesc(3L)).thenReturn(List.of(t1, t2));
        when(tourStops.findByTourIdInOrderByTourIdAscSeqAsc(List.of(101L, 102L)))
                .thenReturn(List.of(stop(1L, t1, 0), stop(2L, t2, 0), stop(3L, t2, 1)));
        DispatchRoute r1 = new DispatchRoute();
        r1.setId(201L);
        r1.setTour(t2);
        DispatchRoute r2 = new DispatchRoute();
        r2.setId(202L);
        r2.setTour(t2);
        when(routes.findByTourIdIn(List.of(101L, 102L))).thenReturn(List.of(r1, r2));

        List<TourResponse> out = service.myTours();

        assertEquals(2, out.size());
        assertEquals(1, out.get(0).getStops().size());
        assertEquals(2, out.get(1).getStops().size());
        assertNull(out.get(0).getActiveRouteId());
        assertEquals(202L, out.get(1).getActiveRouteId());
        verify(tourStops, times(1)).findByTourIdInOrderByTourIdAscSeqAsc(any());
        verify(routes, times(1)).findByTourIdIn(any());
        verify(tourRepository, never()).findById(any());
        verify(tourStops, never()).findByTourIdOrderBySeqAsc(any());
    }

    @Test
    void myTours_empty_noBatchQueries() {
        when(tourRepository.findByDriverIdOrderByIdDesc(3L)).thenReturn(List.of());

        assertTrue(service.myTours().isEmpty());
        verifyNoInteractions(tourStops, routes);
    }
}
