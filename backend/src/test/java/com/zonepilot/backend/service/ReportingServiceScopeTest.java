package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Tour/group scoping for the reports summary: cross-group groupIds 403,
 * tour filters keep only the tour's vehicle, managers default to own groups.
 */
@ExtendWith(MockitoExtension.class)
class ReportingServiceScopeTest {

    @Mock ZoneBreachLogRepository breaches;
    @Mock JdbcTemplate jdbc;
    @Mock TourRepository tours;
    @Mock FleetGroupRepository groups;
    @Mock GroupScope scope;

    private ReportingService service;

    @BeforeEach
    void setUp() {
        service = new ReportingService(breaches, jdbc, tours, groups, scope);
    }

    private Object[] summaryRow(long vehicleId) {
        return new Object[]{(long) vehicleId, "KA01-R-" + vehicleId, "LCV",
                1L, 0L, 1L, 0L, null, 0L};
    }

    private FleetGroup group(long id) {
        FleetGroup g = new FleetGroup();
        g.setId(id);
        return g;
    }

    @Test
    void tourFilter_keepsOnlyTourVehicle() {
        FleetGroup own = group(1L);
        Tour t = new Tour();
        t.setId(7L);
        t.setGroup(own);
        Vehicle v = new Vehicle();
        v.setId(21L);
        t.setVehicle(v);
        when(tours.findById(7L)).thenReturn(Optional.of(t));
        when(breaches.getVehicleBreachSummary())
                .thenReturn(List.of(summaryRow(21L), summaryRow(22L)));

        List<Map<String, Object>> out = service.getVehicleBreachSummary(7L, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("vehicleId")).isEqualTo(21L);
    }

    @Test
    void groupFilter_crossGroup_403() {
        FleetGroup other = group(2L);
        when(groups.findById(2L)).thenReturn(Optional.of(other));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(other);

        assertThatThrownBy(() -> service.getVehicleBreachSummary(null, 2L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noFilter_manager_defaultsToOwnVehicles() {
        when(scope.isOwner()).thenReturn(false);
        when(scope.currentUserId()).thenReturn(Optional.of(42L));
        FleetGroup own = group(1L);
        when(groups.findByManager_IdOrderByCreatedAtDesc(42L)).thenReturn(List.of(own));
        Tour t = new Tour();
        t.setGroup(own);
        Vehicle v = new Vehicle();
        v.setId(21L);
        t.setVehicle(v);
        when(tours.findByGroupId(1L)).thenReturn(List.of(t));
        when(breaches.getVehicleBreachSummary())
                .thenReturn(List.of(summaryRow(21L), summaryRow(22L)));

        List<Map<String, Object>> out = service.getVehicleBreachSummary(null, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).get("vehicleId")).isEqualTo(21L);
    }
}
