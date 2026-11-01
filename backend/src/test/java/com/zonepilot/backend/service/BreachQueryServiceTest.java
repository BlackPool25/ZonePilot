package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.dto.response.BreachResponse;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.enums.BreachType;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tour/group scoping for the breach list: cross-group tourIds 403, tour
 * filters return only that tour, and managers never get a findAll fallback.
 */
@ExtendWith(MockitoExtension.class)
class BreachQueryServiceTest {

    @Mock ZoneBreachLogRepository breaches;
    @Mock TourRepository tours;
    @Mock FleetGroupRepository groups;
    @Mock VehiclePositionLogRepository positions;
    @Mock GroupScope scope;

    private BreachQueryService service;

    @BeforeEach
    void setUp() {
        service = new BreachQueryService(breaches, tours, groups, positions, scope);
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
        return t;
    }

    private ZoneBreachLog breach(long id, long vehicleId, long positionLogId) {
        ZoneBreachLog b = new ZoneBreachLog();
        b.setId(id);
        Vehicle v = new Vehicle();
        v.setId(vehicleId);
        v.setRegistrationNumber("KA01-T-" + vehicleId);
        b.setVehicle(v);
        ZoneRestriction z = new ZoneRestriction();
        z.setId(9L);
        z.setName("Z");
        b.setZone(z);
        b.setBreachType(BreachType.NO_ENTRY);
        b.setBreachTime(Instant.parse("2026-10-20T08:00:00Z"));
        b.setEndTime(Instant.parse("2026-10-20T08:00:00Z"));
        b.setIsAcknowledged(false);
        b.setPositionLogId(positionLogId);
        return b;
    }

    private VehiclePositionLog position(long id) {
        VehiclePositionLog p = new VehiclePositionLog();
        p.setId(id);
        return p;
    }

    @Test
    void breachesForTours_excludesReplayRows() {
        FleetGroup own = group(1L);
        when(tours.findById(7L)).thenReturn(Optional.of(tour(7L, own, 21L)));
        VehiclePositionLog live = position(101L);
        live.setReplay(false);
        VehiclePositionLog replayed = position(102L);
        replayed.setReplay(true);
        when(positions.findByTourIdOrderByRecordedAtAsc(7L))
                .thenReturn(List.of(live, replayed));
        when(breaches.findByPositionLogIdIn(List.of(101L))).thenReturn(List.of());

        service.getBreaches(null, null, null, null, null, 7L, null);

        // Replay re-inserts must never surface in live breach reads.
        verify(breaches).findByPositionLogIdIn(List.of(101L));
        verify(breaches, never()).findByPositionLogIdIn(List.of(101L, 102L));
    }

    @Test
    void tourFilter_crossGroup_403() {
        FleetGroup other = group(2L);
        when(tours.findById(7L)).thenReturn(Optional.of(tour(7L, other, 21L)));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(other);

        assertThatThrownBy(() -> service.getBreaches(
                null, null, null, null, null, 7L, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(breaches, never()).findByPositionLogIdIn(any());
    }

    @Test
    void tourFilter_returnsOnlyThatTour() {
        FleetGroup own = group(1L);
        when(tours.findById(7L)).thenReturn(Optional.of(tour(7L, own, 21L)));
        when(positions.findByTourIdOrderByRecordedAtAsc(7L))
                .thenReturn(List.of(position(101L), position(102L)));
        when(breaches.findByPositionLogIdIn(List.of(101L, 102L)))
                .thenReturn(List.of(breach(1L, 21L, 101L), breach(2L, 21L, 102L)));

        List<BreachResponse> out = service.getBreaches(
                null, null, null, null, null, 7L, null);

        assertThat(out).hasSize(2);
        assertThat(out).allMatch(r -> r.getVehicleId() == 21L);
        // No fleet-wide query on the tour path.
        verify(breaches, never()).findAll();
    }

    @Test
    void noFilter_manager_defaultsToOwnGroups_neverFindAll() {
        when(scope.isOwner()).thenReturn(false);
        when(scope.currentUserId()).thenReturn(Optional.of(42L));
        FleetGroup own = group(1L);
        when(groups.findByManager_IdOrderByCreatedAtDesc(42L)).thenReturn(List.of(own));
        when(tours.findByGroupId(1L)).thenReturn(List.of(tour(7L, own, 21L)));
        when(positions.findByTourIdOrderByRecordedAtAsc(7L)).thenReturn(List.of(position(101L)));
        when(breaches.findByPositionLogIdIn(List.of(101L)))
                .thenReturn(List.of(breach(1L, 21L, 101L)));

        List<BreachResponse> out = service.getBreaches(
                null, null, null, null, null, null, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getVehicleId()).isEqualTo(21L);
        verify(breaches, never()).findAll();
    }

    @Test
    void noFilter_managerWithNoGroups_emptyList() {
        when(scope.isOwner()).thenReturn(false);
        when(scope.currentUserId()).thenReturn(Optional.of(42L));
        when(groups.findByManager_IdOrderByCreatedAtDesc(42L)).thenReturn(List.of());

        assertThat(service.getBreaches(null, null, null, null, null, null, null)).isEmpty();
        verify(breaches, never()).findAll();
    }

    @Test
    void groupFilter_returnsGroupToursOnly() {
        FleetGroup own = group(1L);
        when(groups.findById(1L)).thenReturn(Optional.of(own));
        when(tours.findByGroupId(1L)).thenReturn(List.of(tour(7L, own, 21L)));
        when(positions.findByTourIdOrderByRecordedAtAsc(7L)).thenReturn(List.of(position(101L)));
        when(breaches.findByPositionLogIdIn(List.of(101L)))
                .thenReturn(List.of(breach(1L, 21L, 101L)));

        List<BreachResponse> out = service.getBreaches(
                null, null, null, null, null, null, 1L);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getVehicleId()).isEqualTo(21L);
        verify(breaches, never()).findAll();
    }

    @Test
    void groupFilter_crossGroup_403() {
        FleetGroup other = group(2L);
        when(groups.findById(2L)).thenReturn(Optional.of(other));
        doThrow(new AccessDeniedException("Not your group"))
                .when(scope).requireGroupAccess(other);

        assertThatThrownBy(() -> service.getBreaches(
                null, null, null, null, null, null, 2L))
                .isInstanceOf(AccessDeniedException.class);
    }
}
