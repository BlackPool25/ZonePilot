package com.zonepilot.backend.service;

import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.TourStopRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.AppUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Wave C (TDD): missing-tour track must 404, empty position history must
 * return [] — never null, never 500.
 */
@ExtendWith(MockitoExtension.class)
class MissingTourEmptyHistoryTest {

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

    @InjectMocks TourService tourService;

    @Mock VehiclePositionLogRepository positionLogs;
    @Mock JdbcTemplate jdbc;
    @Mock org.springframework.context.ApplicationEventPublisher events;
    @Mock TickBroadcaster broadcaster;
    @Mock AppUserRepository appUsers;

    @Test
    void track_missingTour_throws404() {
        when(tours.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tourService.track(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void history_emptyVehicle_returnsEmptyList() {
        when(positionLogs.findByVehicleIdOrderByRecordedAtDesc(7L)).thenReturn(List.of());
        PositionTrackingService tracking = new PositionTrackingService(
                vehicles, positionLogs, routes, tours, jdbc,
                events, broadcaster, drivers, appUsers, scope);

        assertThat(tracking.getPositionHistory(7L, null, null)).isEmpty();
    }
}
