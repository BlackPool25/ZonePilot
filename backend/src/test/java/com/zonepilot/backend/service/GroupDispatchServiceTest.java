package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.entity.*;
import com.zonepilot.backend.repository.GroupDriverRepository;
import com.zonepilot.backend.repository.GroupVehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupDispatchServiceTest {

    @Mock
    private GroupVehicleRepository groupVehicles;
    @Mock
    private GroupDriverRepository groupDrivers;
    @Mock
    private DriverService driverService;
    @Mock
    private RouteComplianceService compliance;

    private GroupDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        dispatchService = new GroupDispatchService(groupVehicles, groupDrivers, driverService, compliance);
    }

    @Test
    void dispatchGroup_expiredLicenceDriverSkipped_eligibleDispatched() {
        Long groupId = 1L;

        Vehicle v1 = new Vehicle();
        v1.setId(11L);
        v1.setRegistrationNumber("KA01-0011");
        GroupVehicle gv1 = mock(GroupVehicle.class);
        when(gv1.getVehicle()).thenReturn(v1);
        Vehicle v2 = new Vehicle();
        v2.setId(12L);
        v2.setRegistrationNumber("KA01-0012");
        GroupVehicle gv2 = mock(GroupVehicle.class);
        when(gv2.getVehicle()).thenReturn(v2);
        when(groupVehicles.findByGroupId(groupId)).thenReturn(List.of(gv1, gv2));

        Driver expired = new Driver();
        expired.setId(21L);
        expired.setName("Expired Eddie");
        expired.setLicenceExpiry(LocalDate.now().minusDays(1));
        GroupDriver gd1 = mock(GroupDriver.class);
        when(gd1.getDriver()).thenReturn(expired);
        Driver ok = new Driver();
        ok.setId(22L);
        ok.setName("Ready Rita");
        ok.setLicenceExpiry(LocalDate.now().plusYears(1));
        GroupDriver gd2 = mock(GroupDriver.class);
        when(gd2.getDriver()).thenReturn(ok);
        when(groupDrivers.findByGroupId(groupId)).thenReturn(List.of(gd1, gd2));

        doThrow(new com.zonepilot.backend.exception.EligibilityException("licence expired"))
                .when(driverService).checkEligible(eq(expired), any());
        doNothing().when(driverService).checkEligible(eq(ok), any());

        RouteValidationResponse resp = new RouteValidationResponse();
        resp.setDispatchRouteId(99L);
        when(compliance.validateRoute(eq(12L), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(resp);
        DispatchRoute assigned = new DispatchRoute();
        assigned.setId(99L);
        when(driverService.assign(99L, 22L)).thenReturn(assigned);

        GroupDispatchService.DispatchResult result =
                dispatchService.dispatchGroup(groupId, 12.9, 77.5, 13.0, 77.6);

        assertEquals(1, result.dispatched().size());
        assertEquals(99L, result.dispatched().get(0).routeId());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().toLowerCase().contains("licence"));
        verify(compliance, never()).validateRoute(eq(11L), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}
