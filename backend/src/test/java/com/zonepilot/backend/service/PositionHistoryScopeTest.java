package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.dto.response.PositionResponse;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tour/group scoping for position history: cross-group tourIds 403, tour
 * filters delegate to the tour query, unfiltered manager reads stay inside
 * their own groups.
 */
@ExtendWith(MockitoExtension.class)
class PositionHistoryScopeTest {

    @Mock VehicleRepository vehicles;
    @Mock VehiclePositionLogRepository positionLogs;
    @Mock DispatchRouteRepository routes;
    @Mock TourRepository tours;
    @Mock JdbcTemplate jdbc;
    @Mock ApplicationEventPublisher events;
    @Mock TickBroadcaster broadcaster;
    @Mock DriverRepository drivers;
    @Mock AppUserRepository appUsers;
    @Mock GroupScope scope;
    @Mock FleetGroupRepository fleetGroups;
    @Mock GroupVehicleRepository groupVehicles;

    private PositionTrackingService service;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        service = new PositionTrackingService(vehicles, positionLogs, routes, tours,
                jdbc, events, broadcaster, drivers, appUsers, scope, fleetGroups, groupVehicles);
    }

    private FleetGroup group(long id) {
        FleetGroup g = new FleetGroup();
        g.setId(id);
        return g;
    }

    private Tour tour(long id, FleetGroup g, long vehicleId) {
        Tour t = new Tour();
        t.setId(id);
        t.setGroup(g);
        Vehicle v = new Vehicle();
        v.setId(vehicleId);
        t.setVehicle(v);
        Driver d = new Driver();
        d.setId(3L);
        t.setDriver(d);
        return t;
    }

    private VehiclePositionLog log(long vehicleId) {
        VehiclePositionLog p = new VehiclePositionLog();
        p.setVehicleId(vehicleId);
        p.setPosition(gf.createPoint(new Coordinate(77.62, 12.93)));
        p.setRecordedAt(Instant.parse("2026-10-20T08:00:00Z"));
        p.setSource(PositionSource.LIVE);
        return p;
    }

    @Test
    void tourFilter_crossGroup_403() {
        FleetGroup other = group(2L);
        when(tours.findById(7L)).thenReturn(Optional.of(tour(7L, other, 21L)));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(other);

        assertThatThrownBy(() -> service.getPositionHistory(21L, null, null, 7L, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(positionLogs, never()).findByVehicleIdAndTourIdOrderByRecordedAtDesc(any(), any());
    }

    @Test
    void tourFilter_delegatesToTourQuery() {
        FleetGroup own = group(1L);
        when(tours.findById(7L)).thenReturn(Optional.of(tour(7L, own, 21L)));
        when(positionLogs.findByVehicleIdAndTourIdOrderByRecordedAtDesc(21L, 7L))
                .thenReturn(List.of(log(21L)));

        List<PositionResponse> out = service.getPositionHistory(21L, null, null, 7L, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getVehicleId()).isEqualTo(21L);
        verify(positionLogs, never()).findByVehicleIdOrderByRecordedAtDesc(any());
    }

    @Test
    void tourFilter_wrongVehicle_rejected() {
        FleetGroup own = group(1L);
        when(tours.findById(7L)).thenReturn(Optional.of(tour(7L, own, 21L)));

        assertThatThrownBy(() -> service.getPositionHistory(99L, null, null, 7L, null))
                .isInstanceOf(com.zonepilot.backend.exception.ValidationException.class);
    }

    @Test
    void noFilter_managerOutsideGroups_denied() {
        when(scope.isOwner()).thenReturn(false);
        when(scope.currentUserId()).thenReturn(Optional.of(42L));
        when(groupVehicles.findByVehicleId(21L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getPositionHistory(21L, null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
