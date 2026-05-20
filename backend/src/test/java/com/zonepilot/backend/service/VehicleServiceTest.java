package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.request.CreateVehicleRequest;
import com.zonepilot.backend.dto.response.VehicleResponse;
import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DepotRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private DepotRepository depotRepository;
    @Mock private ZoneRestrictionRepository zoneRestrictionRepository;
    @Mock private ZoneRestrictionRuleRepository zoneRestrictionRuleRepository;

    private VehicleService vehicleService;

    @BeforeEach
    void setUp() {
        vehicleService = new VehicleService(
                vehicleRepository, depotRepository,
                zoneRestrictionRepository, zoneRestrictionRuleRepository);
    }

    @Test
    void createVehicle_withValidRequest_returnsResponse() {
        Depot depot = new Depot();
        depot.setId(1L);
        depot.setName("HSR Depot");

        Vehicle saved = new Vehicle();
        saved.setId(10L);
        saved.setRegistrationNumber("KA01-LCV-0099");
        saved.setVehicleClass(VehicleClass.LCV);
        saved.setOwnerName("Test Owner");
        saved.setDepot(depot);
        saved.setIsActive(true);

        when(vehicleRepository.existsByRegistrationNumber("KA01-LCV-0099")).thenReturn(false);
        when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));
        when(vehicleRepository.save(any(Vehicle.class))).thenReturn(saved);

        CreateVehicleRequest request = new CreateVehicleRequest();
        request.setRegistrationNumber("KA01-LCV-0099");
        request.setVehicleClass("LCV");
        request.setOwnerName("Test Owner");
        request.setDepotId(1L);

        VehicleResponse response = vehicleService.createVehicle(request);

        assertEquals(10L, response.getId());
        assertEquals("KA01-LCV-0099", response.getRegistrationNumber());
        assertEquals(VehicleClass.LCV, response.getVehicleClass());
        verify(vehicleRepository).save(any(Vehicle.class));
    }

    @Test
    void createVehicle_withDuplicateRegistration_throwsValidationException() {
        when(vehicleRepository.existsByRegistrationNumber("KA01-LCV-0099")).thenReturn(true);

        CreateVehicleRequest request = new CreateVehicleRequest();
        request.setRegistrationNumber("KA01-LCV-0099");
        request.setVehicleClass("LCV");
        request.setOwnerName("Test Owner");
        request.setDepotId(1L);

        assertThrows(ValidationException.class, () -> vehicleService.createVehicle(request));
        verify(vehicleRepository, never()).save(any());
    }

    @Test
    void createVehicle_withNonExistentDepot_throwsResourceNotFoundException() {
        when(vehicleRepository.existsByRegistrationNumber(anyString())).thenReturn(false);
        when(depotRepository.findById(99L)).thenReturn(Optional.empty());

        CreateVehicleRequest request = new CreateVehicleRequest();
        request.setRegistrationNumber("KA01-LCV-0099");
        request.setVehicleClass("LCV");
        request.setOwnerName("Test Owner");
        request.setDepotId(99L);

        assertThrows(ResourceNotFoundException.class, () -> vehicleService.createVehicle(request));
    }

    @Test
    void createVehicle_withInvalidVehicleClass_throwsIllegalArgumentException() {
        Depot depot = new Depot();
        depot.setId(1L);
        depot.setName("HSR Depot");

        when(vehicleRepository.existsByRegistrationNumber(anyString())).thenReturn(false);
        when(depotRepository.findById(1L)).thenReturn(Optional.of(depot));

        CreateVehicleRequest request = new CreateVehicleRequest();
        request.setRegistrationNumber("KA01-LCV-0099");
        request.setVehicleClass("INVALID_CLASS");
        request.setOwnerName("Test Owner");
        request.setDepotId(1L);

        assertThrows(IllegalArgumentException.class, () -> vehicleService.createVehicle(request));
    }

    @Test
    void getVehicleById_withNonExistentId_throwsResourceNotFoundException() {
        when(vehicleRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> vehicleService.getVehicleById(999L));
    }

    @Test
    void updateVehicle_withDuplicateRegistrationNumber_throwsValidationException() {
        Depot depot = new Depot();
        depot.setId(1L);
        depot.setName("HSR Depot");

        Vehicle existing = new Vehicle();
        existing.setId(1L);
        existing.setRegistrationNumber("KA01-LCV-0001");
        existing.setVehicleClass(VehicleClass.LCV);
        existing.setOwnerName("Owner");
        existing.setDepot(depot);
        existing.setIsActive(true);

        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(vehicleRepository.existsByRegistrationNumber("KA01-LCV-0002")).thenReturn(true);

        CreateVehicleRequest request = new CreateVehicleRequest();
        request.setRegistrationNumber("KA01-LCV-0002"); // different from current, but already taken
        request.setVehicleClass("LCV");
        request.setOwnerName("Owner");
        request.setDepotId(1L);

        assertThrows(ValidationException.class, () -> vehicleService.updateVehicle(1L, request));
        verify(vehicleRepository, never()).save(any());
    }
}
