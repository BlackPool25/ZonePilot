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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

        // Mock finding destination coordinates for vehicle
        List<Object[]> destCoords = new ArrayList<>();
        destCoords.add(new Object[]{12.97, 77.64});
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(destCoords);

        // Road phase is isolated in RoutingService (REQUIRES_NEW): mock the result.
        LineString mockReroute = gf.createLineString(new Coordinate[]{
                new Coordinate(77.63, 12.91),
                new Coordinate(77.64, 12.97)
        });
        when(routingService.computeZoneAvoidingReroute(
                eq(12.91), eq(77.63), eq(12.97), eq(77.64), eq(1L))).thenReturn(mockReroute);

        breachService.computeReroute(breach, 5L, 12.91, 77.63);

        assertNotNull(breach.getResolvedRouteGeometry());
        assertEquals(2, breach.getResolvedRouteGeometry().getNumPoints());
        verify(breachLogRepository).save(breach);
    }

    @Test
    void computeReroute_whenRoadsUnavailable_breachSurvivesWithoutGeometry() {
        ZoneBreachLog breach = new ZoneBreachLog();
        breach.setId(10L);
        ZoneRestriction zone = new ZoneRestriction();
        zone.setId(1L);
        breach.setZone(zone);

        List<Object[]> destCoords = new ArrayList<>();
        destCoords.add(new Object[]{12.97, 77.64});
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(destCoords);
        // Missing road network: isolated road phase returns null instead of
        // aborting the caller's txn (which used to roll back the breach row).
        when(routingService.computeZoneAvoidingReroute(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong())).thenReturn(null);

        breachService.computeReroute(breach, 5L, 12.91, 77.63);

        assertNull(breach.getResolvedRouteGeometry());
        verify(breachLogRepository, never()).save(any());
    }

    @Test
    void computeReroute_whenNoDestinationFound_skipsRerouting() {
        ZoneBreachLog breach = new ZoneBreachLog();
        breach.setId(10L);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(Collections.emptyList());

        breachService.computeReroute(breach, 5L, 12.91, 77.63);

        assertNull(breach.getResolvedRouteGeometry());
        verify(breachLogRepository, never()).save(any());
        verify(routingService, never()).computeZoneAvoidingReroute(
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
    }

    @Test
    void computeOffRouteReroute_whenCalled_recomputesPlannedRouteAndSavesToDispatchRoute() {
        // Mock destination coordinates for vehicle
        List<Object[]> destCoords = new ArrayList<>();
        destCoords.add(new Object[]{12.97, 77.64});
        when(jdbcTemplate.query(eq("SELECT ST_Y(destination_point) AS lat, ST_X(destination_point) AS lng FROM dispatch_route WHERE vehicle_id = ? ORDER BY created_at DESC LIMIT 1"), any(RowMapper.class), eq(5L)))
                .thenReturn(destCoords);

        // Mock computed route geometry (isolated road phase in RoutingService)
        LineString mockReroute = gf.createLineString(new Coordinate[]{
                new Coordinate(77.63, 12.91),
                new Coordinate(77.64, 12.97)
        });
        when(routingService.computeDirectReroute(eq(12.91), eq(77.63), eq(12.97), eq(77.64)))
                .thenReturn(mockReroute);

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

    @Test
    void computeReroute_joinsCallerTransaction_noRequiresNew() throws NoSuchMethodException {
        // Deadlock guard: recordPosition calls computeReroute inside its own txn,
        // so this method must join the outer txn (default REQUIRED), never suspend it.
        Propagation p = BreachService.class
                .getMethod("computeReroute", ZoneBreachLog.class, Long.class, double.class, double.class)
                .getAnnotation(Transactional.class).propagation();
        assertEquals(Propagation.REQUIRED, p,
                "computeReroute must join the caller's transaction (REQUIRED), not REQUIRES_NEW");
    }

    @Test
    void computeOffRouteReroute_joinsCallerTransaction_noRequiresNew() throws NoSuchMethodException {
        Propagation p = BreachService.class
                .getMethod("computeOffRouteReroute", Long.class, double.class, double.class)
                .getAnnotation(Transactional.class).propagation();
        assertEquals(Propagation.REQUIRED, p,
                "computeOffRouteReroute must join the caller's transaction (REQUIRED), not REQUIRES_NEW");
    }
}
