package com.zonepilot.backend.service;

import com.zonepilot.backend.exception.RoutingException;
import com.zonepilot.backend.repository.RoadNetworkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoutingServiceTest {

    @Mock
    private RoadNetworkRepository roadNetworkRepository;

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        routingService = new RoutingService(roadNetworkRepository);
    }

    @Test
    void findNearestNode_whenExists_returnsNodeId() {
        when(roadNetworkRepository.findNearestNodeId(12.9, 77.6)).thenReturn(Optional.of(12345L));

        Long nodeId = routingService.findNearestNode(12.9, 77.6);

        assertEquals(12345L, nodeId);
        verify(roadNetworkRepository).findNearestNodeId(12.9, 77.6);
    }

    @Test
    void findNearestNode_whenNotExists_throwsRoutingException() {
        when(roadNetworkRepository.findNearestNodeId(12.9, 77.6)).thenReturn(Optional.empty());

        assertThrows(RoutingException.class, () -> routingService.findNearestNode(12.9, 77.6));
    }

    @Test
    void findNearestNodeOrNull_whenExists_returnsNodeId() {
        when(roadNetworkRepository.findNearestNodeId(12.9, 77.6)).thenReturn(Optional.of(12345L));

        Long nodeId = routingService.findNearestNodeOrNull(12.9, 77.6);

        assertEquals(12345L, nodeId);
    }

    @Test
    void findNearestNodeOrNull_whenNotExists_returnsNull() {
        when(roadNetworkRepository.findNearestNodeId(12.9, 77.6)).thenReturn(Optional.empty());

        assertNull(routingService.findNearestNodeOrNull(12.9, 77.6));
    }

    @Test
    void findNearestNodeOrNull_whenThrowsException_returnsNull() {
        when(roadNetworkRepository.findNearestNodeId(12.9, 77.6)).thenThrow(new RuntimeException("Database down"));

        assertNull(routingService.findNearestNodeOrNull(12.9, 77.6));
    }

    @Test
    void computeRoute_whenNoResults_throwsRoutingException() {
        when(roadNetworkRepository.computeDijkstraRoute(1L, 2L)).thenReturn(Collections.emptyList());

        assertThrows(RoutingException.class, () -> routingService.computeRoute(1L, 2L));
    }

    @Test
    void computeRoute_withValidEdges_stitchesLineStringsAndAvoidsDuplicatingJunctions() {
        List<Object[]> mockEdges = new ArrayList<>();
        // Edge 1: (77.6, 12.9) to (77.61, 12.91)
        mockEdges.add(new Object[]{1, 101L, 5.0, "LINESTRING(77.6 12.9, 77.61 12.91)"});
        // Edge 2: (77.61, 12.91) to (77.62, 12.92)
        mockEdges.add(new Object[]{2, 102L, 6.0, "LINESTRING(77.61 12.91, 77.62 12.92)"});

        when(roadNetworkRepository.computeDijkstraRoute(1L, 2L)).thenReturn(mockEdges);

        LineString route = routingService.computeRoute(1L, 2L);

        assertNotNull(route);
        assertEquals(3, route.getNumPoints());
        assertEquals(new Coordinate(77.6, 12.9), route.getCoordinateN(0));
        assertEquals(new Coordinate(77.61, 12.91), route.getCoordinateN(1));
        assertEquals(new Coordinate(77.62, 12.92), route.getCoordinateN(2));
    }

    @Test
    void computeRoute_withInvalidWktAndToleratedParsing_throwsIfInsufficientPoints() {
        List<Object[]> mockEdges = new ArrayList<>();
        mockEdges.add(new Object[]{1, 101L, 5.0, "INVALID WKT GEOMETRY"});

        when(roadNetworkRepository.computeDijkstraRoute(1L, 2L)).thenReturn(mockEdges);

        assertThrows(RoutingException.class, () -> routingService.computeRoute(1L, 2L));
    }
}
