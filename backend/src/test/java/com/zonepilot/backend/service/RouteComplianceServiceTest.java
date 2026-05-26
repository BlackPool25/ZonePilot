package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DispatchRouteRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Time;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteComplianceServiceTest {

    @Mock
    private VehicleRepository vehicleRepository;
    @Mock
    private RoutingService routingService;
    @Mock
    private DispatchRouteRepository dispatchRouteRepository;
    @Mock
    private TimePredictionService timePredictionService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private RouteComplianceService complianceService;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    private Vehicle vehicle;
    private LineString mockRoute;

    @BeforeEach
    void setUp() {
        complianceService = new RouteComplianceService(
                vehicleRepository, routingService, dispatchRouteRepository, timePredictionService, jdbcTemplate);

        vehicle = new Vehicle();
        vehicle.setId(5L);
        vehicle.setRegistrationNumber("KA01-LCV-0005");
        vehicle.setVehicleClass(VehicleClass.LCV);
        vehicle.setIsActive(true);

        mockRoute = gf.createLineString(new Coordinate[]{
                new Coordinate(77.6, 12.9),
                new Coordinate(77.61, 12.91)
        });
    }

    @Test
    void validateRoute_whenRouteIsCompliant_returnsCompliantResponse() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(routingService.findNearestNode(12.9, 77.6)).thenReturn(100L);
        when(routingService.findNearestNode(12.91, 77.61)).thenReturn(200L);
        when(routingService.computeRoute(100L, 200L)).thenReturn(mockRoute);

        // sp_validate_route returning no violations
        when(jdbcTemplate.queryForList(eq("SELECT * FROM sp_validate_route(?, ST_GeomFromText(?, 4326))"), eq(5L), anyString()))
                .thenReturn(Collections.emptyList());

        when(timePredictionService.predictTotalDurationSec(any(), anyList(), any())).thenReturn(300L);

        DispatchRoute savedRoute = new DispatchRoute();
        savedRoute.setId(500L);
        when(dispatchRouteRepository.save(any(DispatchRoute.class))).thenReturn(savedRoute);

        RouteValidationResponse response = complianceService.validateRoute(5L, 12.9, 77.6, 12.91, 77.61);

        assertTrue(response.getCompliant());
        assertEquals(500L, response.getDispatchRouteId());
        verify(vehicleRepository).save(vehicle);
        assertEquals(500L, vehicle.getActiveDispatchRouteId());
    }

    @Test
    void validateRoute_withViolations_usesZoneExitWaypointRouting() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(routingService.findNearestNode(12.9, 77.6)).thenReturn(100L);
        when(routingService.findNearestNode(12.91, 77.61)).thenReturn(200L);

        // Phase 1: direct route has a violation
        when(routingService.computeRoute(100L, 200L)).thenReturn(mockRoute);

        Map<String, Object> violation = new HashMap<>();
        violation.put("violated_zone_id", 1L);
        violation.put("violated_zone_name", "MG Road");
        violation.put("breach_type", "TIME_WINDOW");

        // Phase 1 validation: violation. Phase 2 (waypoint route): compliant.
        when(jdbcTemplate.queryForList(eq("SELECT * FROM sp_validate_route(?, ST_GeomFromText(?, 4326))"), eq(5L), anyString()))
                .thenReturn(Arrays.asList(violation))   // direct route: violation
                .thenReturn(Collections.emptyList());   // waypoint route: compliant

        // Zone-exit node lookup (findZoneExitNodes)
        when(jdbcTemplate.query(contains("blr_2po_4pgr_vertices_pgr"), any(RowMapper.class),
                eq(1L), eq(1L), eq(100L), eq(1L)))
                .thenReturn(Arrays.asList(150L));

        // Waypoint route: source(100) → exit(150) → target(200)
        LineString seg1 = gf.createLineString(new Coordinate[]{new Coordinate(77.6, 12.9), new Coordinate(77.605, 12.905)});
        LineString seg2 = gf.createLineString(new Coordinate[]{new Coordinate(77.605, 12.905), new Coordinate(77.61, 12.91)});
        when(routingService.computeRoute(100L, 150L)).thenReturn(seg1);
        when(routingService.computeRoute(150L, 200L)).thenReturn(seg2);

        when(timePredictionService.predictTotalDurationSec(any(), anyList(), any())).thenReturn(350L);

        DispatchRoute savedRoute = new DispatchRoute();
        savedRoute.setId(600L);
        when(dispatchRouteRepository.save(any(DispatchRoute.class))).thenReturn(savedRoute);

        RouteValidationResponse response = complianceService.validateRoute(5L, 12.9, 77.6, 12.91, 77.61);

        assertTrue(response.getCompliant());
        assertEquals(600L, response.getDispatchRouteId());
        verify(vehicleRepository).save(vehicle);
        assertEquals(600L, vehicle.getActiveDispatchRouteId());
    }
}
