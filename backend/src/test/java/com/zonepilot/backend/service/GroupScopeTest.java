package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.FleetGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupScopeTest {

    @Mock
    private com.zonepilot.backend.repository.FleetGroupRepository groups;
    @Mock
    private com.zonepilot.backend.repository.GroupVehicleRepository groupVehicles;
    @Mock
    private com.zonepilot.backend.repository.GroupDriverRepository groupDrivers;
    @Mock
    private com.zonepilot.backend.repository.VehicleRepository vehicles;
    @Mock
    private com.zonepilot.backend.repository.DriverRepository drivers;
    @Mock
    private com.zonepilot.backend.repository.DispatchRouteRepository routes;
    @Mock
    private GroupScope scope;

    @InjectMocks
    private GroupService groupService;

    @Test
    void listGroups_asManager_onlyMine() {
        when(scope.isOwner()).thenReturn(false);
        when(scope.currentUserId()).thenReturn(java.util.Optional.of(5L));
        when(groups.findByManager_IdOrderByCreatedAtDesc(5L)).thenReturn(List.of(new FleetGroup()));

        List<FleetGroup> result = groupService.listGroups();

        assert result.size() == 1;
        verify(groups, never()).findAll();
    }

    @Test
    void listGroups_asOwner_seesAll() {
        when(scope.isOwner()).thenReturn(true);
        when(groups.findAll()).thenReturn(List.of(new FleetGroup(), new FleetGroup()));

        List<FleetGroup> result = groupService.listGroups();

        assert result.size() == 2;
        verify(groups, never()).findByManager_IdOrderByCreatedAtDesc(any());
    }
}
