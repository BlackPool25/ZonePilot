package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.exception.RoutingException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class BreachService {

    private static final Logger log = LoggerFactory.getLogger(BreachService.class);

    private final ZoneBreachLogRepository breachLogRepository;
    private final DispatchRouteRepository dispatchRouteRepository;
    private final RoutingService routingService;
    private final JdbcTemplate jdbcTemplate;
    private final GeometryFactory geometryFactory;

    public BreachService(ZoneBreachLogRepository breachLogRepository,
                         DispatchRouteRepository dispatchRouteRepository,
                         RoutingService routingService,
                         JdbcTemplate jdbcTemplate) {
        this.breachLogRepository = breachLogRepository;
        this.dispatchRouteRepository = dispatchRouteRepository;
        this.routingService = routingService;
        this.jdbcTemplate = jdbcTemplate;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    /**
     * Computes a reroute from the vehicle's current position that avoids the breached zone.
     * The destination is taken from the vehicle's most recent dispatch route if available.
     * The resolved geometry is persisted on the breach log record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void computeReroute(ZoneBreachLog breach, Long vehicleId,
                                double currentLat, double currentLng) {
        try {
            Long currentNode = routingService.findNearestNodeOrNull(currentLat, currentLng);
            if (currentNode == null) {
                log.warn("Cannot compute reroute for breach {}: no road node near ({}, {})",
                        breach.getId(), currentLat, currentLng);
                return;
            }

            Long destinationNode = findDestinationNodeForVehicle(vehicleId);
            if (destinationNode == null) {
                log.info("No active dispatch route found for vehicle {}; skipping reroute for breach {}",
                        vehicleId, breach.getId());
                return;
            }

            Long zoneId = breach.getZone().getId();
            LineString rerouteGeometry = computeZoneAvoidingRoute(currentNode, destinationNode, zoneId);

            breach.setResolvedRouteGeometry(rerouteGeometry);
            breachLogRepository.save(breach);
            log.info("Reroute computed for breach {} in zone {}: {} points",
                    breach.getId(), breach.getZone().getName(), rerouteGeometry.getNumPoints());

        } catch (RoutingException e) {
            log.warn("Reroute routing failed for breach {}: {}", breach.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("Unexpected error computing reroute for breach {}: {}", breach.getId(), e.getMessage());
        }
    }

    /**
     * Epic 3: Computes a reroute for a vehicle confirmed off-route (no zone breach).
     * Uses the vehicle's active dispatch route destination.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void computeOffRouteReroute(Long vehicleId, double currentLat, double currentLng) {
        try {
            Long currentNode = routingService.findNearestNodeOrNull(currentLat, currentLng);
            if (currentNode == null) {
                log.warn("Off-route reroute: no road node near ({}, {}) for vehicle {}", currentLat, currentLng, vehicleId);
                return;
            }

            Long destinationNode = findDestinationNodeForVehicle(vehicleId);
            if (destinationNode == null) {
                log.info("Off-route reroute: no active dispatch route for vehicle {}", vehicleId);
                return;
            }

            // Straight reroute — no zone to avoid
            LineString rerouteGeometry = routingService.computeRoute(currentNode, destinationNode);
            log.info("Off-route reroute computed for vehicle {}: {} points",
                    vehicleId, rerouteGeometry.getNumPoints());

            // Update the vehicle's active dispatch route geometry
            List<Object[]> rows = jdbcTemplate.query(
                    "SELECT id FROM dispatch_route WHERE vehicle_id = ? ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> new Object[]{rs.getLong("id")},
                    vehicleId);
            if (!rows.isEmpty()) {
                Long routeId = (Long) rows.get(0)[0];
                dispatchRouteRepository.findById(routeId).ifPresent(route -> {
                    route.setPlannedRouteGeometry(rerouteGeometry);
                    dispatchRouteRepository.save(route);
                });
            }

        } catch (Exception e) {
            log.warn("Off-route reroute failed for vehicle {}: {}", vehicleId, e.getMessage());
        }
    }

    private Long findDestinationNodeForVehicle(Long vehicleId) {
        try {
            List<Object[]> rows = jdbcTemplate.query(
                    "SELECT ST_Y(destination_point) AS lat, ST_X(destination_point) AS lng " +
                    "FROM dispatch_route WHERE vehicle_id = ? " +
                    "ORDER BY created_at DESC LIMIT 1",
                    (rs, rowNum) -> new Object[]{rs.getDouble("lat"), rs.getDouble("lng")},
                    vehicleId);

            if (rows.isEmpty()) return null;

            double destLat = (double) rows.get(0)[0];
            double destLng = (double) rows.get(0)[1];
            return routingService.findNearestNodeOrNull(destLat, destLng);
        } catch (Exception e) {
            log.warn("Could not find destination for vehicle {}: {}", vehicleId, e.getMessage());
            return null;
        }
    }

    /**
     * Runs pgr_dijkstra with a 1000x cost penalty on edges that intersect the given zone.
     * Uses cost_time_sec (Epic 1) as the base cost.
     */
    private LineString computeZoneAvoidingRoute(Long sourceNode, Long targetNode, Long zoneId) {
        String penalisedQuery = String.format(
                "SELECT id, source, target, " +
                "CASE WHEN ST_Intersects(the_geom, (SELECT boundary FROM zone_restriction WHERE id = %d)) " +
                "THEN cost_time_sec * 1000 ELSE cost_time_sec END AS cost, " +
                "CASE WHEN reverse_cost > 0 AND ST_Intersects(the_geom, (SELECT boundary FROM zone_restriction WHERE id = %d)) " +
                "THEN cost_time_sec * 1000 WHEN reverse_cost > 0 THEN cost_time_sec ELSE reverse_cost END AS reverse_cost " +
                "FROM blr_2po_4pgr",
                zoneId, zoneId);

        List<Object[]> results = jdbcTemplate.query(
                "SELECT seq, edge, cost, ST_AsText(pt.the_geom) AS geom " +
                "FROM pgr_dijkstra(?, CAST(? AS BIGINT), CAST(? AS BIGINT), directed := true) AS di " +
                "JOIN blr_2po_4pgr pt ON pt.id = di.edge ORDER BY seq",
                (rs, rowNum) -> new Object[]{
                        rs.getInt("seq"), rs.getLong("edge"),
                        rs.getDouble("cost"), rs.getString("geom")
                },
                penalisedQuery, sourceNode, targetNode);

        if (results.isEmpty()) {
            throw new RoutingException("No zone-avoiding route found from node " + sourceNode + " to " + targetNode);
        }

        return buildLineString(results);
    }

    private LineString buildLineString(List<Object[]> results) {
        List<org.locationtech.jts.geom.Coordinate> coordinates = new ArrayList<>();
        for (Object[] row : results) {
            String geomText = (String) row[3];
            if (geomText == null) continue;
            try {
                org.locationtech.jts.io.WKTReader reader =
                        new org.locationtech.jts.io.WKTReader(geometryFactory);
                LineString edge = (LineString) reader.read(geomText);
                org.locationtech.jts.geom.Coordinate[] edgeCoords = edge.getCoordinates();
                if (coordinates.isEmpty()) {
                    for (org.locationtech.jts.geom.Coordinate c : edgeCoords) coordinates.add(c);
                } else {
                    for (int i = 1; i < edgeCoords.length; i++) coordinates.add(edgeCoords[i]);
                }
            } catch (org.locationtech.jts.io.ParseException e) {
                log.warn("Failed to parse reroute edge geometry: {}", geomText);
            }
        }

        if (coordinates.size() < 2) {
            throw new RoutingException("Reroute geometry has insufficient points");
        }

        LineString result = geometryFactory.createLineString(
                coordinates.toArray(new org.locationtech.jts.geom.Coordinate[0]));
        result.setSRID(4326);
        return result;
    }
}
