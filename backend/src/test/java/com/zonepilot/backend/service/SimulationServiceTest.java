package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.SimulationTickResponse;
import com.zonepilot.backend.entity.SimulationPath;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.SimulationException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.SimulationPathRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationServiceTest {

    @Mock private SimulationPathRepository simulationPathRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private PositionTrackingService positionTrackingService;
    @Mock private DispatchRouteRepository dispatchRouteRepository;

    private SimulationService simulationService;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        simulationService = new SimulationService(
                simulationPathRepository, vehicleRepository, positionTrackingService, dispatchRouteRepository);
    }

    @Test
    void tick_withNoActivePaths_returnsEmptyResponse() {
        when(simulationPathRepository.findAllActive()).thenReturn(Collections.emptyList());

        SimulationTickResponse response = simulationService.tick();

        assertNotNull(response);
        assertTrue(response.getVehicles().isEmpty());
        assertEquals(0, response.getTickNumber());
    }

    @Test
    void startScenarios_withUnknownScenario_throwsSimulationException() {
        when(simulationPathRepository.findAll()).thenReturn(Collections.emptyList());
        assertThrows(SimulationException.class,
                () -> simulationService.startScenarios(List.of("SCENARIO_X")));
    }

    @Test
    void startScenarios_withKnownScenario_activatesPath() {
        SimulationPath path = buildPath("SCENARIO_A", false, 0, 5);
        when(simulationPathRepository.findAll()).thenReturn(List.of(path));
        when(simulationPathRepository.save(any())).thenReturn(path);

        simulationService.startScenarios(List.of("A"));

        assertTrue(path.getIsActive());
        assertEquals(0, path.getCurrentStepIndex());
        verify(simulationPathRepository).save(path);
    }

    @Test
    void startScenarios_withAlreadyActivePath_resetsAndActivates() {
        // B4 fix: should find active paths too, not just inactive
        SimulationPath path = buildPath("SCENARIO_B", true, 7, 15);
        when(simulationPathRepository.findAll()).thenReturn(List.of(path));
        when(simulationPathRepository.save(any())).thenReturn(path);

        simulationService.startScenarios(List.of("B"));

        assertTrue(path.getIsActive());
        assertEquals(0, path.getCurrentStepIndex()); // reset to 0
    }

    @Test
    void tick_whenPathExhausted_marksPathInactive() {
        // B5 fix: last step should be visited before marking complete
        // totalSteps = 3, currentStepIndex = 2 → nextStep = 3 > 3 is false, so step 3 executes
        // currentStepIndex = 3 → nextStep = 4 > 3 is true → mark complete
        Vehicle vehicle = buildVehicle(1L);
        SimulationPath path = buildPath("SCENARIO_A", true, 3, 3); // exhausted: nextStep=4 > 3
        path.setVehicle(vehicle);

        when(simulationPathRepository.findAllActive()).thenReturn(List.of(path));
        when(simulationPathRepository.save(any())).thenReturn(path);

        SimulationTickResponse response = simulationService.tick();

        assertFalse(path.getIsActive());
        assertEquals(1, response.getVehicles().size());
        assertEquals("COMPLETED", response.getVehicles().get(0).getStatus());
    }

    @Test
    void tick_whenPathHasRemainingSteps_recordsPosition() {
        Vehicle vehicle = buildVehicle(1L);
        // 3 waypoints, at step 0 → nextStep=1, which is valid (1 <= 3)
        SimulationPath path = buildPath("SCENARIO_A", true, 0, 3);
        path.setVehicle(vehicle);

        com.zonepilot.backend.dto.response.PositionRecordResponse posResponse =
                new com.zonepilot.backend.dto.response.PositionRecordResponse();
        posResponse.setBreachDetected(false);

        when(simulationPathRepository.findAllActive()).thenReturn(List.of(path));
        when(positionTrackingService.recordPosition(any(), anyDouble(), anyDouble(),
                any(), any(), any(), any())).thenReturn(posResponse);

        SimulationTickResponse response = simulationService.tick();

        assertEquals(1, response.getVehicles().size());
        assertEquals("MOVING", response.getVehicles().get(0).getStatus());
        assertFalse(response.getVehicles().get(0).getBreachDetected());
        verify(simulationPathRepository).incrementStep(path.getId());
    }

    @Test
    void tick_whenOneVehicleFixFailsOutOfBounds_continuesWithRemainingVehicles() {
        // Tick guard: one bad fix (e.g. deviation pushed it outside the bbox)
        // must not abort the whole tick — validation applies per-fix, not per-tick.
        Vehicle v1 = buildVehicle(1L);
        Vehicle v2 = buildVehicle(2L);
        SimulationPath p1 = buildPath("SCENARIO_A", true, 0, 3);
        p1.setVehicle(v1);
        p1.setId(1L);
        SimulationPath p2 = buildPath("SCENARIO_A", true, 0, 3);
        p2.setVehicle(v2);
        p2.setId(2L);

        when(simulationPathRepository.findAllActive()).thenReturn(List.of(p1, p2));
        when(positionTrackingService.recordPosition(eq(1L), anyDouble(), anyDouble(),
                any(), any(), any(), any()))
                .thenThrow(new ValidationException("Coordinates (0.0, 0.0) are outside Bangalore bounding box"));
        com.zonepilot.backend.dto.response.PositionRecordResponse ok =
                new com.zonepilot.backend.dto.response.PositionRecordResponse();
        ok.setBreachDetected(false);
        when(positionTrackingService.recordPosition(eq(2L), anyDouble(), anyDouble(),
                any(), any(), any(), any())).thenReturn(ok);

        SimulationTickResponse response = simulationService.tick();

        assertEquals(2, response.getVehicles().size());
        assertEquals("SKIPPED", response.getVehicles().get(0).getStatus());
        assertEquals("MOVING", response.getVehicles().get(1).getStatus());
    }

    @Test
    void tick_whenDuplicateKey_toleratesAndContinues() {
        // Tick guard: QoS1 redelivery of an already-stored fix is benign — skip, don't abort.
        Vehicle v1 = buildVehicle(1L);
        Vehicle v2 = buildVehicle(2L);
        SimulationPath p1 = buildPath("SCENARIO_A", true, 0, 3);
        p1.setVehicle(v1);
        p1.setId(1L);
        SimulationPath p2 = buildPath("SCENARIO_A", true, 0, 3);
        p2.setVehicle(v2);
        p2.setId(2L);

        when(simulationPathRepository.findAllActive()).thenReturn(List.of(p1, p2));
        when(positionTrackingService.recordPosition(eq(1L), anyDouble(), anyDouble(),
                any(), any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));
        com.zonepilot.backend.dto.response.PositionRecordResponse ok =
                new com.zonepilot.backend.dto.response.PositionRecordResponse();
        ok.setBreachDetected(false);
        when(positionTrackingService.recordPosition(eq(2L), anyDouble(), anyDouble(),
                any(), any(), any(), any())).thenReturn(ok);

        SimulationTickResponse response = simulationService.tick();

        assertEquals(2, response.getVehicles().size());
        assertEquals("SKIPPED", response.getVehicles().get(0).getStatus());
        assertEquals("MOVING", response.getVehicles().get(1).getStatus());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SimulationPath buildPath(String name, boolean active, int currentStep, int totalSteps) {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(77.6389, 12.9116),
                new Coordinate(77.6380, 12.9130),
                new Coordinate(77.6370, 12.9150),
        };
        LineString waypoints = gf.createLineString(coords);

        SimulationPath path = new SimulationPath();
        path.setId(1L);
        path.setScenarioName(name);
        path.setIsActive(active);
        path.setCurrentStepIndex(currentStep);
        path.setTotalSteps(totalSteps);
        path.setWaypoints(waypoints);
        return path;
    }

    private Vehicle buildVehicle(Long id) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setRegistrationNumber("KA01-LCV-000" + id);
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("Test Owner");
        v.setIsActive(true);
        return v;
    }
}
