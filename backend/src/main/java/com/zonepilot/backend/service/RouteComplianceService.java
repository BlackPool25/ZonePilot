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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RouteComplianceService {

    private static final Logger log = LoggerFactory.getLogger(RouteComplianceService.class);

    private static final int MAX_ROUTE_ATTEMPTS = 5;
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

        Instant departureTime = Instant.now();

        // Epic 4: 5-attempt recursive routing with zone penalty escalation
        RouteCandidate winner = computeBestRoute(
                sourceNode, targetNode, vehicleId, departureTime, new HashSet<>(), 0);

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

    // ── Epic 4: 5-attempt recursive routing ──────────────────────────────────

    /**
     * Recursively computes routes, penalising violated zones on each attempt.
     *
     * Exit A: a route with 0 violations → return immediately.
     * Exit B: after MAX_ROUTE_ATTEMPTS, compare all candidates by
     *         (travelDuration + waitDuration) and return the minimum.
     */
    private RouteCandidate computeBestRoute(Long sourceNode, Long targetNode,
                                             Long vehicleId, Instant departureTime,
                                             Set<Long> penalisedZones, int attempt) {
        if (attempt >= MAX_ROUTE_ATTEMPTS) {
            // Should not reach here — caller collects candidates and picks best
            throw new RoutingException("Max route attempts exceeded");
        }

        LineString routeGeometry = computeRouteAvoidingZones(sourceNode, targetNode, penalisedZones);
        List<RouteValidationResponse.ViolationDetail> violations =
                validateRouteAgainstZones(vehicleId, routeGeometry);

        if (violations.isEmpty()) {
            // Exit A: clean route found
            long travelSec = timePredictionService.predictTotalDurationSec(
                    routeGeometry, List.of(), departureTime);
            return new RouteCandidate(routeGeometry, violations, 0, null, travelSec);
        }

        // Collect zone entry points for Google Routes API timing
        List<double[]> zoneEntryPoints = queryZoneEntryPoints(routeGeometry, violations);
        List<Long> cumulativeArrivals = timePredictionService.predictCumulativeArrivalsSec(
                routeGeometry, zoneEntryPoints, departureTime);

        // Compute wait time for this route (time until first curfew ends)
        WaitState waitState = computeWaitState(violations, departureTime, cumulativeArrivals);
        long travelSec = timePredictionService.predictTotalDurationSec(
                routeGeometry, zoneEntryPoints, departureTime);
        long totalWithWait = (travelSec > 0 ? travelSec : 0) + waitState.waitDurationSec;

        RouteCandidate thisCandidate = new RouteCandidate(
                routeGeometry, violations, waitState.waitDurationSec, waitState.waitUntil, totalWithWait);

        if (attempt + 1 >= MAX_ROUTE_ATTEMPTS) {
            return thisCandidate;
        }

        // Penalise all violated zones and recurse
        Set<Long> nextPenalised = new HashSet<>(penalisedZones);
        for (RouteValidationResponse.ViolationDetail v : violations) {
            if (v.getZoneId() != null) nextPenalised.add(v.getZoneId());
        }

        try {
            RouteCandidate nextCandidate = computeBestRoute(
                    sourceNode, targetNode, vehicleId, departureTime, nextPenalised, attempt + 1);

            // Exit A propagation: if next attempt found a clean route, return it
            if (nextCandidate.violations.isEmpty()) return nextCandidate;

            // Exit B: pick the candidate with lower total trip time
            return nextCandidate.totalTripSec <= thisCandidate.totalTripSec
                    ? nextCandidate : thisCandidate;

        } catch (RoutingException e) {
            log.warn("Attempt {} routing failed: {}", attempt + 1, e.getMessage());
            return thisCandidate;
        }
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
                "SELECT seq, edge, cost, ST_AsText(pt.the_geom) AS geom " +
                "FROM pgr_dijkstra(?, CAST(? AS BIGINT), CAST(? AS BIGINT), directed := true) AS di " +
                "JOIN blr_2po_4pgr pt ON pt.id = di.edge ORDER BY seq",
                (rs, rowNum) -> new Object[]{
                        rs.getInt("seq"), rs.getLong("edge"),
                        rs.getDouble("cost"), rs.getString("geom")
                },
                pgRoutingEdgeSql, sourceNode, targetNode);

        if (results.isEmpty()) {
            throw new RoutingException("No route found avoiding zones: " + zoneIds);
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
                log.warn("Failed to parse route edge geometry: {}", geomText);
            }
        }
        if (coordinates.size() < 2) {
            throw new RoutingException("Route geometry has insufficient points");
        }
        return geometryFactory.createLineString(coordinates.toArray(new Coordinate[0]));
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
