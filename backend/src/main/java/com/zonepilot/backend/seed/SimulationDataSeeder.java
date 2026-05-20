package com.zonepilot.backend.seed;

import com.zonepilot.backend.entity.SimulationPath;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.repository.SimulationPathRepository;
import com.zonepilot.backend.repository.VehicleRepository;
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

    private final SimulationPathRepository simulationPathRepository;
    private final VehicleRepository vehicleRepository;
    private final GeometryFactory geometryFactory;

    public SimulationDataSeeder(SimulationPathRepository simulationPathRepository,
                                 VehicleRepository vehicleRepository) {
        this.simulationPathRepository = simulationPathRepository;
        this.vehicleRepository = vehicleRepository;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    @Override
    public void run(String... args) {
        if (simulationPathRepository.count() > 0) {
            log.info("Simulation paths already exist, skipping seed");
            return;
        }

        log.info("Seeding simulation paths...");

        // Scenario A: Vehicle ID 4 (LCV, HSR Layout depot) — compliant route
        // Scenario B: Vehicle ID 7 (HCV, Yeshwantpur depot) — time window violation on MG Road
        // Scenario C: Vehicle ID 5 (LCV, Yeshwantpur depot) — no-entry zone deviation at Majestic
        Vehicle vehicle1 = vehicleRepository.findById(4L).orElse(null);
        Vehicle vehicle4 = vehicleRepository.findById(7L).orElse(null);
        Vehicle vehicle7 = vehicleRepository.findById(5L).orElse(null);

        if (vehicle1 == null || vehicle4 == null || vehicle7 == null) {
            log.warn("Vehicles not found, skipping simulation seed");
            return;
        }

        LineString scenarioA = buildScenarioA();
        LineString scenarioB = buildScenarioB();
        LineString scenarioC = buildScenarioC();

        SimulationPath pathA = new SimulationPath();
        pathA.setVehicle(vehicle1);
        pathA.setScenarioName("SCENARIO_A");
        pathA.setWaypoints(scenarioA);
        pathA.setCurrentStepIndex(0);
        pathA.setTotalSteps(scenarioA.getNumPoints());
        pathA.setIsActive(false);
        simulationPathRepository.save(pathA);

        SimulationPath pathB = new SimulationPath();
        pathB.setVehicle(vehicle4);
        pathB.setScenarioName("SCENARIO_B");
        pathB.setWaypoints(scenarioB);
        pathB.setCurrentStepIndex(0);
        pathB.setTotalSteps(scenarioB.getNumPoints());
        pathB.setIsActive(false);
        simulationPathRepository.save(pathB);

        SimulationPath pathC = new SimulationPath();
        pathC.setVehicle(vehicle7);
        pathC.setScenarioName("SCENARIO_C");
        pathC.setWaypoints(scenarioC);
        pathC.setCurrentStepIndex(0);
        pathC.setTotalSteps(scenarioC.getNumPoints());
        pathC.setIsActive(false);
        simulationPathRepository.save(pathC);

        log.info("Seeded 3 simulation paths: A (compliant), B (time violation), C (no-entry deviation)");
    }

    private LineString buildScenarioA() {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(77.6389, 12.9116),
                new Coordinate(77.6380, 12.9130),
                new Coordinate(77.6370, 12.9150),
                new Coordinate(77.6350, 12.9180),
                new Coordinate(77.6330, 12.9210),
                new Coordinate(77.6310, 12.9240),
                new Coordinate(77.6290, 12.9270),
                new Coordinate(77.6270, 12.9300),
                new Coordinate(77.6250, 12.9330),
                new Coordinate(77.6230, 12.9360),
                new Coordinate(77.6210, 12.9390),
                new Coordinate(77.6190, 12.9420),
                new Coordinate(77.6170, 12.9450),
                new Coordinate(77.6150, 12.9480),
                new Coordinate(77.6130, 12.9510),
                new Coordinate(77.6110, 12.9540),
                new Coordinate(77.6090, 12.9570),
        };
        return geometryFactory.createLineString(coords);
    }

    private LineString buildScenarioB() {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(77.5535, 13.0241),
                new Coordinate(77.5560, 13.0200),
                new Coordinate(77.5590, 13.0160),
                new Coordinate(77.5620, 13.0120),
                new Coordinate(77.5650, 13.0080),
                new Coordinate(77.5680, 13.0040),
                new Coordinate(77.6170, 12.9760),
                new Coordinate(77.6180, 12.9750),
                new Coordinate(77.6190, 12.9740),
                new Coordinate(77.6200, 12.9730),
                new Coordinate(77.6210, 12.9725),
                new Coordinate(77.6220, 12.9720),
                new Coordinate(77.6230, 12.9715),
                new Coordinate(77.6240, 12.9710),
                new Coordinate(77.6250, 12.9700),
                new Coordinate(77.6260, 12.9690),
                new Coordinate(77.6270, 12.9680),
        };
        return geometryFactory.createLineString(coords);
    }

    private LineString buildScenarioC() {
        Coordinate[] coords = new Coordinate[]{
                new Coordinate(77.6770, 12.8399),
                new Coordinate(77.6740, 12.8430),
                new Coordinate(77.6710, 12.8470),
                new Coordinate(77.5630, 12.9800),
                new Coordinate(77.5640, 12.9790),
                new Coordinate(77.5650, 12.9780),
                new Coordinate(77.5660, 12.9770),
                new Coordinate(77.5670, 12.9760),
                new Coordinate(77.6200, 12.9370),
                new Coordinate(77.6210, 12.9360),
                new Coordinate(77.6220, 12.9350),
                new Coordinate(77.6230, 12.9340),
                new Coordinate(77.6240, 12.9330),
                new Coordinate(77.6250, 12.9320),
                new Coordinate(77.6260, 12.9310),
                new Coordinate(77.6270, 12.9300),
                new Coordinate(77.6280, 12.9290),
        };
        return geometryFactory.createLineString(coords);
    }
}
