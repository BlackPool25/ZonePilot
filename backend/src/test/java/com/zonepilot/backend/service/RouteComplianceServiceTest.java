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
    @Mock
    private com.zonepilot.backend.repository.DispatchRouteAttemptRepository attemptRepository;

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
    void validateRoute_withViolations_retriesWithHugeCostAndAccepts() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(routingService.findNearestNode(12.9, 77.6)).thenReturn(100L);
        when(routingService.findNearestNode(12.91, 77.61)).thenReturn(200L);

        // Attempt 1: direct route has a violation
        when(routingService.computeRoute(100L, 200L)).thenReturn(mockRoute);

        Map<String, Object> violation = new HashMap<>();
        violation.put("violated_zone_id", 1L);
        violation.put("violated_zone_name", "MG Road");
        violation.put("breach_type", "TIME_WINDOW");

        // Attempt 1: violation. Attempt 2 (retry): compliant.
        when(jdbcTemplate.queryForList(eq("SELECT * FROM sp_validate_route(?, ST_GeomFromText(?, 4326))"), eq(5L), anyString()))
                .thenReturn(Arrays.asList(violation))
                .thenReturn(Collections.emptyList());

        // Endpoint not inside a zone -> retry allowed
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(100L), eq(200L))).thenReturn(0);

        // Retry Dijkstra returns one edge row; assemble into rerouted geometry
        LineString rerouted = gf.createLineString(new Coordinate[]{
                new Coordinate(77.6, 12.9), new Coordinate(77.62, 12.92)});
        Object[] row = new Object[]{1, 10L, 5.0,
                "LINESTRING(77.6 12.9,77.62 12.92)", 200L, 100L};
        when(jdbcTemplate.query(contains("pgr_dijkstra"), any(RowMapper.class), anyString(), eq(100L), eq(200L)))
                .thenReturn(Arrays.asList(row));
        when(routingService.assembleRouteFromEdgeRows(anyList())).thenReturn(rerouted);

        when(timePredictionService.predictTotalDurationSec(any(), anyList(), any())).thenReturn(350L);

        DispatchRoute savedRoute = new DispatchRoute();
        savedRoute.setId(600L);
        when(dispatchRouteRepository.save(any(DispatchRoute.class))).thenReturn(savedRoute);
        complianceService.setAttemptRepository(attemptRepository);

        RouteValidationResponse response = complianceService.validateRoute(5L, 12.9, 77.6, 12.91, 77.61);

        assertTrue(response.getCompliant());
        assertEquals(600L, response.getDispatchRouteId());
        assertNotNull(response.getAttempts());
        assertEquals(2, response.getAttempts().size());
        assertFalse(response.getAttempts().get(0).isAccepted());
        assertEquals(List.of(1L), response.getAttempts().get(0).getViolatedZoneIds());
        assertTrue(response.getAttempts().get(1).isAccepted());
        verify(vehicleRepository).save(vehicle);
        assertEquals(600L, vehicle.getActiveDispatchRouteId());
        // Versioned attempt rows persisted with violation ids + reject reason
        verify(attemptRepository, times(2)).save(any(com.zonepilot.backend.entity.DispatchRouteAttempt.class));
    }

    @Test
    void validateRoute_threeStrikes_capsAtThreeAttempts() {
        when(vehicleRepository.findById(5L)).thenReturn(Optional.of(vehicle));
        when(routingService.findNearestNode(12.9, 77.6)).thenReturn(100L);
        when(routingService.findNearestNode(12.91, 77.61)).thenReturn(200L);
        when(routingService.computeRoute(100L, 200L)).thenReturn(mockRoute);

        Map<String, Object> violation = new HashMap<>();
        violation.put("violated_zone_id", 1L);
        violation.put("violated_zone_name", "MG Road");
        violation.put("breach_type", "TIME_WINDOW");

        // All three attempts violated
        when(jdbcTemplate.queryForList(eq("SELECT * FROM sp_validate_route(?, ST_GeomFromText(?, 4326))"), eq(5L), anyString()))
                .thenReturn(Arrays.asList(violation))
                .thenReturn(Arrays.asList(violation))
                .thenReturn(Arrays.asList(violation))
                .thenReturn(Arrays.asList(violation));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(100L), eq(200L))).thenReturn(0);

        Object[] row = new Object[]{1, 10L, 5.0,
                "LINESTRING(77.6 12.9,77.62 12.92)", 200L, 100L};
        when(jdbcTemplate.query(contains("pgr_dijkstra"), any(RowMapper.class), anyString(), eq(100L), eq(200L)))
                .thenReturn(Arrays.asList(row));
        when(routingService.assembleRouteFromEdgeRows(anyList())).thenReturn(mockRoute);

        // Wait-state fallback deps
        when(timePredictionService.predictCumulativeArrivalsSec(any(), anyList(), any()))
                .thenReturn(Collections.emptyList());
        when(timePredictionService.predictTotalDurationSec(any(), anyList(), any())).thenReturn(300L);

        DispatchRoute savedRoute = new DispatchRoute();
        savedRoute.setId(700L);
        when(dispatchRouteRepository.save(any(DispatchRoute.class))).thenReturn(savedRoute);

        RouteValidationResponse response = complianceService.validateRoute(5L, 12.9, 77.6, 12.91, 77.61);

        assertNotNull(response.getAttempts());
        assertEquals(3, response.getAttempts().size());
        assertTrue(response.getAttempts().stream().noneMatch(RouteValidationResponse.AttemptDetail::isAccepted));
    }
}
