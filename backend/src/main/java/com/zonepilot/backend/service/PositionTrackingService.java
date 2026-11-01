package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.AppUserRepository;
import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PositionTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PositionTrackingService.class);

    // Cross-track threshold (meters)
    // ≤ 30m: on-route (may be GPS glitch on opposite side of dual carriageway)
    private static final double GLITCH_SNAP_THRESHOLD_M  = 30.0;

    private final VehicleRepository vehicleRepository;
    private final VehiclePositionLogRepository positionLogRepository;
    private final DispatchRouteRepository dispatchRouteRepository;
    private final TourRepository tourRepository;
    private final JdbcTemplate jdbcTemplate;
    private final GeometryFactory geometryFactory;
    private final ApplicationEventPublisher events;
    private final TickBroadcaster broadcaster;
    private final DriverRepository driverRepository;
    private final AppUserRepository appUserRepository;
    private final GroupScope scope;

    public PositionTrackingService(VehicleRepository vehicleRepository,
                                   VehiclePositionLogRepository positionLogRepository,
                                   DispatchRouteRepository dispatchRouteRepository,
                                   TourRepository tourRepository,
                                   JdbcTemplate jdbcTemplate,
                                   ApplicationEventPublisher events,
                                   TickBroadcaster broadcaster,
                                   DriverRepository driverRepository,
                                   AppUserRepository appUserRepository,
                                   GroupScope scope) {
        this.vehicleRepository = vehicleRepository;
        this.positionLogRepository = positionLogRepository;
        this.dispatchRouteRepository = dispatchRouteRepository;
        this.tourRepository = tourRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.events = events;
        this.broadcaster = broadcaster;
        this.driverRepository = driverRepository;
        this.appUserRepository = appUserRepository;
        this.scope = scope;
    }

    @Transactional
    public PositionRecordResponse recordPosition(Long vehicleId, double lat, double lng,
                                                  Instant timestamp, BigDecimal speedKmh,
                                                  Short headingDeg, PositionSource source) {
        return recordPosition(vehicleId, null, null, null, lat, lng,
                timestamp, speedKmh, headingDeg, source);
    }

    /**
     * Strict-keys ingest: every fix carries driver+tour+group. Null keys resolve
     * from the vehicle's active dispatch route; with no active tour the fix is
     * rejected (400, not a 500 FK blowup). Same-ms burst redelivery of an
     * already-stored (vehicle, tour, recorded_at) is ignored, not a 500.
     * Mid-tour driver/vehicle swaps are accepted: keys are scoped per fix,
     * the tour-scoped unique only dedupes identical redeliveries.
     */
    @Transactional
    public PositionRecordResponse recordPosition(Long vehicleId, Long driverId, Long tourId, Long groupId,
                                                  double lat, double lng, Instant timestamp,
                                                  BigDecimal speedKmh, Short headingDeg, PositionSource source) {
        return doRecord(vehicleId, driverId, tourId, groupId, lat, lng,
                timestamp, speedKmh, headingDeg, source, true);
    }

    /**
     * Replay path: same binding + persistence as live ingest but no per-fix
     * tick — the replay caller emits one summary tick at the end.
     */
    @Transactional
    public PositionRecordResponse recordReplayFix(Long vehicleId, Long driverId, Long tourId, Long groupId,
                                                  double lat, double lng, Instant timestamp,
                                                  BigDecimal speedKmh, Short headingDeg, PositionSource source) {
        return doRecord(vehicleId, driverId, tourId, groupId, lat, lng,
                timestamp, speedKmh, headingDeg, source, false);
    }

    private PositionRecordResponse doRecord(Long vehicleId, Long driverId, Long tourId, Long groupId,
                                             double lat, double lng, Instant timestamp,
                                             BigDecimal speedKmh, Short headingDeg, PositionSource source,
                                             boolean broadcast) {
        validateBangaloreCoordinates(lat, lng);

        if (timestamp != null && timestamp.isAfter(Instant.now().plusSeconds(300))) {
            throw new ValidationException("Timestamp cannot be more than 5 minutes in the future");
        }

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));

        if (!vehicle.getIsActive()) {
            throw new ValidationException("Vehicle is inactive and cannot record positions.");
        }

        Tour tour = resolveTour(tourId, vehicle);
        // Client keys are untrusted: drivers bind to their owned tour,
        // managers must own the tour's group (spoofed keys rejected here).
        Long[] keys = bindKeys(tour, tourId, driverId, groupId);
        driverId = keys[0];
        groupId = keys[1];

        // Epic 3: cross-track check against active route
        double[] snappedPos = applyRouteMapMatching(vehicle, lat, lng, headingDeg);
        double finalLat = snappedPos[0];
        double finalLng = snappedPos[1];

        Instant recordedAt = timestamp != null ? timestamp : Instant.now();
        recordedAt = recordedAt.truncatedTo(ChronoUnit.MILLIS);

        // Burst redelivery of an already-stored fix: ignore, don't 500.
        if (positionLogRepository.existsByVehicleIdAndTourIdAndRecordedAt(vehicleId, tour.getId(), recordedAt)) {
            return duplicateResponse(vehicleId, tour.getId(), recordedAt);
        }

        Point position = geometryFactory.createPoint(new Coordinate(finalLng, finalLat));

        VehiclePositionLog positionLog = new VehiclePositionLog();
        positionLog.setVehicleId(vehicleId);
        positionLog.setDriverId(driverId);
        positionLog.setTourId(tour.getId());
        positionLog.setGroupId(groupId);
        positionLog.setPosition(position);
        positionLog.setRecordedAt(recordedAt);
        positionLog.setSpeedKmh(speedKmh);
        positionLog.setHeadingDeg(headingDeg);
        positionLog.setSource(source);

        VehiclePositionLog saved;
        try {
            saved = positionLogRepository.saveAndFlush(positionLog);
        } catch (DataIntegrityViolationException dup) {
            // Lost the race with a concurrent redelivery: ignore, don't 500.
            return duplicateResponse(vehicleId, tour.getId(), recordedAt);
        }

        // Task 5: breach + reroute run in the background worker (own txn,
        // AFTER_COMMIT). No pgRouting query may run here: a failed road query
        // aborts the whole Postgres txn and the ingest would 500 (Task 3 Major).
        events.publishEvent(new BreachWorker.PositionFix(
                vehicleId, saved.getId(), finalLat, finalLng, recordedAt, source));

        PositionRecordResponse response = new PositionRecordResponse();
        response.setBreachDetected(false);

        PositionRecordResponse.PositionDetail posDetail = new PositionRecordResponse.PositionDetail();
        posDetail.setLatitude(finalLat);
        posDetail.setLongitude(finalLng);
        posDetail.setRecordedAt(recordedAt);
        posDetail.setSpeedKmh(speedKmh);
        posDetail.setSource(source.name());
        response.setPosition(posDetail);

        // Live pipe: every accepted fix fans out as a derived tick frame
        // on the fix's group topic (/topic/group/{id}, JWT-scoped).
        // Replay passes broadcast=false and emits one summary tick instead.
        if (broadcast) {
            broadcaster.tick(groupId, vehicleId, finalLat, finalLng,
                    speedKmh, recordedAt, false);
        }

        return response;
    }

    /**
     * Key binding for untrusted client keys. Driver callers ignore all
     * client keys and bind to their owned tour (spoofed tourIds 403);
     * manager callers with an explicit tourId must own the tour's group,
     * and explicit driver/group keys must match the tour. Null-auth callers
     * (replay, background workers) keep the old defaulting behavior.
     */
    private Long[] bindKeys(Tour tour, Long tourId, Long driverId, Long groupId) {
        DepotPrincipal caller = DepotScope.current();
        if (caller != null && caller.role() == Role.DRIVER) {
            Long userId = appUserRepository.findByEmail(caller.username())
                    .map(AppUser::getId)
                    .orElseThrow(() -> new AccessDeniedException("Unknown driver login"));
            Driver me = driverRepository.findByUserId(userId)
                    .orElseThrow(() -> new AccessDeniedException("No driver profile linked to this login"));
            if (tour.getDriver() == null || !tour.getDriver().getId().equals(me.getId())) {
                throw new AccessDeniedException("Tour is not your assignment");
            }
            return new Long[]{me.getId(), tour.getGroup().getId()};
        }
        if (tourId != null) {
            if (caller != null) {
                scope.requireGroupAccess(tour.getGroup());
            }
            // Untrusted keys on every pipe: MQTT frames carry no caller, so a
            // forged groupId must fail here — never bind a fix to another group.
            if (groupId != null && !groupId.equals(tour.getGroup().getId())) {
                throw new ValidationException("groupId does not match tour " + tour.getId());
            }
            // Explicit driverId is never validated: mid-tour driver/vehicle
            // swaps are accepted (keys are scoped per fix; the tour key rules).
            // Attribution: the STORED driver is the fix caller when known
            // (driver logins bind to their owned tour above), else the tour's
            // assigned driver — per-fix keys, not a roster snapshot.
        }
        if (driverId == null) {
            driverId = tour.getDriver().getId();
        }
        if (groupId == null) {
            groupId = tour.getGroup().getId();
        }
        return new Long[]{driverId, groupId};
    }

    /**
     * Null keys resolve from the vehicle's active dispatch route (group tour
     * assign flow links route -&gt; tour). No active tour: 400, not a 500 FK blowup.
     * Closed tours reject fixes: history is immutable once COMPLETED.
     */
    private Tour resolveTour(Long tourId, Vehicle vehicle) {
        Tour tour;
        if (tourId != null) {
            tour = tourRepository.findById(tourId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        } else {
            Long routeId = vehicle.getActiveDispatchRouteId();
            if (routeId != null) {
                Tour linked = dispatchRouteRepository.findById(routeId)
                        .map(DispatchRoute::getTour)
                        .orElse(null);
                if (linked != null) {
                    tour = linked;
                } else {
                    throw new ValidationException("Position requires an active tour for vehicle " + vehicle.getId());
                }
            } else {
                throw new ValidationException("Position requires an active tour for vehicle " + vehicle.getId());
            }
        }
        if (tour.getStatus() == TourStatus.COMPLETED) {
            throw new ValidationException("Tour " + tour.getId() + " is COMPLETED, history is closed");
        }
        return tour;
    }

    /**
     * HTTP duplicate redelivery: the row is already stored, so answer the
     * stored fix (200) instead of 400. Quarantine rejections still 400 —
     * only a stored row returns a response here.
     */
    public java.util.Optional<PositionRecordResponse> findStoredFix(
            Long vehicleId, Instant recordedAt) {
        if (vehicleId == null || recordedAt == null) {
            return java.util.Optional.empty();
        }
        return positionLogRepository.findFirstByVehicleIdAndRecordedAt(vehicleId, recordedAt)
                .map(this::responseOf);
    }

    private PositionRecordResponse responseOf(VehiclePositionLog existing) {
        PositionRecordResponse response = new PositionRecordResponse();
        response.setBreachDetected(false);

        PositionRecordResponse.PositionDetail posDetail = new PositionRecordResponse.PositionDetail();
        posDetail.setLatitude(existing.getPosition().getY());
        posDetail.setLongitude(existing.getPosition().getX());
        posDetail.setRecordedAt(existing.getRecordedAt());
        posDetail.setSpeedKmh(existing.getSpeedKmh());
        posDetail.setSource(existing.getSource().name());
        response.setPosition(posDetail);
        return response;
    }

    /**
     * Already-stored fix redelivered: rebuild the accepted response from the
     * stored row (no reroute recompute — the first insert already handled it).
     */
    private PositionRecordResponse duplicateResponse(Long vehicleId, Long tourId, Instant recordedAt) {
        VehiclePositionLog existing = positionLogRepository
                .findByVehicleIdAndTourIdAndRecordedAt(vehicleId, tourId, recordedAt)
                .orElseThrow(() -> new ValidationException(
                        "Duplicate position for vehicle " + vehicleId + " at " + recordedAt));
        return responseOf(existing);
    }

    /**
     * Epic 3 map-matching: pure coordinate correction. Queries only
     * dispatch_route (never the road network) and keeps no counter state —
     * off-route streak counting + reroute live in BreachWorker, so a failed
     * probe here can never poison the ingest txn.
     *
     * ≤ 30m: on-route; reversed heading (|delta| &gt; 150°) snaps to the
     * route's nearest point (GPS glitch on opposite carriageway).
     * Anything beyond passes through raw for the worker to judge.
     */
    private double[] applyRouteMapMatching(Vehicle vehicle, double lat, double lng, Short headingDeg) {
        Long routeId = vehicle.getActiveDispatchRouteId();
        if (routeId == null) {
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
            }
            return new double[]{lat, lng};

        } catch (Exception e) {
            log.warn("Route map-matching failed for vehicle {}: {}", vehicle.getId(), e.getMessage());
            return new double[]{lat, lng};
        }
    }

    private void validateBangaloreCoordinates(double lat, double lng) {
        if (!GeoBounds.inBounds(lat, lng)) {
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
        return positionLogRepository.findLatestByVehicleId(vehicleId)
                .map(this::toPositionResponse)
                .orElseGet(() -> {
                    Vehicle vehicle = vehicleRepository.findById(vehicleId)
                            .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
                    com.zonepilot.backend.dto.response.PositionResponse r = new com.zonepilot.backend.dto.response.PositionResponse();
                    r.setVehicleId(vehicleId);
                    r.setRecordedAt(vehicle.getCreatedAt());
                    r.setSpeedKmh(java.math.BigDecimal.ZERO);
                    r.setHeadingDeg((short) 0);
                    r.setSource("NONE");
                    return r;
                });
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
