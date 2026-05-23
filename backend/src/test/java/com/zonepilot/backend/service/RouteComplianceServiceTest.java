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
    void validateRoute_withViolationsAndSuccessfulBypass_performsRecursiveRouting() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(routingService.findNearestNode(12.9, 77.6)).thenReturn(100L);
        when(routingService.findNearestNode(12.91, 77.61)).thenReturn(200L);
        
        // Attempt 1: Returns initial route
        when(routingService.computeRoute(100L, 200L)).thenReturn(mockRoute);

        // Attempt 1 validation: returns 1 violation
        Map<String, Object> violation = new HashMap<>();
        violation.put("violated_zone_id", 1L);
        violation.put("violated_zone_name", "MG Road");
        violation.put("breach_type", "TIME_WINDOW");
        
        // Mock queryForList for Attempt 1 and Attempt 2 (which is compliant)
        when(jdbcTemplate.queryForList(eq("SELECT * FROM sp_validate_route(?, ST_GeomFromText(?, 4326))"), eq(5L), anyString()))
                .thenReturn(Arrays.asList(violation)) // Attempt 1 returns violation
                .thenReturn(Collections.emptyList()); // Attempt 2 returns empty (compliant bypass)

        // Mock wait state lookups for Attempt 1:
        // Entry points:
        List<double[]> entryPoints = Arrays.asList(new double[]{12.905, 77.605});
        when(jdbcTemplate.query(eq("SELECT ST_Y(ST_ClosestPoint(boundary, ST_GeomFromText(?, 4326))) AS lat,        ST_X(ST_ClosestPoint(boundary, ST_GeomFromText(?, 4326))) AS lng FROM zone_restriction WHERE id = ?"), any(RowMapper.class), anyString(), anyString(), eq(1L)))
                .thenReturn(entryPoints);
        // Time prediction arrivals and total times:
        when(timePredictionService.predictCumulativeArrivalsSec(any(), anyList(), any())).thenReturn(Arrays.asList(50L));
        when(timePredictionService.predictTotalDurationSec(any(), anyList(), any())).thenReturn(300L);
        // Curfew end time:
        List<Object[]> curfewRules = new ArrayList<>();
        curfewRules.add(new Object[]{Time.valueOf("23:00:00")});
        when(jdbcTemplate.query(eq("SELECT restriction_end_time FROM zone_restriction_rule WHERE zone_id = ? AND is_active = true AND restriction_end_time IS NOT NULL ORDER BY restriction_end_time DESC LIMIT 1"), any(RowMapper.class), eq(1L)))
                .thenReturn(curfewRules);

        // Attempt 2: Avoid zones routing using pgr_dijkstra with penalty SQL
        List<Object[]> dijkstraBypassEdges = new ArrayList<>();
        dijkstraBypassEdges.add(new Object[]{1, 1001L, 10.0, "LINESTRING(77.6 12.9, 77.62 12.92, 77.61 12.91)"});
        when(jdbcTemplate.query(contains("pgr_dijkstra"), any(RowMapper.class), anyString(), eq(100L), eq(200L)))
                .thenReturn(dijkstraBypassEdges);

        DispatchRoute savedRoute = new DispatchRoute();
        savedRoute.setId(600L);
        when(dispatchRouteRepository.save(any(DispatchRoute.class))).thenReturn(savedRoute);

        RouteValidationResponse response = complianceService.validateRoute(5L, 12.9, 77.6, 12.91, 77.61);

        // Bypass found, so compliant is true
        assertTrue(response.getCompliant());
        assertEquals(600L, response.getDispatchRouteId());
        verify(vehicleRepository).save(vehicle);
        assertEquals(600L, vehicle.getActiveDispatchRouteId());
    }
}
