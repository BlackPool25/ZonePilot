package com.zonepilot.backend.service;

import com.zonepilot.backend.exception.RoutingException;
import com.zonepilot.backend.repository.RoadNetworkRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final RoadNetworkRepository roadNetworkRepository;
    private final GeometryFactory geometryFactory;
    private final WKTReader wktReader;

    public RoutingService(RoadNetworkRepository roadNetworkRepository) {
        this.roadNetworkRepository = roadNetworkRepository;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.wktReader = new WKTReader(geometryFactory);
    }

    public Long findNearestNode(double lat, double lng) {
        return roadNetworkRepository.findNearestNodeId(lat, lng)
                .orElseThrow(() -> new RoutingException(
                        "No road network node found near coordinates (" + lat + ", " + lng + ")"));
    }

    /**
     * Returns null instead of throwing if no node is found.
     * Used by BreachService where routing is best-effort.
     */
    public Long findNearestNodeOrNull(double lat, double lng) {
        try {
            return roadNetworkRepository.findNearestNodeId(lat, lng).orElse(null);
        } catch (Exception e) {
            log.warn("Road network unavailable for nearest node lookup: {}", e.getMessage());
            return null;
        }
    }

    public LineString computeRoute(long sourceNodeId, long targetNodeId) {
        List<Object[]> results = roadNetworkRepository.computeDijkstraRoute(sourceNodeId, targetNodeId);

        if (results.isEmpty()) {
            throw new RoutingException(
                    "No road network path found between nodes " + sourceNodeId + " and " + targetNodeId
                            + ". Check coordinates are within Bangalore.");
        }

        return assembleRouteFromEdgeRows(results);
    }

    /**
     * Assembles a LineString from pgr_dijkstra edge rows.
     *
     * Each row is [seq, edge, cost, geom_wkt, node, source].
     * - node: the node we are LEAVING on this edge (the traversal start node)
     * - source: the stored source node of the edge in blr_2po_4pgr
     *
     * If node == source, the edge is traversed forward (source→target): use coords as-is.
     * If node != source, the edge is traversed backward (target→source): reverse the coords.
     *
     * This fixes the off-road geometry bug where edges stored in the wrong direction
     * caused straight-line jumps through forests/off-road areas.
     */
    public LineString assembleRouteFromEdgeRows(List<Object[]> results) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (Object[] row : results) {
            String geomText = (String) row[3];
            long traversalNode = ((Number) row[4]).longValue(); // di.node = node we leave from
            long edgeSource   = ((Number) row[5]).longValue(); // pt.source = stored source of edge
            try {
                LineString edgeGeom = (LineString) wktReader.read(geomText);
                Coordinate[] edgeCoords = edgeGeom.getCoordinates();

                // Reverse if traversed backwards (target→source)
                if (traversalNode != edgeSource) {
                    edgeCoords = reverse(edgeCoords);
                }

                if (coordinates.isEmpty()) {
                    for (Coordinate c : edgeCoords) coordinates.add(c);
                } else {
                    // Skip first coordinate to avoid duplicating junction points
                    for (int i = 1; i < edgeCoords.length; i++) coordinates.add(edgeCoords[i]);
                }
            } catch (ParseException e) {
                log.warn("Failed to parse edge geometry: {}", geomText, e);
            }
        }

        if (coordinates.size() < 2) {
            throw new RoutingException("Route geometry has insufficient points");
        }

        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    private static Coordinate[] reverse(Coordinate[] coords) {
        Coordinate[] reversed = new Coordinate[coords.length];
        for (int i = 0; i < coords.length; i++) reversed[i] = coords[coords.length - 1 - i];
        return reversed;
    }
}
