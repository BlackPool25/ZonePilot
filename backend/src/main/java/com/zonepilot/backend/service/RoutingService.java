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
        return roadNetworkRepository.findNearestNodeId(lat, lng).orElse(null);
    }

    public LineString computeRoute(long sourceNodeId, long targetNodeId) {
        List<Object[]> results = roadNetworkRepository.computeDijkstraRoute(sourceNodeId, targetNodeId);

        if (results.isEmpty()) {
            throw new RoutingException(
                    "No road network path found between nodes " + sourceNodeId + " and " + targetNodeId
                            + ". Check coordinates are within Bangalore.");
        }

        List<Coordinate> coordinates = new ArrayList<>();
        for (Object[] row : results) {
            String geomText = (String) row[3];
            try {
                LineString edgeGeom = (LineString) wktReader.read(geomText);
                Coordinate[] edgeCoords = edgeGeom.getCoordinates();
                if (coordinates.isEmpty()) {
                    for (Coordinate c : edgeCoords) {
                        coordinates.add(c);
                    }
                } else {
                    // Skip first coordinate to avoid duplicating junction points
                    for (int i = 1; i < edgeCoords.length; i++) {
                        coordinates.add(edgeCoords[i]);
                    }
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
}
