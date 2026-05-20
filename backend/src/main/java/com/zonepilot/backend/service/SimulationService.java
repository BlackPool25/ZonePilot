package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.dto.response.SimulationStateResponse;
import com.zonepilot.backend.dto.response.SimulationTickResponse;
import com.zonepilot.backend.entity.SimulationPath;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.SimulationException;
import com.zonepilot.backend.repository.SimulationPathRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    // Probability thresholds
    private static final double GPS_GLITCH_PROB   = 0.10; // 10% chance per tick
    private static final double WRONG_TURN_PROB   = 0.05; // 5% chance per tick
    private static final int    WRONG_TURN_TICKS  = 3;    // consecutive wrong-turn ticks
    // ~50m offset in degrees (≈0.00045° at Bangalore latitude)
    private static final double GLITCH_OFFSET_DEG = 0.00045;

    private final SimulationPathRepository simulationPathRepository;
    private final VehicleRepository vehicleRepository;
    private final PositionTrackingService positionTrackingService;
    private final GeometryFactory geometryFactory;
    private final Random random = new Random();

    // Tracks remaining wrong-turn ticks per path id
    private final ConcurrentHashMap<Long, Integer> wrongTurnCounters = new ConcurrentHashMap<>();
    // Tracks the wrong-turn offset direction per path id [deltaLat, deltaLng]
    private final ConcurrentHashMap<Long, double[]> wrongTurnOffsets = new ConcurrentHashMap<>();

    public SimulationService(SimulationPathRepository simulationPathRepository,
                             VehicleRepository vehicleRepository,
                             PositionTrackingService positionTrackingService) {
        this.simulationPathRepository = simulationPathRepository;
        this.vehicleRepository = vehicleRepository;
        this.positionTrackingService = positionTrackingService;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
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
                    wrongTurnCounters.remove(path.getId());
                    wrongTurnOffsets.remove(path.getId());
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
                wrongTurnCounters.remove(path.getId());
                wrongTurnOffsets.remove(path.getId());

                SimulationTickResponse.TickVehicleResult result = new SimulationTickResponse.TickVehicleResult();
                result.setVehicleId(path.getVehicle().getId());
                result.setRegistrationNumber(path.getVehicle().getRegistrationNumber());
                result.setStatus("COMPLETED");
                result.setBreachDetected(false);
                vehicleResults.add(result);
                continue;
            }

            Coordinate trueWaypoint = extractWaypoint(path.getWaypoints(), nextStep + 1);
            if (trueWaypoint == null) {
                log.warn("Could not extract waypoint at step {} for path {}", nextStep, path.getId());
                continue;
            }

            // Compute heading from previous waypoint (or use 0 if first step)
            Short headingDeg = computeHeading(path.getWaypoints(), nextStep);

            // Determine reported position (may be glitched or wrong-turn)
            double[] reportedPos = applyDeviationInjector(
                    path.getId(), trueWaypoint.getY(), trueWaypoint.getX(), headingDeg);

            double reportedLat = reportedPos[0];
            double reportedLng = reportedPos[1];
            Short reportedHeading = (short) Math.round(reportedPos[2]);

            PositionRecordResponse positionResult = positionTrackingService.recordPosition(
                    path.getVehicle().getId(),
                    reportedLat,
                    reportedLng,
                    null,
                    null,
                    reportedHeading,
                    PositionSource.SIMULATED);

            simulationPathRepository.incrementStep(path.getId());

            SimulationTickResponse.TickVehicleResult result = new SimulationTickResponse.TickVehicleResult();
            result.setVehicleId(path.getVehicle().getId());
            result.setRegistrationNumber(path.getVehicle().getRegistrationNumber());
            result.setLatitude(reportedLat);
            result.setLongitude(reportedLng);
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
        wrongTurnCounters.clear();
        wrongTurnOffsets.clear();
    }

    // ── Deviation Injector ────────────────────────────────────────────────────

    /**
     * Applies GPS glitch or wrong-turn deviation to the true waypoint.
     * Returns [lat, lng, headingDeg].
     *
     * GPS Glitch (10%): jumps ~50m in a random direction and flips heading 180°.
     *   The next tick will return to the true path (no persistent state needed).
     *
     * Wrong Turn (5%): drifts the vehicle off-route for WRONG_TURN_TICKS consecutive
     *   ticks by applying a fixed lateral offset, simulating a real wrong turn that
     *   the reroute pipeline should detect.
     */
    private double[] applyDeviationInjector(Long pathId, double lat, double lng, short heading) {
        // If currently in a wrong-turn sequence, continue it
        int wrongTurnsLeft = wrongTurnCounters.getOrDefault(pathId, 0);
        if (wrongTurnsLeft > 0) {
            double[] offset = wrongTurnOffsets.get(pathId);
            wrongTurnCounters.put(pathId, wrongTurnsLeft - 1);
            if (wrongTurnsLeft == 1) {
                wrongTurnOffsets.remove(pathId);
            }
            double devLat = lat + offset[0];
            double devLng = lng + offset[1];
            // Heading stays the same (vehicle is driving the wrong way, not backwards)
            return new double[]{devLat, devLng, heading};
        }

        double roll = random.nextDouble();

        if (roll < GPS_GLITCH_PROB) {
            // GPS glitch: random ~50m offset + flipped heading
            double angle = random.nextDouble() * 2 * Math.PI;
            double devLat = lat + GLITCH_OFFSET_DEG * Math.sin(angle);
            double devLng = lng + GLITCH_OFFSET_DEG * Math.cos(angle);
            double flippedHeading = (heading + 180.0) % 360.0;
            log.debug("GPS glitch injected for path {} at ({},{})", pathId, lat, lng);
            return new double[]{devLat, devLng, flippedHeading};

        } else if (roll < GPS_GLITCH_PROB + WRONG_TURN_PROB) {
            // Wrong turn: pick a perpendicular offset direction and persist for WRONG_TURN_TICKS
            // Perpendicular to heading = heading + 90°
            double perpRad = Math.toRadians((heading + 90.0) % 360.0);
            // Accumulate ~60m per tick in the wrong direction
            double offsetDeg = GLITCH_OFFSET_DEG * 2;
            double[] offset = {offsetDeg * Math.sin(perpRad), offsetDeg * Math.cos(perpRad)};
            wrongTurnCounters.put(pathId, WRONG_TURN_TICKS - 1);
            wrongTurnOffsets.put(pathId, offset);
            double devLat = lat + offset[0];
            double devLng = lng + offset[1];
            log.debug("Wrong turn injected for path {} at ({},{})", pathId, lat, lng);
            return new double[]{devLat, devLng, heading};
        }

        return new double[]{lat, lng, heading};
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String normalizeScenarioName(String name) {
        String normalized = name.trim().toUpperCase();
        return switch (normalized) {
            case "A", "SCENARIO_A", "SCENARIO A" -> "SCENARIO_A";
            case "B", "SCENARIO_B", "SCENARIO B" -> "SCENARIO_B";
            case "C", "SCENARIO_C", "SCENARIO C" -> "SCENARIO_C";
            default -> name;
        };
    }

    private Coordinate extractWaypoint(LineString waypoints, int stepIndex) {
        if (waypoints == null || stepIndex < 1 || stepIndex > waypoints.getNumPoints()) {
            return null;
        }
        return waypoints.getCoordinateN(stepIndex - 1);
    }

    /**
     * Computes heading in degrees from the previous waypoint to the current one.
     * Uses atan2 on the coordinate delta. Returns 0 if at the first step.
     */
    private Short computeHeading(LineString waypoints, int currentStep) {
        if (currentStep < 2 || waypoints.getNumPoints() < 2) return 0;
        Coordinate prev = waypoints.getCoordinateN(currentStep - 2);
        Coordinate curr = waypoints.getCoordinateN(currentStep - 1);
        double dLng = curr.getX() - prev.getX();
        double dLat = curr.getY() - prev.getY();
        double bearing = Math.toDegrees(Math.atan2(dLng, dLat));
        return (short) ((bearing + 360) % 360);
    }
}
