package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.enums.BreachType;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BreachServiceTest {

    @Mock
    private ZoneBreachLogRepository breachLogRepository;
    @Mock
    private DispatchRouteRepository dispatchRouteRepository;
    @Mock
    private RoutingService routingService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private BreachService breachService;
    private final GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        breachService = new BreachService(
                breachLogRepository, dispatchRouteRepository, routingService, jdbcTemplate);
    }

    @Test
    void computeReroute_whenDestinationFound_calculatesAndSavesZoneAvoidingRoute() {
        ZoneBreachLog breach = new ZoneBreachLog();
        breach.setId(10L);
        ZoneRestriction zone = new ZoneRestriction();
        zone.setId(1L);
        zone.setName("Majestic Zone");
        breach.setZone(zone);
        breach.setBreachType(BreachType.NO_ENTRY);

        when(routingService.findNearestNodeOrNull(12.91, 77.63)).thenReturn(100L); // currentNode
        
        // Mock finding destination coordinates for vehicle
        List<Object[]> destCoords = new ArrayList<>();
        destCoords.add(new Object[]{12.97, 77.64});
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(destCoords);
        when(routingService.findNearestNodeOrNull(12.97, 77.64)).thenReturn(200L); // destinationNode

        // Mock pgr_dijkstra database execution in computeZoneAvoidingRoute
        List<Object[]> routingResults = new ArrayList<>();
        routingResults.add(new Object[]{1, 1001L, 10.0, "LINESTRING(77.63 12.91, 77.64 12.97)"});
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), eq(100L), eq(200L))).thenReturn(routingResults);

        breachService.computeReroute(breach, 5L, 12.91, 77.63);

        assertNotNull(breach.getResolvedRouteGeometry());
        assertEquals(2, breach.getResolvedRouteGeometry().getNumPoints());
        verify(breachLogRepository).save(breach);
    }

    @Test
    void computeReroute_whenNoDestinationFound_skipsRerouting() {
        ZoneBreachLog breach = new ZoneBreachLog();
        breach.setId(10L);

        when(routingService.findNearestNodeOrNull(12.91, 77.63)).thenReturn(100L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(Collections.emptyList());

        breachService.computeReroute(breach, 5L, 12.91, 77.63);

        assertNull(breach.getResolvedRouteGeometry());
        verify(breachLogRepository, never()).save(any());
    }

    @Test
    void computeOffRouteReroute_whenCalled_recomputesPlannedRouteAndSavesToDispatchRoute() {
        when(routingService.findNearestNodeOrNull(12.91, 77.63)).thenReturn(100L); // currentNode

        // Mock destination coordinates for vehicle
        List<Object[]> destCoords = new ArrayList<>();
        destCoords.add(new Object[]{12.97, 77.64});
        when(jdbcTemplate.query(eq("SELECT ST_Y(destination_point) AS lat, ST_X(destination_point) AS lng FROM dispatch_route WHERE vehicle_id = ? ORDER BY created_at DESC LIMIT 1"), any(RowMapper.class), eq(5L)))
                .thenReturn(destCoords);
        when(routingService.findNearestNodeOrNull(12.97, 77.64)).thenReturn(200L); // destinationNode

        // Mock computed route geometry
        LineString mockReroute = gf.createLineString(new Coordinate[]{
                new Coordinate(77.63, 12.91),
                new Coordinate(77.64, 12.97)
        });
        when(routingService.computeRoute(100L, 200L)).thenReturn(mockReroute);

        // Mock latest route lookup and loading
        List<Object[]> latestRouteId = new ArrayList<>();
        latestRouteId.add(new Object[]{500L});
        when(jdbcTemplate.query(eq("SELECT id FROM dispatch_route WHERE vehicle_id = ? ORDER BY created_at DESC LIMIT 1"), any(RowMapper.class), eq(5L)))
                .thenReturn(latestRouteId);

        DispatchRoute route = new DispatchRoute();
        route.setId(500L);
        when(dispatchRouteRepository.findById(500L)).thenReturn(Optional.of(route));

        breachService.computeOffRouteReroute(5L, 12.91, 77.63);

        assertEquals(mockReroute, route.getPlannedRouteGeometry());
        verify(dispatchRouteRepository).save(route);
    }
}
