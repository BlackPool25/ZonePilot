package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.RouteValidationResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.RouteStatus;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.RoutingException;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RouteComplianceService {

    private static final Logger log = LoggerFactory.getLogger(RouteComplianceService.class);

    // IST timezone for curfew window calculations
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final VehicleRepository vehicleRepository;
    private final RoutingService routingService;
    private final DispatchRouteRepository dispatchRouteRepository;
    private final TimePredictionService timePredictionService;
    private final JdbcTemplate jdbcTemplate;
    private final GeometryFactory geometryFactory;

    public RouteComplianceService(VehicleRepository vehicleRepository,
                                   RoutingService routingService,
                                   DispatchRouteRepository dispatchRouteRepository,
                                   TimePredictionService timePredictionService,
                                   JdbcTemplate jdbcTemplate) {
        this.vehicleRepository = vehicleRepository;
        this.routingService = routingService;
        this.dispatchRouteRepository = dispatchRouteRepository;
        this.timePredictionService = timePredictionService;
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

        Instant departureTime = Instant.now().plusSeconds(10);

        // Smart zone-aware routing: direct route → zone-exit waypoints → wait-state fallback
        RouteCandidate winner = computeBestRoute(sourceNode, targetNode, vehicleId, departureTime);

        Point originPoint = geometryFactory.createPoint(new Coordinate(originLng, originLat));
        originPoint.setSRID(4326);
        Point destPoint = geometryFactory.createPoint(new Coordinate(destLng, destLat));
        destPoint.setSRID(4326);

        RouteValidationResponse response = new RouteValidationResponse();
        response.setViolations(winner.violations);
        response.setRouteGeoJson(winner.geometry.toText());
        response.setCompliant(winner.violations.isEmpty() && winner.waitDurationSec == 0);

        if (!winner.violations.isEmpty() && winner.waitDurationSec > 0) {
            // Wait-state route: original route is faster with a wait than any alternate
            response.setAlternativeRouteUnavailable(false);
            response.setWaitDurationSec(winner.waitDurationSec);
            response.setWaitUntil(winner.waitUntil != null ? winner.waitUntil.toString() : null);
        } else if (!winner.violations.isEmpty()) {
            response.setAlternativeRouteGeoJson(winner.geometry.toText());
        }

        DispatchRoute dispatchRoute = new DispatchRoute();
        dispatchRoute.setVehicle(vehicle);
        dispatchRoute.setOriginPoint(originPoint);
        dispatchRoute.setDestinationPoint(destPoint);
        dispatchRoute.setPlannedRouteGeometry(winner.geometry);
        dispatchRoute.setStatus(response.getCompliant() ? RouteStatus.COMPLIANT : RouteStatus.NON_COMPLIANT);
        dispatchRoute.setValidationTimestamp(departureTime);
        if (winner.waitDurationSec > 0) {
            dispatchRoute.setWaitUntil(winner.waitUntil);
            dispatchRoute.setWaitDurationSec(winner.waitDurationSec);
        }

        DispatchRoute saved = dispatchRouteRepository.save(dispatchRoute);

        // Epic 3: only mark as active route when the route is usable (compliant or wait-state).
        // BUG-NEW-008: do not set active route for non-compliant routes with no wait state,
        // as map-matching against a non-compliant route would produce incorrect off-route signals.
        if (response.getCompliant() || winner.waitDurationSec() > 0) {
            vehicle.setActiveDispatchRouteId(saved.getId());
            vehicleRepository.save(vehicle);
        }

        response.setDispatchRouteId(saved.getId());
        return response;
    }

    // ── Smart zone-aware routing ──────────────────────────────────────────────

    /**
     * Two-phase routing strategy:
     *
     * Phase 1 — Direct route: compute standard Dijkstra. If compliant, done.
     *
     * Phase 2 — Zone-exit waypoint routing: for each violated zone, find the
     * nearest road network node that lies OUTSIDE the zone boundary using
     * ST_ExteriorRing + KNN. Route as a waypoint chain:
     *   origin → exit_node_1 → exit_node_2 → ... → destination
     * This guarantees the route leaves each zone before continuing — no
     * penalty retries needed.
     *
     * Phase 3 — Wait-state fallback: if the destination itself is inside a
     * zone (unavoidable), compute the curfew end time and return a wait-state
     * route rather than exhausting retries.
     */
    private RouteCandidate computeBestRoute(Long sourceNode, Long targetNode,
                                             Long vehicleId, Instant departureTime) {
        // Phase 1: direct route
        LineString directRoute = computeRouteAvoidingZones(sourceNode, targetNode, Set.of());
        List<RouteValidationResponse.ViolationDetail> violations =
                validateRouteAgainstZones(vehicleId, directRoute);

        if (violations.isEmpty()) {
            long travelSec = timePredictionService.predictTotalDurationSec(
                    directRoute, List.of(), departureTime);
            return new RouteCandidate(directRoute, violations, 0, null, travelSec);
        }

        // Phase 2: smart zone-exit waypoint routing
        try {
            Set<Long> violatedZoneIds = violations.stream()
                    .filter(v -> v.getZoneId() != null)
                    .map(RouteValidationResponse.ViolationDetail::getZoneId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

            List<Long> exitNodes = findZoneExitNodes(violatedZoneIds, sourceNode, targetNode);

            if (!exitNodes.isEmpty()) {
                LineString waypointRoute = computeWaypointRoute(sourceNode, exitNodes, targetNode);
                List<RouteValidationResponse.ViolationDetail> waypointViolations =
                        validateRouteAgainstZones(vehicleId, waypointRoute);

                if (waypointViolations.isEmpty()) {
                    long travelSec = timePredictionService.predictTotalDurationSec(
                            waypointRoute, List.of(), departureTime);
                    return new RouteCandidate(waypointRoute, waypointViolations, 0, null, travelSec);
                }

                // Waypoint route still has violations (destination inside zone) — use it for wait-state
                violations = waypointViolations;
                directRoute = waypointRoute;
            }
        } catch (RoutingException e) {
            log.warn("Zone-exit waypoint routing failed, falling back to wait-state: {}", e.getMessage());
        }

        // Phase 3: wait-state fallback
        List<double[]> zoneEntryPoints = queryZoneEntryPoints(directRoute, violations);
        List<Long> cumulativeArrivals = timePredictionService.predictCumulativeArrivalsSec(
                directRoute, zoneEntryPoints, departureTime);
        WaitState waitState = computeWaitState(violations, departureTime, cumulativeArrivals);
        long travelSec = timePredictionService.predictTotalDurationSec(
                directRoute, zoneEntryPoints, departureTime);
        long totalWithWait = (travelSec > 0 ? travelSec : 0) + waitState.waitDurationSec;

        return new RouteCandidate(directRoute, violations, waitState.waitDurationSec,
                waitState.waitUntil, totalWithWait);
    }

    /**
     * For each violated zone, finds the nearest road network node that is
     * OUTSIDE the zone boundary. Uses ST_ExteriorRing to get the boundary
     * ring, then KNN to snap to the nearest road vertex outside the polygon.
     *
     * Returns nodes ordered by proximity to the source (so the waypoint chain
     * routes through them in a sensible geographic order).
     */
    private List<Long> findZoneExitNodes(Set<Long> zoneIds, Long sourceNode, Long targetNode) {
        if (zoneIds.isEmpty()) return List.of();

        String ids = String.join(",", zoneIds.stream().map(String::valueOf).toList());

        // For each zone: find the nearest road vertex that is outside the zone polygon.
        // ST_ExteriorRing gives the boundary; we want a point just outside it.
        // We use NOT ST_Within to exclude vertices inside the zone.
        List<Long> exitNodes = new ArrayList<>();
        for (Long zoneId : zoneIds) {
            try {
                List<Long> nodes = jdbcTemplate.query(
                        "SELECT v.id FROM blr_2po_4pgr_vertices_pgr v " +
                        "WHERE NOT ST_Within(v.the_geom, (SELECT boundary FROM zone_restriction WHERE id = ?)) " +
                        "AND v.the_geom && ST_Expand((SELECT boundary FROM zone_restriction WHERE id = ?), 0.02) " +
                        "ORDER BY v.the_geom <-> (SELECT ST_ClosestPoint(ST_ExteriorRing(boundary), " +
                        "  (SELECT the_geom FROM blr_2po_4pgr_vertices_pgr WHERE id = ?)) " +
                        "  FROM zone_restriction WHERE id = ?) " +
                        "LIMIT 1",
                        (rs, rowNum) -> rs.getLong("id"),
                        zoneId, zoneId, sourceNode, zoneId);
                if (!nodes.isEmpty()) exitNodes.add(nodes.get(0));
            } catch (Exception e) {
                log.warn("Could not find exit node for zone {}: {}", zoneId, e.getMessage());
            }
        }
        return exitNodes;
    }

    /**
     * Routes through a sequence of waypoint nodes: source → wp1 → wp2 → ... → target.
     * Stitches the individual Dijkstra segments into a single LineString.
     */
    private LineString computeWaypointRoute(Long sourceNode, List<Long> waypointNodes, Long targetNode) {
        List<Long> chain = new ArrayList<>();
        chain.add(sourceNode);
        chain.addAll(waypointNodes);
        chain.add(targetNode);

        List<org.locationtech.jts.geom.Coordinate> allCoords = new ArrayList<>();
        for (int i = 0; i < chain.size() - 1; i++) {
            LineString segment = routingService.computeRoute(chain.get(i), chain.get(i + 1));
            org.locationtech.jts.geom.Coordinate[] segCoords = segment.getCoordinates();
            if (allCoords.isEmpty()) {
                for (org.locationtech.jts.geom.Coordinate c : segCoords) allCoords.add(c);
            } else {
                for (int j = 1; j < segCoords.length; j++) allCoords.add(segCoords[j]);
            }
        }

        if (allCoords.size() < 2) throw new RoutingException("Waypoint route has insufficient points");
        return geometryFactory.createLineString(
                allCoords.toArray(new org.locationtech.jts.geom.Coordinate[0]));
    }

    /**
     * Computes wait time for the first curfew violation on this route.
     * Queries zone_restriction_rule for the restriction end time.
     */
    private WaitState computeWaitState(List<RouteValidationResponse.ViolationDetail> violations,
                                        Instant departureTime,
                                        List<Long> cumulativeArrivalsSec) {
        if (violations.isEmpty()) return new WaitState(0, null);

        // Use the first violation's zone to find curfew end time
        Long zoneId = violations.get(0).getZoneId();
        if (zoneId == null) return new WaitState(0, null);

        // Estimated arrival at zone entry (first leg cumulative, or 0 if unavailable)
        long arrivalOffsetSec = cumulativeArrivalsSec.isEmpty() ? 0 : cumulativeArrivalsSec.get(0);
        Instant estimatedArrival = departureTime.plusSeconds(arrivalOffsetSec);

        try {
            List<Object[]> rules = jdbcTemplate.query(
                    "SELECT restriction_end_time FROM zone_restriction_rule " +
                    "WHERE zone_id = ? AND is_active = true " +
                    "AND restriction_end_time IS NOT NULL " +
                    "ORDER BY restriction_end_time DESC LIMIT 1",
                    (rs, rowNum) -> new Object[]{rs.getTime("restriction_end_time")},
                    zoneId);

            if (rules.isEmpty()) return new WaitState(0, null);

            java.sql.Time endTime = (java.sql.Time) rules.get(0)[0];
            LocalTime curfewEnd = endTime.toLocalTime();

            // Compute wait_until = today's curfew end time in IST
            Instant curfewEndInstant = estimatedArrival
                    .atZone(IST)
                    .toLocalDate()
                    .atTime(curfewEnd)
                    .atZone(IST)
                    .toInstant();

            // If curfew end is before estimated arrival, it ends tomorrow
            if (curfewEndInstant.isBefore(estimatedArrival)) {
                curfewEndInstant = curfewEndInstant.plusSeconds(86400);
            }

            long waitSec = Math.max(0, curfewEndInstant.getEpochSecond() - estimatedArrival.getEpochSecond());
            return new WaitState((int) waitSec, curfewEndInstant);

        } catch (Exception e) {
            log.warn("Could not compute wait state for zone {}: {}", zoneId, e.getMessage());
            return new WaitState(0, null);
        }
    }

    /**
     * Queries zone entry points (ST_Intersection of route with zone boundaries).
     * Returns list of [lat, lng] for each violated zone.
     */
    private List<double[]> queryZoneEntryPoints(LineString routeGeometry,
                                                 List<RouteValidationResponse.ViolationDetail> violations) {
        List<double[]> points = new ArrayList<>();
        for (RouteValidationResponse.ViolationDetail v : violations) {
            if (v.getZoneId() == null) continue;
            try {
                List<double[]> pts = jdbcTemplate.query(
                        "SELECT ST_Y(ST_ClosestPoint(boundary, ST_GeomFromText(?, 4326))) AS lat, " +
                        "       ST_X(ST_ClosestPoint(boundary, ST_GeomFromText(?, 4326))) AS lng " +
                        "FROM zone_restriction WHERE id = ?",
                        (rs, rowNum) -> new double[]{rs.getDouble("lat"), rs.getDouble("lng")},
                        routeGeometry.toText(), routeGeometry.toText(), v.getZoneId());
                points.addAll(pts);
            } catch (Exception e) {
                log.warn("Could not query zone entry point for zone {}: {}", v.getZoneId(), e.getMessage());
            }
        }
        return points;
    }

    /**
     * Runs pgr_dijkstra with 1000x cost penalty on all penalised zones.
     * Uses cost_time_sec (Epic 1) as base cost. Preserves reverse_cost=-1 for one-ways.
     *
     * BUG-NEW-006: pgr_dijkstra requires its edge SQL as a server-evaluated string literal —
     * JDBC bind parameters cannot be used inside it. Zone IDs are server-side Long values
     * (never user-controlled input), so %d / String.valueOf formatting is safe here.
     * The outer pgr_dijkstra call uses JDBC bind parameters for sourceNode and targetNode.
     */
    private LineString computeRouteAvoidingZones(Long sourceNode, Long targetNode,
                                                  Set<Long> penalisedZones) {
        if (penalisedZones.isEmpty()) {
            return routingService.computeRoute(sourceNode, targetNode);
        }

        String zoneIds = String.join(",",
                penalisedZones.stream().map(String::valueOf).toList());

        String pgRoutingEdgeSql = String.format(
                "SELECT id, source, target, " +
                "CASE WHEN ST_Intersects(the_geom, (SELECT ST_Union(boundary) FROM zone_restriction WHERE id IN (%s))) " +
                "THEN cost_time_sec * 1000 ELSE cost_time_sec END AS cost, " +
                "CASE WHEN reverse_cost > 0 AND ST_Intersects(the_geom, (SELECT ST_Union(boundary) FROM zone_restriction WHERE id IN (%s))) " +
                "THEN cost_time_sec * 1000 WHEN reverse_cost > 0 THEN cost_time_sec ELSE reverse_cost END AS reverse_cost " +
                "FROM blr_2po_4pgr",
                zoneIds, zoneIds);

        List<Object[]> results = jdbcTemplate.query(
                "SELECT di.seq, di.edge, di.cost, ST_AsText(pt.the_geom) AS geom, di.node, pt.source " +
                "FROM pgr_dijkstra(?, CAST(? AS BIGINT), CAST(? AS BIGINT), directed := true) AS di " +
                "JOIN blr_2po_4pgr pt ON pt.id = di.edge " +
                "WHERE di.edge <> -1 " +
                "ORDER BY di.seq",
                (rs, rowNum) -> new Object[]{
                        rs.getInt("seq"), rs.getLong("edge"),
                        rs.getDouble("cost"), rs.getString("geom"),
                        rs.getLong("node"), rs.getLong("source")
                },
                pgRoutingEdgeSql, sourceNode, targetNode);

        if (results.isEmpty()) {
            throw new RoutingException("No route found avoiding zones: " + zoneIds);
        }

        return buildLineStringFromEdgeResults(results);
    }

    private LineString buildLineStringFromEdgeResults(List<Object[]> results) {
        // Delegate to RoutingService which handles edge direction (node vs source check)
        return routingService.assembleRouteFromEdgeRows(results);
    }

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

    // ── Inner types ───────────────────────────────────────────────────────────

    private record RouteCandidate(
            LineString geometry,
            List<RouteValidationResponse.ViolationDetail> violations,
            int waitDurationSec,
            Instant waitUntil,
            long totalTripSec
    ) {}

    private record WaitState(int waitDurationSec, Instant waitUntil) {}
}
