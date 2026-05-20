package com.zonepilot.backend.seed;

import com.zonepilot.backend.entity.SimulationPath;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.repository.RoadNetworkRepository;
import com.zonepilot.backend.repository.SimulationPathRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.service.RoutingService;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("dev")
public class SimulationDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationDataSeeder.class);

    // Tick interval in meters — ~30m matches a GPS ping every few seconds in city traffic
    private static final double TICK_INTERVAL_M = 30.0;

    // Fallback scenario coordinates (used when road network is not loaded)
    private static final double[][] SCENARIO_A_COORDS = {
        {77.6389, 12.9116}, {77.6380, 12.9130}, {77.6370, 12.9150}, {77.6350, 12.9180},
        {77.6330, 12.9210}, {77.6310, 12.9240}, {77.6290, 12.9270}, {77.6270, 12.9300},
        {77.6250, 12.9330}, {77.6230, 12.9360}, {77.6210, 12.9390}, {77.6190, 12.9420},
        {77.6170, 12.9450}, {77.6150, 12.9480}, {77.6130, 12.9510}, {77.6110, 12.9540}
    };
    private static final double[][] SCENARIO_B_COORDS = {
        {77.5535, 13.0241}, {77.5560, 13.0200}, {77.5590, 13.0160}, {77.5620, 13.0120},
        {77.5650, 13.0080}, {77.5680, 13.0040}, {77.6170, 12.9760}, {77.6180, 12.9750},
        {77.6190, 12.9740}, {77.6200, 12.9730}, {77.6210, 12.9725}, {77.6220, 12.9720},
        {77.6230, 12.9715}, {77.6240, 12.9710}, {77.6250, 12.9700}, {77.6260, 12.9690}
    };
    private static final double[][] SCENARIO_C_COORDS = {
        {77.6770, 12.8399}, {77.6740, 12.8430}, {77.6710, 12.8470}, {77.5630, 12.9800},
        {77.5640, 12.9790}, {77.5650, 12.9780}, {77.5660, 12.9770}, {77.5670, 12.9760},
        {77.6200, 12.9370}, {77.6210, 12.9360}, {77.6220, 12.9350}, {77.6230, 12.9340},
        {77.6240, 12.9330}, {77.6250, 12.9320}, {77.6260, 12.9310}, {77.6270, 12.9300}
    };

    private final SimulationPathRepository simulationPathRepository;
    private final VehicleRepository vehicleRepository;
    private final RoadNetworkRepository roadNetworkRepository;
    private final RoutingService routingService;
    private final GeometryFactory geometryFactory;

    public SimulationDataSeeder(SimulationPathRepository simulationPathRepository,
                                VehicleRepository vehicleRepository,
                                RoadNetworkRepository roadNetworkRepository,
                                RoutingService routingService) {
        this.simulationPathRepository = simulationPathRepository;
        this.vehicleRepository = vehicleRepository;
        this.roadNetworkRepository = roadNetworkRepository;
        this.routingService = routingService;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    @Override
    public void run(String... args) {
        if (simulationPathRepository.count() > 0) {
            log.info("Simulation paths already exist, skipping seed");
            return;
        }

        log.info("Seeding simulation paths...");

        Vehicle vehicleA = vehicleRepository.findById(4L).orElse(null);
        Vehicle vehicleB = vehicleRepository.findById(7L).orElse(null);
        Vehicle vehicleC = vehicleRepository.findById(5L).orElse(null);

        if (vehicleA == null || vehicleB == null || vehicleC == null) {
            log.warn("Vehicles not found, skipping simulation seed");
            return;
        }

        seedScenario("SCENARIO_A", vehicleA, SCENARIO_A_COORDS);
        seedScenario("SCENARIO_B", vehicleB, SCENARIO_B_COORDS);
        seedScenario("SCENARIO_C", vehicleC, SCENARIO_C_COORDS);

        log.info("Seeded 3 simulation paths: A (compliant), B (time violation), C (no-entry deviation)");
    }

    private void seedScenario(String name, Vehicle vehicle, double[][] fallbackCoords) {
        LineString waypoints = buildPgRoutingPath(fallbackCoords);
        SimulationPath path = new SimulationPath();
        path.setVehicle(vehicle);
        path.setScenarioName(name);
        path.setWaypoints(waypoints);
        path.setCurrentStepIndex(0);
        path.setTotalSteps(waypoints.getNumPoints());
        path.setIsActive(false);
        simulationPathRepository.save(path);
        log.info("Seeded {} with {} waypoints (pgRouting={})",
                name, waypoints.getNumPoints(), waypoints.getNumPoints() > fallbackCoords.length);
    }

    /**
     * Attempts to build a pgRouting-backed, 30m-interpolated path between the
     * first and last fallback coordinate pair. Falls back to straight-line
     * coordinates if the road network is not loaded.
     */
    private LineString buildPgRoutingPath(double[][] fallbackCoords) {
        try {
            double originLng = fallbackCoords[0][0];
            double originLat = fallbackCoords[0][1];
            double destLng   = fallbackCoords[fallbackCoords.length - 1][0];
            double destLat   = fallbackCoords[fallbackCoords.length - 1][1];

            Long sourceNode = routingService.findNearestNodeOrNull(originLat, originLng);
            Long targetNode = routingService.findNearestNodeOrNull(destLat, destLng);

            if (sourceNode == null || targetNode == null) {
                log.info("Road network not loaded — using fallback straight-line waypoints");
                return buildFallback(fallbackCoords);
            }

            LineString routeGeom = routingService.computeRoute(sourceNode, targetNode);
            List<double[]> ticks = roadNetworkRepository.interpolateWaypoints(
                    routeGeom.toText(), TICK_INTERVAL_M);

            if (ticks.size() < 2) {
                log.warn("Interpolation returned < 2 points — using fallback");
                return buildFallback(fallbackCoords);
            }

            Coordinate[] coords = ticks.stream()
                    .map(p -> new Coordinate(p[1], p[0])) // lng, lat
                    .toArray(Coordinate[]::new);
            LineString ls = geometryFactory.createLineString(coords);
            ls.setSRID(4326);
            return ls;

        } catch (Exception e) {
            log.warn("pgRouting path generation failed ({}), using fallback", e.getMessage());
            return buildFallback(fallbackCoords);
        }
    }

    private LineString buildFallback(double[][] coords) {
        Coordinate[] c = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) {
            c[i] = new Coordinate(coords[i][0], coords[i][1]);
        }
        LineString ls = geometryFactory.createLineString(c);
        ls.setSRID(4326);
        return ls;
    }
}
