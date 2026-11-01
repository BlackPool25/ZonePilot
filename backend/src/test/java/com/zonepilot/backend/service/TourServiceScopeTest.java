package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.dto.response.TourResponse;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.TourStopRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Wave A fix 1 (TDD RED): TourService.get() must enforce group scope,
 * mirroring track(). Cross-group get -> 403, own-group -> 200.
 */
@ExtendWith(MockitoExtension.class)
class TourServiceScopeTest {

    @Mock TourRepository tours;
    @Mock TourStopRepository stops;
    @Mock FleetGroupRepository groups;
    @Mock VehicleRepository vehicles;
    @Mock DriverRepository drivers;
    @Mock DispatchRouteRepository routes;
    @Mock GroupScope scope;
    @Mock TourOptimizerService optimizer;
    @Mock RouteComplianceService compliance;
    @Mock DriverService driverService;
    @Mock PositionTrackingService positions;

    @InjectMocks TourService service;

    private Tour tourIn(FleetGroup group) {
        Tour t = new Tour();
        t.setId(1L);
        t.setGroup(group);
        Vehicle v = new Vehicle();
        v.setId(2L);
        t.setVehicle(v);
        Driver d = new Driver();
        d.setId(3L);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        return t;
    }

    @Test
    void get_enforcesGroupScope() {
        FleetGroup group = new FleetGroup();
        when(tours.findById(1L)).thenReturn(Optional.of(tourIn(group)));
        when(stops.findByTourIdOrderBySeqAsc(1L)).thenReturn(List.of());
        when(routes.findByTourIdOrderByIdDesc(1L)).thenReturn(List.of());

        TourResponse r = service.get(1L);

        assertThat(r.getGroupId()).isNull(); // group fixture has no id; response still built
        verify(scope).requireGroupAccess(group);
    }

    @Test
    void get_crossGroup_denied() {
        FleetGroup group = new FleetGroup();
        when(tours.findById(1L)).thenReturn(Optional.of(tourIn(group)));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(group);

        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(AccessDeniedException.class);
    }
}
