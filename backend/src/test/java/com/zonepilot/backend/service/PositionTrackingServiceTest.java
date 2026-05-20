package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PositionTrackingServiceTest {

    @Mock private VehicleRepository vehicleRepository;
    @Mock private VehiclePositionLogRepository positionLogRepository;
    @Mock private ZoneBreachLogRepository breachLogRepository;
    @Mock private BreachService breachService;

    private PositionTrackingService service;

    @BeforeEach
    void setUp() {
        service = new PositionTrackingService(
                vehicleRepository, positionLogRepository, breachLogRepository, breachService);
    }

    @Test
    void recordPosition_withNonExistentVehicle_throwsResourceNotFoundException() {
        when(vehicleRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.recordPosition(999L, 12.9, 77.6, null, null, null, PositionSource.LIVE));
    }

    @Test
    void recordPosition_withInactiveVehicle_throwsValidationException() {
        Vehicle inactive = buildVehicle(1L, false);
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(inactive));

        assertThrows(ValidationException.class,
                () -> service.recordPosition(1L, 12.9, 77.6, null, null, null, PositionSource.LIVE));
        verify(positionLogRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
        "12.7, 77.6",   // lat below Bangalore
        "13.3, 77.6",   // lat above Bangalore
        "12.9, 77.3",   // lng below Bangalore
        "12.9, 77.9",   // lng above Bangalore
    })
    void recordPosition_withOutOfRangeCoordinates_throwsValidationException(double lat, double lng) {
        // Coordinate validation happens before vehicle lookup
        assertThrows(ValidationException.class,
                () -> service.recordPosition(1L, lat, lng, null, null, null, PositionSource.LIVE));
        verify(vehicleRepository, never()).findById(any());
    }

    @ParameterizedTest
    @CsvSource({
        "12.8, 77.4",   // min boundary
        "13.2, 77.8",   // max boundary
        "12.9116, 77.6389",  // HSR Layout depot
        "13.0241, 77.5535",  // Yeshwantpur depot
    })
    void recordPosition_withValidBangaloreCoordinates_proceedsToVehicleLookup(double lat, double lng) {
        // Coordinate validation passes; vehicle lookup is attempted
        when(vehicleRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.recordPosition(1L, lat, lng, null, null, null, PositionSource.LIVE));
        verify(vehicleRepository).findById(1L);
    }

    private Vehicle buildVehicle(Long id, boolean active) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNumber("KA01-LCV-000" + id);
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Test Owner");
        v.setIsActive(active);
        return v;
    }
}
