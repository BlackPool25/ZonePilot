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
    private com.zonepilot.backend.repository.DispatchRouteAttemptRepository attemptRepository;
    private RouteSimilarityService similarityService;

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

    public void setAttemptRepository(com.zonepilot.backend.repository.DispatchRouteAttemptRepository r) {
        this.attemptRepository = r;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSimilarityService(RouteSimilarityService s) { this.similarityService = s; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void injectAttemptRepository(
            com.zonepilot.backend.repository.DispatchRouteAttemptRepository r) {
        this.attemptRepository = r;
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

        if (!response.getCompliant()) {
            try {
                Set<Long> violatedZoneIds = winner.violations.stream()
                        .filter(v -> v.getZoneId() != null)
                        .map(RouteValidationResponse.ViolationDetail::getZoneId)
                        .collect(java.util.stream.Collectors.toSet());
                if (!violatedZoneIds.isEmpty()) {
                    LineString altGeom = computeRouteAvoidingZones(sourceNode, targetNode, violatedZoneIds);
                    List<RouteValidationResponse.ViolationDetail> altViolations = validateRouteAgainstZones(vehicleId, altGeom);
                    if (altViolations.isEmpty()) {
                        response.setAlternativeRouteGeoJson(altGeom.toText());
                        response.setAlternativeRouteUnavailable(false);
                    } else {
                        response.setAlternativeRouteUnavailable(true);
                    }
                } else {
                    response.setAlternativeRouteUnavailable(true);
                }
            } catch (Exception e) {
                log.info("Could not compute strictly compliant alternative route: {}", e.getMessage());
                response.setAlternativeRouteUnavailable(true);
            }

            if (winner.waitDurationSec > 0) {
                response.setWaitDurationSec(winner.waitDurationSec);
                response.setWaitUntil(winner.waitUntil != null ? winner.waitUntil.toString() : null);
            }
        }

        DispatchRoute dispatchRoute = new DispatchRoute();
        dispatchRoute.setVehicle(vehicle);
        dispatchRoute.setOriginPoint(originPoint);
        dispatchRoute.setDestinationPoint(destPoint);
        dispatchRoute.setPlannedRouteGeometry(winner.geometry);
        dispatchRoute.setStatus(response.getCompliant() ? RouteStatus.COMPLIANT : RouteStatus.NON_COMPLIANT);
        dispatchRoute.setValidationTimestamp(departureTime);
        if (winner.totalTripSec > 0) {
            dispatchRoute.setTravelDurationSec(winner.totalTripSec);
            response.setTravelDurationSec(winner.totalTripSec);
        }
        if (winner.waitDurationSec > 0) {
            dispatchRoute.setWaitUntil(winner.waitUntil);
            dispatchRoute.setWaitDurationSec(winner.waitDurationSec);
        }

        DispatchRoute saved = dispatchRouteRepository.save(dispatchRoute);

        // Google traffic enrichment + similarity (nullable when key missing/fails)
        try {
            java.util.List<double[]> entryPts = queryZoneEntryPoints(winner.geometry, winner.violations);
            TimePredictionService.TrafficEnrichment enrich =
                    timePredictionService.fetchTrafficEnrichment(winner.geometry, entryPts, departureTime);
            if (enrich != null && enrich.durationSec() >= 0) {
                dispatchRoute.setGoogleDurationSec(enrich.durationSec());
                dispatchRoute.setGoogleStaticDurationSec(enrich.staticDurationSec());
                dispatchRoute.setGoogleDistanceMeters(enrich.distanceMeters());
                dispatchRoute.setGoogleDelaySec(enrich.delaySec());
                response.setGoogleDurationSec(enrich.durationSec());
                response.setGoogleStaticDurationSec(enrich.staticDurationSec());
                response.setGoogleDistanceMeters(enrich.distanceMeters());
                response.setGoogleDelaySec(enrich.delaySec());
                if (enrich.googlePolyline() != null && similarityService != null) {
                    String googleWkt = RouteSimilarityService.decodePolylineToWkt(enrich.googlePolyline());
                    double sim = similarityService.score(winner.geometry.toText(), googleWkt);
                    dispatchRoute.setGoogleSimilarityScore(sim);
                    response.setGoogleSimilarityScore(sim);
                }
                dispatchRouteRepository.save(dispatchRoute);
            }
        } catch (Exception e) {
            log.warn("Google enrichment skipped: {}", e.getMessage());
        }

        // Versioned attempt history with violation ids + reject reason
        java.util.List<RouteValidationResponse.AttemptDetail> attemptDtos = new java.util.ArrayList<>();
        for (AttemptTrace a : winner.attempts) {
            RouteValidationResponse.AttemptDetail dto = new RouteValidationResponse.AttemptDetail();
            dto.setAttemptNumber(a.attemptNumber());
            dto.setPenalisedZoneIds(a.penalisedZoneIds());
            dto.setViolatedZoneIds(a.violatedZoneIds());
            dto.setAccepted(a.accepted());
            dto.setReason(a.reason());
            attemptDtos.add(dto);
            if (attemptRepository != null) {
                try {
                    com.zonepilot.backend.entity.DispatchRouteAttempt row =
                            new com.zonepilot.backend.entity.DispatchRouteAttempt();
                    row.setDispatchRouteId(saved.getId());
                    row.setAttemptNumber(a.attemptNumber());
                    row.setPlannedRouteGeometry(a.geometry());
                    row.setPenalisedZoneIds(a.penalisedZoneIds().toString());
                    row.setViolatedZoneIds(a.violatedZoneIds().toString());
                    row.setAccepted(a.accepted());
                    row.setRejectReason(a.reason());
                    attemptRepository.save(row);
                } catch (Exception e) {
                    log.warn("Attempt persist skipped: {}", e.getMessage());
                }
            }
        }
        response.setAttempts(attemptDtos);

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
     * Iterative zone-avoidance routing (max 3 Dijkstra attempts):
     * attempt 1 = direct; each retry accumulates ALL violated zones so far
     * with huge cost (1e9x) so Dijkstra avoids them unless disconnected.
     * Endpoint inside a zone breaks to wait-state to avoid infinite loop.
     */
    private RouteCandidate computeBestRoute(Long sourceNode, Long targetNode,
                                             Long vehicleId, Instant departureTime) {
        Set<Long> penalised = new java.util.LinkedHashSet<>();
        List<AttemptTrace> attempts = new ArrayList<>();
        LineString route = computeRouteAvoidingZones(sourceNode, targetNode, penalised);
        List<RouteValidationResponse.ViolationDetail> violations = List.of();

        for (int attempt = 1; attempt <= 3; attempt++) {
            violations = validateRouteAgainstZones(vehicleId, route);
            Set<Long> violatedIds = violations.stream()
                    .filter(v -> v.getZoneId() != null)
                    .map(RouteValidationResponse.ViolationDetail::getZoneId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            boolean accepted = violations.isEmpty();
            attempts.add(new AttemptTrace(attempt, Set.copyOf(penalised),
                    List.copyOf(violatedIds), accepted,
                    accepted ? "ACCEPTED" : "REJECTED_ZONES_" + violatedIds, route));
            if (accepted) {
                long travelSec = timePredictionService.predictTotalDurationSec(
                        route, List.of(), departureTime);
                return new RouteCandidate(route, violations, 0, null, travelSec, List.copyOf(attempts));
            }
            penalised.addAll(violatedIds);
            if (attempt == 3) break;
            // Endpoint inside a restricted zone: no reroute can fix it
            if (isEndpointInRestrictedZone(sourceNode, targetNode)) break;
            try {
                route = computeRouteAvoidingZones(sourceNode, targetNode, penalised);
            } catch (RoutingException e) {
                log.warn("Retry {} failed (penalised {}): {}", attempt, penalised, e.getMessage());
                break;
            }
        }

        // Wait-state fallback using last route/violations
        List<double[]> zoneEntryPoints = queryZoneEntryPoints(route, violations);
        List<Long> cumulativeArrivals = timePredictionService.predictCumulativeArrivalsSec(
                route, zoneEntryPoints, departureTime);
        WaitState waitState = computeWaitState(violations, departureTime, cumulativeArrivals);
        long travelSec = timePredictionService.predictTotalDurationSec(
                route, zoneEntryPoints, departureTime);
        long totalWithWait = (travelSec > 0 ? travelSec : 0) + waitState.waitDurationSec;

        return new RouteCandidate(route, violations, waitState.waitDurationSec,
                waitState.waitUntil, totalWithWait, List.copyOf(attempts));
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
     * True when origin or destination vertex lies inside any zone boundary.
     * Used to stop retries that can never succeed.
     */
    private boolean isEndpointInRestrictedZone(Long sourceNode, Long targetNode) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM zone_restriction zr WHERE EXISTS ("
                    + "SELECT 1 FROM blr_2po_4pgr_vertices_pgr v WHERE v.id IN (?, ?) "
                    + "AND ST_Within(v.the_geom, zr.boundary))",
                    Integer.class, sourceNode, targetNode);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Runs pgr_dijkstra with huge cost penalty on all penalised zones.
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
                "THEN cost_time_sec * 1e9 ELSE cost_time_sec END AS cost, " +
                "CASE WHEN reverse_cost > 0 AND ST_Intersects(the_geom, (SELECT ST_Union(boundary) FROM zone_restriction WHERE id IN (%s))) " +
                "THEN cost_time_sec * 1e9 WHEN reverse_cost > 0 THEN cost_time_sec ELSE reverse_cost END AS reverse_cost " +
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

        if (results == null || results.isEmpty()) {
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
            long totalTripSec,
            List<AttemptTrace> attempts
    ) {}

    record AttemptTrace(
            int attemptNumber,
            Set<Long> penalisedZoneIds,
            List<Long> violatedZoneIds,
            boolean accepted,
            String reason,
            LineString geometry
    ) {}

    private record WaitState(int waitDurationSec, Instant waitUntil) {}
}
