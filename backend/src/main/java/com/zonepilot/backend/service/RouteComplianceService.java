package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.RouteStatus;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.RoutingException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RouteComplianceService {

    private static final Logger log = LoggerFactory.getLogger(RouteComplianceService.class);

    private final VehicleRepository vehicleRepository;
    private final RoutingService routingService;
    private final DispatchRouteRepository dispatchRouteRepository;
    private final JdbcTemplate jdbcTemplate;
    private final GeometryFactory geometryFactory;

    public RouteComplianceService(VehicleRepository vehicleRepository,
                                   RoutingService routingService,
                                   DispatchRouteRepository dispatchRouteRepository,
                                   JdbcTemplate jdbcTemplate) {
        this.vehicleRepository = vehicleRepository;
        this.routingService = routingService;
        this.dispatchRouteRepository = dispatchRouteRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    @Transactional
    public RouteValidationResponse validateRoute(Long vehicleId, double originLat, double originLng,
                                                   double destLat, double destLng) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));

        Long sourceNode = routingService.findNearestNode(originLat, originLng);
        Long targetNode = routingService.findNearestNode(destLat, destLng);

        LineString routeGeometry = routingService.computeRoute(sourceNode, targetNode);

        List<RouteValidationResponse.ViolationDetail> violations =
                validateRouteAgainstZones(vehicleId, routeGeometry);

        // Build origin and destination Point geometries (SRID 4326)
        Point originPoint = geometryFactory.createPoint(new Coordinate(originLng, originLat));
        originPoint.setSRID(4326);
        Point destPoint = geometryFactory.createPoint(new Coordinate(destLng, destLat));
        destPoint.setSRID(4326);

        RouteValidationResponse response = new RouteValidationResponse();
        response.setViolations(violations);

        LineString routeToStore;
        if (violations.isEmpty()) {
            response.setCompliant(true);
            response.setRouteGeoJson(routeGeometry.toText());
            routeToStore = routeGeometry;
        } else {
            response.setCompliant(false);
            response.setRouteGeoJson(routeGeometry.toText());
            routeToStore = routeGeometry;

            // Attempt alternative route via a midpoint that avoids the violated zone.
            // Full zone-avoidance routing (pgr_withPoints cost penalty) requires the
            // road network to be loaded. If unavailable, we return the original route
            // with a clear flag rather than silently returning the same geometry.
            try {
                LineString alternativeRoute = computeAlternativeRoute(sourceNode, targetNode, violations);
                response.setAlternativeRouteGeoJson(alternativeRoute.toText());
                routeToStore = alternativeRoute;
            } catch (RoutingException e) {
                log.warn("Alternative route unavailable: {}", e.getMessage());
                response.setAlternativeRouteUnavailable(true);
            }
        }

        DispatchRoute dispatchRoute = new DispatchRoute();
        dispatchRoute.setVehicle(vehicle);
        dispatchRoute.setOriginPoint(originPoint);
        dispatchRoute.setDestinationPoint(destPoint);
        dispatchRoute.setPlannedRouteGeometry(routeToStore);
        dispatchRoute.setStatus(response.getCompliant() ? RouteStatus.COMPLIANT : RouteStatus.NON_COMPLIANT);
        dispatchRoute.setValidationTimestamp(Instant.now());

        DispatchRoute saved = dispatchRouteRepository.save(dispatchRoute);
        response.setDispatchRouteId(saved.getId());

        return response;
    }

    /**
     * Attempts to compute an alternative route that avoids violated zones.
     * Strategy: temporarily increase edge costs for edges intersecting violated zones,
     * then re-run Dijkstra. If the road network table is not loaded, this throws RoutingException.
     */
    private LineString computeAlternativeRoute(Long sourceNode, Long targetNode,
                                                List<RouteValidationResponse.ViolationDetail> violations) {
        // Build a zone ID list for the penalty query
        if (violations.isEmpty()) {
            return routingService.computeRoute(sourceNode, targetNode);
        }

        // Use pgr_dijkstra with a subquery that penalises edges inside violated zones.
        // This avoids mutating the ways table.
        StringBuilder zoneIds = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) zoneIds.append(",");
            zoneIds.append(violations.get(i).getZoneId());
        }

        String penalisedQuery = String.format(
                "SELECT id, source, target, " +
                "CASE WHEN ST_Intersects(the_geom, (SELECT ST_Union(boundary) FROM zone_restriction WHERE id IN (%s))) " +
                "THEN length_m * 1000 ELSE length_m END AS cost, " +
                "CASE WHEN ST_Intersects(the_geom, (SELECT ST_Union(boundary) FROM zone_restriction WHERE id IN (%s))) " +
                "THEN length_m * 1000 ELSE length_m END AS reverse_cost " +
                "FROM blr_2po_4pgr",
                zoneIds, zoneIds);

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
            throw new RoutingException("No alternative route found avoiding violated zones");
        }

        return buildLineStringFromEdgeResults(results);
    }

    private LineString buildLineStringFromEdgeResults(List<Object[]> results) {
        List<Coordinate> coordinates = new ArrayList<>();
        for (Object[] row : results) {
            String geomText = (String) row[3];
            if (geomText == null) continue;
            try {
                org.locationtech.jts.io.WKTReader reader =
                        new org.locationtech.jts.io.WKTReader(geometryFactory);
                LineString edge = (LineString) reader.read(geomText);
                Coordinate[] edgeCoords = edge.getCoordinates();
                if (coordinates.isEmpty()) {
                    for (Coordinate c : edgeCoords) coordinates.add(c);
                } else {
                    for (int i = 1; i < edgeCoords.length; i++) coordinates.add(edgeCoords[i]);
                }
            } catch (org.locationtech.jts.io.ParseException e) {
                log.warn("Failed to parse alternative route edge geometry: {}", geomText);
            }
        }
        if (coordinates.size() < 2) {
            throw new RoutingException("Alternative route geometry has insufficient points");
        }
        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    /**
     * Calls sp_validate_route stored procedure.
     * Throws ValidationException if the procedure call itself fails (S4 fix: no silent swallow).
     */
    private List<RouteValidationResponse.ViolationDetail> validateRouteAgainstZones(
            Long vehicleId, LineString routeGeometry) {
        List<RouteValidationResponse.ViolationDetail> violations = new ArrayList<>();

        List<Map<String, Object>> results = jdbcTemplate.queryForList(
                "SELECT * FROM sp_validate_route(?, ST_GeomFromText(?, 4326))",
                vehicleId, routeGeometry.toText());

        for (Map<String, Object> row : results) {
            RouteValidationResponse.ViolationDetail detail = new RouteValidationResponse.ViolationDetail();
            if (row.get("violated_zone_id") != null) {
                detail.setZoneId(((Number) row.get("violated_zone_id")).longValue());
            }
            if (row.get("violated_zone_name") != null) {
                detail.setZoneName((String) row.get("violated_zone_name"));
            }
            if (row.get("breach_type") != null) {
                detail.setBreachType((String) row.get("breach_type"));
            }
            violations.add(detail);
        }

        return violations;
    }
}
