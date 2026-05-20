package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.dto.response.SimulationStateResponse;
import com.zonepilot.backend.dto.response.SimulationTickResponse;
import com.zonepilot.backend.entity.SimulationPath;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.SimulationException;
import com.zonepilot.backend.repository.SimulationPathRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SimulationPathRepository simulationPathRepository;
    private final VehicleRepository vehicleRepository;
    private final PositionTrackingService positionTrackingService;
    private final GeometryFactory geometryFactory;
    private final WKTReader wktReader;

    public SimulationService(SimulationPathRepository simulationPathRepository,
                             VehicleRepository vehicleRepository,
                             PositionTrackingService positionTrackingService) {
        this.simulationPathRepository = simulationPathRepository;
        this.vehicleRepository = vehicleRepository;
        this.positionTrackingService = positionTrackingService;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.wktReader = new WKTReader(geometryFactory);
    }

    @Transactional
    public void startScenarios(List<String> scenarioNames) {
        for (String name : scenarioNames) {
            String scenarioName = normalizeScenarioName(name);
            List<SimulationPath> paths = simulationPathRepository.findAll();
            boolean found = false;
            for (SimulationPath path : paths) {
                if (path.getScenarioName().equals(scenarioName)) {
                    path.setIsActive(true);
                    path.setCurrentStepIndex(0);
                    simulationPathRepository.save(path);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new SimulationException("Scenario not found: " + scenarioName);
            }
        }
    }

    @Transactional
    public SimulationTickResponse tick() {
        List<SimulationPath> activePaths = simulationPathRepository.findAllActive();

        if (activePaths.isEmpty()) {
            SimulationTickResponse emptyResponse = new SimulationTickResponse();
            emptyResponse.setTickNumber(0);
            emptyResponse.setVehicles(new ArrayList<>());
            return emptyResponse;
        }

        int tickNumber = activePaths.stream()
                .mapToInt(SimulationPath::getCurrentStepIndex)
                .max()
                .orElse(0) + 1;

        List<SimulationTickResponse.TickVehicleResult> vehicleResults = new ArrayList<>();

        for (SimulationPath path : activePaths) {
            int nextStep = path.getCurrentStepIndex() + 1;

            if (nextStep > path.getTotalSteps()) {
                path.setIsActive(false);
                simulationPathRepository.save(path);

                SimulationTickResponse.TickVehicleResult result = new SimulationTickResponse.TickVehicleResult();
                result.setVehicleId(path.getVehicle().getId());
                result.setRegistrationNumber(path.getVehicle().getRegistrationNumber());
                result.setStatus("COMPLETED");
                result.setBreachDetected(false);
                vehicleResults.add(result);
                continue;
            }

            Coordinate waypoint = extractWaypoint(path.getWaypoints(), nextStep + 1);
            if (waypoint == null) {
                log.warn("Could not extract waypoint at step {} for path {}", nextStep, path.getId());
                continue;
            }

            PositionRecordResponse positionResult = positionTrackingService.recordPosition(
                    path.getVehicle().getId(),
                    waypoint.getY(),
                    waypoint.getX(),
                    null,
                    null,
                    null,
                    PositionSource.SIMULATED);

            simulationPathRepository.incrementStep(path.getId());

            SimulationTickResponse.TickVehicleResult result = new SimulationTickResponse.TickVehicleResult();
            result.setVehicleId(path.getVehicle().getId());
            result.setRegistrationNumber(path.getVehicle().getRegistrationNumber());
            result.setLatitude(waypoint.getY());
            result.setLongitude(waypoint.getX());
            result.setBreachDetected(positionResult.getBreachDetected());
            result.setBreaches(positionResult.getBreaches());
            result.setStatus("MOVING");
            vehicleResults.add(result);
        }

        SimulationTickResponse response = new SimulationTickResponse();
        response.setTickNumber(tickNumber);
        response.setVehicles(vehicleResults);
        return response;
    }

    public List<SimulationStateResponse> getState() {
        List<SimulationPath> allPaths = simulationPathRepository.findAll();
        List<SimulationStateResponse> states = new ArrayList<>();

        for (SimulationPath path : allPaths) {
            SimulationStateResponse state = new SimulationStateResponse();
            state.setPathId(path.getId());
            state.setVehicleId(path.getVehicle().getId());
            state.setRegistrationNumber(path.getVehicle().getRegistrationNumber());
            state.setScenarioName(path.getScenarioName());
            state.setCurrentStep(path.getCurrentStepIndex());
            state.setTotalSteps(path.getTotalSteps());
            state.setIsActive(path.getIsActive());

            Coordinate currentWaypoint = extractWaypoint(path.getWaypoints(), path.getCurrentStepIndex() + 1);
            if (currentWaypoint != null) {
                state.setLatitude(currentWaypoint.getY());
                state.setLongitude(currentWaypoint.getX());
            }

            states.add(state);
        }

        return states;
    }

    @Transactional
    public void reset() {
        simulationPathRepository.resetAll();
    }

    private String normalizeScenarioName(String name) {
        String normalized = name.trim().toUpperCase();
        if (normalized.equals("A") || normalized.equals("SCENARIO_A") || normalized.equals("SCENARIO A")) {
            return "SCENARIO_A";
        } else if (normalized.equals("B") || normalized.equals("SCENARIO_B") || normalized.equals("SCENARIO B")) {
            return "SCENARIO_B";
        } else if (normalized.equals("C") || normalized.equals("SCENARIO_C") || normalized.equals("SCENARIO C")) {
            return "SCENARIO_C";
        }
        return name;
    }

    private Coordinate extractWaypoint(LineString waypoints, int stepIndex) {
        if (waypoints == null || stepIndex < 1 || stepIndex > waypoints.getNumPoints()) {
            return null;
        }
        return waypoints.getCoordinateN(stepIndex - 1);
    }
}
