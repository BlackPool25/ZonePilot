package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock
    private FleetGroupRepository groups;
    @Mock
    private GroupVehicleRepository groupVehicles;
    @Mock
    private GroupDriverRepository groupDrivers;
    @Mock
    private VehicleRepository vehicles;
    @Mock
    private DriverRepository drivers;
    @Mock
    private DispatchRouteRepository routes;
    @Mock
    private com.zonepilot.backend.auth.GroupScope scope;

    private GroupService groupService;

    @BeforeEach
    void setUp() {
        groupService = new GroupService(groups, groupVehicles, groupDrivers, vehicles, drivers, routes, scope);
    }

    @Test
    void groupDetail_showsBusyVehicle() {
        Long groupId = 1L, busyVehicleId = 7L, freeVehicleId = 8L;
        FleetGroup g = new FleetGroup();
        g.setId(groupId);
        g.setName("North");
        when(groups.findById(groupId)).thenReturn(Optional.of(g));

        GroupVehicle busy = mock(GroupVehicle.class);
        Vehicle bv = new Vehicle();
        bv.setId(busyVehicleId);
        bv.setRegistrationNumber("KA01-0007");
        when(busy.getVehicle()).thenReturn(bv);
        GroupVehicle free = mock(GroupVehicle.class);
        Vehicle fv = new Vehicle();
        fv.setId(freeVehicleId);
        fv.setRegistrationNumber("KA01-0008");
        when(free.getVehicle()).thenReturn(fv);
        when(groupVehicles.findByGroupId(groupId)).thenReturn(List.of(busy, free));
        when(groupDrivers.findByGroupId(groupId)).thenReturn(List.of());

        when(routes.existsByVehicleIdAndAssignmentStatusIn(eq(busyVehicleId), anyList())).thenReturn(true);
        when(routes.existsByVehicleIdAndAssignmentStatusIn(eq(freeVehicleId), anyList())).thenReturn(false);

        GroupService.GroupDetail detail = groupService.groupDetail(groupId);

        assertEquals(2, detail.vehicles().size());
        assertTrue(detail.vehicles().stream()
                .filter(v -> v.id().equals(busyVehicleId)).findFirst().orElseThrow().busy());
        assertFalse(detail.vehicles().stream()
                .filter(v -> v.id().equals(freeVehicleId)).findFirst().orElseThrow().busy());
    }

    @Test
    void addDuplicateVehicle_rejected() {
        when(groups.findById(1L)).thenReturn(Optional.of(new FleetGroup()));
        Vehicle v = new Vehicle();
        v.setId(9L);
        when(vehicles.findById(9L)).thenReturn(Optional.of(v));
        when(groupVehicles.existsByGroupIdAndVehicleId(1L, 9L)).thenReturn(true);
        assertThrows(Exception.class, () -> groupService.addVehicle(1L, 9L));
    }
}
