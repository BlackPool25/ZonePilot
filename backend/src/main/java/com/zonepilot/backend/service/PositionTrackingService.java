package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class PositionTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PositionTrackingService.class);

    private static final double BANGALORE_MIN_LAT = 12.8;
    private static final double BANGALORE_MAX_LAT = 13.2;
    private static final double BANGALORE_MIN_LNG = 77.4;
    private static final double BANGALORE_MAX_LNG = 77.8;

    // Cross-track thresholds (meters)
    // ≤ 30m: on-route (may be GPS glitch on opposite side of dual carriageway)
    // > 50m for 2 consecutive pings: hard off-route → trigger reroute
    private static final double GLITCH_SNAP_THRESHOLD_M  = 30.0;
    private static final double OFF_ROUTE_THRESHOLD_M    = 50.0;

    private final VehicleRepository vehicleRepository;
    private final VehiclePositionLogRepository positionLogRepository;
    private final ZoneBreachLogRepository breachLogRepository;
    private final DispatchRouteRepository dispatchRouteRepository;
    private final BreachService breachService;
    private final JdbcTemplate jdbcTemplate;
    private final GeometryFactory geometryFactory;

    // Tracks consecutive off-route ping count per vehicleId
    private final ConcurrentHashMap<Long, Integer> offRoutePingCount = new ConcurrentHashMap<>();

    public PositionTrackingService(VehicleRepository vehicleRepository,
                                   VehiclePositionLogRepository positionLogRepository,
                                   ZoneBreachLogRepository breachLogRepository,
                                   DispatchRouteRepository dispatchRouteRepository,
                                   BreachService breachService,
                                   JdbcTemplate jdbcTemplate) {
        this.vehicleRepository = vehicleRepository;
        this.positionLogRepository = positionLogRepository;
        this.breachLogRepository = breachLogRepository;
        this.dispatchRouteRepository = dispatchRouteRepository;
        this.breachService = breachService;
        this.jdbcTemplate = jdbcTemplate;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    @Transactional
    public PositionRecordResponse recordPosition(Long vehicleId, double lat, double lng,
                                                  Instant timestamp, BigDecimal speedKmh,
                                                  Short headingDeg, PositionSource source) {
        validateBangaloreCoordinates(lat, lng);

        if (timestamp != null && timestamp.isAfter(Instant.now().plusSeconds(300))) {
            throw new ValidationException("Timestamp cannot be more than 5 minutes in the future");
        }

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));

        if (!vehicle.getIsActive()) {
            throw new ValidationException("Vehicle is inactive and cannot record positions.");
        }

        // Epic 3: cross-track check against active route
        double[] snappedPos = applyRouteMapMatching(vehicle, lat, lng, headingDeg);
        double finalLat = snappedPos[0];
        double finalLng = snappedPos[1];

        Instant recordedAt = timestamp != null ? timestamp : Instant.now();
        Point position = geometryFactory.createPoint(new Coordinate(finalLng, finalLat));

        VehiclePositionLog positionLog = new VehiclePositionLog();
        positionLog.setVehicleId(vehicleId);
        positionLog.setPosition(position);
        positionLog.setRecordedAt(recordedAt);
        positionLog.setSpeedKmh(speedKmh);
        positionLog.setHeadingDeg(headingDeg);
        positionLog.setSource(source);

        VehiclePositionLog saved = positionLogRepository.save(positionLog);

        List<ZoneBreachLog> breaches;
        if (saved.getId() != null) {
            breaches = breachLogRepository.findByPositionLogId(saved.getId());
            if (breaches.isEmpty()) {
                breaches = breachLogRepository.findByVehicleIdAndBreachTime(vehicleId, recordedAt);
            }
        } else {
            log.warn("Position log ID was null after save (partitioned table); falling back to vehicleId+timestamp query");
            breaches = breachLogRepository.findByVehicleIdAndBreachTime(vehicleId, recordedAt);
        }

        PositionRecordResponse response = new PositionRecordResponse();
        response.setBreachDetected(!breaches.isEmpty());

        PositionRecordResponse.PositionDetail posDetail = new PositionRecordResponse.PositionDetail();
        posDetail.setLatitude(finalLat);
        posDetail.setLongitude(finalLng);
        posDetail.setRecordedAt(recordedAt);
        posDetail.setSpeedKmh(speedKmh);
        posDetail.setSource(source.name());
        response.setPosition(posDetail);

        if (!breaches.isEmpty()) {
            List<PositionRecordResponse.BreachDetail> breachDetails = new ArrayList<>();
            for (ZoneBreachLog breach : breaches) {
                breachService.computeReroute(breach, vehicleId, finalLat, finalLng);
                PositionRecordResponse.BreachDetail detail = new PositionRecordResponse.BreachDetail();
                detail.setBreachId(breach.getId());
                detail.setZoneId(breach.getZone().getId());
                detail.setZoneName(breach.getZone().getName());
                detail.setBreachType(breach.getBreachType().name());
                if (breach.getResolvedRouteGeometry() != null) {
                    detail.setRerouteGeoJson(breach.getResolvedRouteGeometry().toText());
                }
                breachDetails.add(detail);
            }
            response.setBreaches(breachDetails);
        }

        return response;
    }

    /**
     * Epic 3 map-matching logic:
     *
     * 1. If vehicle has no active route → pass through unchanged.
     * 2. Query ST_Distance (geography) between ping and planned route geometry.
     * 3. ≤ 30m: on-route. If heading is reversed (|delta| > 150°), snap to route's
     *    nearest point (GPS glitch on opposite carriageway). Reset off-route counter.
     * 4. > 50m: increment consecutive off-route counter.
     *    - 1st ping: tolerate (could be single glitch).
     *    - 2nd consecutive ping: hard off-route → trigger reroute via BreachService.
     * 5. Between 30m and 50m: tolerate, reset counter.
     */
    private double[] applyRouteMapMatching(Vehicle vehicle, double lat, double lng, Short headingDeg) {
        Long routeId = vehicle.getActiveDispatchRouteId();
        if (routeId == null) {
            offRoutePingCount.remove(vehicle.getId());
            return new double[]{lat, lng};
        }

        try {
            // Query cross-track distance and nearest point on route in one shot
            List<Object[]> rows = jdbcTemplate.query(
                    "SELECT " +
                    "  ST_Distance(ST_SetSRID(ST_Point(?, ?), 4326)::geography, " +
                    "              planned_route_geometry::geography) AS dist_m, " +
                    "  ST_Y(ST_ClosestPoint(planned_route_geometry, ST_SetSRID(ST_Point(?, ?), 4326))) AS snap_lat, " +
                    "  ST_X(ST_ClosestPoint(planned_route_geometry, ST_SetSRID(ST_Point(?, ?), 4326))) AS snap_lng, " +
                    "  ST_Azimuth(ST_PointN(planned_route_geometry, 1), " +
                    "             ST_PointN(planned_route_geometry, -1)) AS route_azimuth " +
                    "FROM dispatch_route WHERE id = ?",
                    (rs, rowNum) -> new Object[]{
                            rs.getDouble("dist_m"),
                            rs.getDouble("snap_lat"),
                            rs.getDouble("snap_lng"),
                            rs.getDouble("route_azimuth")
                    },
                    lng, lat, lng, lat, lng, lat, routeId);

            if (rows.isEmpty()) {
                return new double[]{lat, lng};
            }

            double distM       = (double) rows.get(0)[0];
            double snapLat     = (double) rows.get(0)[1];
            double snapLng     = (double) rows.get(0)[2];
            double routeAzRad  = (double) rows.get(0)[3]; // radians, 0=north

            if (distM <= GLITCH_SNAP_THRESHOLD_M) {
                offRoutePingCount.remove(vehicle.getId());

                // Check if heading is reversed (GPS glitch on opposite carriageway)
                if (headingDeg != null && headingDeg != 0) {
                    double routeHeadingDeg = Math.toDegrees(routeAzRad);
                    double delta = Math.abs(((headingDeg - routeHeadingDeg) + 540) % 360 - 180);
                    if (delta > 150) {
                        // Reversed heading within 30m → GPS glitch, snap to route
                        log.debug("GPS glitch (reversed heading) for vehicle {} — snapping to route", vehicle.getId());
                        return new double[]{snapLat, snapLng};
                    }
                }
                return new double[]{lat, lng};

            } else if (distM > OFF_ROUTE_THRESHOLD_M) {
                int count = offRoutePingCount.merge(vehicle.getId(), 1, Integer::sum);
                log.debug("Vehicle {} off-route by {}m (consecutive pings: {})", vehicle.getId(), distM, count);

                if (count >= 2) {
                    // Two consecutive pings > 50m off-route → trigger reroute
                    offRoutePingCount.remove(vehicle.getId());
                    log.info("Vehicle {} confirmed off-route ({}m) — triggering reroute", vehicle.getId(), distM);
                    triggerOffRouteReroute(vehicle.getId(), lat, lng);
                }
                return new double[]{lat, lng};

            } else {
                // 30m < dist ≤ 50m: tolerate, reset counter
                offRoutePingCount.remove(vehicle.getId());
                return new double[]{lat, lng};
            }

        } catch (Exception e) {
            log.warn("Route map-matching failed for vehicle {}: {}", vehicle.getId(), e.getMessage());
            return new double[]{lat, lng};
        }
    }

    /**
     * Triggers a reroute for a vehicle that is confirmed off-route.
     * Creates a synthetic ZoneBreachLog-free reroute by delegating to BreachService
     * via a direct reroute call using the vehicle's active dispatch route destination.
     */
    private void triggerOffRouteReroute(Long vehicleId, double lat, double lng) {
        try {
            // Reuse BreachService's destination-finding + zone-avoiding route logic
            // by passing a null breach (off-route reroute, not a zone breach)
            breachService.computeOffRouteReroute(vehicleId, lat, lng);
        } catch (Exception e) {
            log.warn("Off-route reroute failed for vehicle {}: {}", vehicleId, e.getMessage());
        }
    }

    private void validateBangaloreCoordinates(double lat, double lng) {
        if (lat < BANGALORE_MIN_LAT || lat > BANGALORE_MAX_LAT
                || lng < BANGALORE_MIN_LNG || lng > BANGALORE_MAX_LNG) {
            throw new ValidationException(
                    "Coordinates (" + lat + ", " + lng + ") are outside Bangalore bounding box");
        }
    }

    public List<com.zonepilot.backend.dto.response.PositionResponse> getPositionHistory(
            Long vehicleId, Instant from, Instant to) {
        List<VehiclePositionLog> logs;
        if (from != null && to != null) {
            logs = positionLogRepository.findByVehicleIdAndRecordedAtBetween(vehicleId, from, to);
        } else {
            logs = positionLogRepository.findByVehicleIdOrderByRecordedAtDesc(vehicleId);
        }
        return logs.stream().map(this::toPositionResponse).collect(Collectors.toList());
    }

    public com.zonepilot.backend.dto.response.PositionResponse getLatestPosition(Long vehicleId) {
        VehiclePositionLog posLog = positionLogRepository.findLatestByVehicleId(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Position", "vehicleId", vehicleId));
        return toPositionResponse(posLog);
    }

    private com.zonepilot.backend.dto.response.PositionResponse toPositionResponse(VehiclePositionLog posLog) {
        com.zonepilot.backend.dto.response.PositionResponse r = new com.zonepilot.backend.dto.response.PositionResponse();
        r.setId(posLog.getId());
        r.setVehicleId(posLog.getVehicleId());
        if (posLog.getPosition() != null) {
            r.setLatitude(posLog.getPosition().getY());
            r.setLongitude(posLog.getPosition().getX());
        }
        r.setRecordedAt(posLog.getRecordedAt());
        r.setSpeedKmh(posLog.getSpeedKmh());
        r.setHeadingDeg(posLog.getHeadingDeg());
        r.setSource(posLog.getSource().name());
        return r;
    }
}
