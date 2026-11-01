package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.enums.BreachType;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * Background breach worker. Consumes position-insert events and owns
 * zone_breach_log one-per-entry (60s extend window) plus best-effort
 * zone-avoiding reroute — in its OWN transaction, AFTER_COMMIT.
 *
 * Isolation is the point: a pgRouting query against missing blr_2po_* tables
 * aborts the whole Postgres txn, so running breach/reroute inside the ingest
 * txn turned in-zone fixes into HTTP 500s (Task 3 Major). Here every road
 * touch is best-effort and can never poison ingest.
 */
@Service
public class BreachWorker {

    private static final Logger log = LoggerFactory.getLogger(BreachWorker.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Best-effort duplicate-create rejections: two fixes racing one entry. */
    private static final java.util.concurrent.atomic.AtomicLong duplicateRejected =
            new java.util.concurrent.atomic.AtomicLong();

    /** Ops counter: duplicate breach-create attempts merged by the open-entry guard. */
    public static long duplicateRejectedCount() {
        return duplicateRejected.get();
    }
    /** Matches the inline map-matching off-route threshold (PositionTrackingService). */
    private static final double OFF_ROUTE_THRESHOLD_M = 50.0;
    /** An entry stays open while fixes keep arriving within this window (was: trigger V18). */
    private static final long EXTEND_WINDOW_SEC = 60L;

    /** Published by PositionTrackingService after a fix commits. All sources (incl. REPLAY) re-trigger. */
    public record PositionFix(long vehicleId, Long positionLogId,
                              double lat, double lng,
                              Instant recordedAt, PositionSource source) {}

    private final ZoneBreachLogRepository breachLogRepository;
    private final VehicleRepository vehicleRepository;
    private final ZoneRestrictionRepository zoneRepository;
    private final ZoneRestrictionRuleRepository ruleRepository;
    private final BreachService breachService;
    private final JdbcTemplate jdbcTemplate;
    private final IngestState state;

    public BreachWorker(ZoneBreachLogRepository breachLogRepository,
                        VehicleRepository vehicleRepository,
                        ZoneRestrictionRepository zoneRepository,
                        ZoneRestrictionRuleRepository ruleRepository,
                        BreachService breachService,
                        JdbcTemplate jdbcTemplate,
                        IngestState state) {
        this.breachLogRepository = breachLogRepository;
        this.vehicleRepository = vehicleRepository;
        this.zoneRepository = zoneRepository;
        this.ruleRepository = ruleRepository;
        this.breachService = breachService;
        this.jdbcTemplate = jdbcTemplate;
        this.state = state;
    }

    @Async("breachExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEvent(PositionFix fix) {
        try {
            evaluateZoneBreaches(fix);
        } catch (Exception e) {
            log.warn("BreachWorker zone evaluation failed for vehicle {}: {}",
                    fix.vehicleId(), e.getMessage());
        }
        try {
            evaluateOffRoute(fix);
        } catch (Exception e) {
            log.warn("BreachWorker off-route evaluation failed for vehicle {}: {}",
                    fix.vehicleId(), e.getMessage());
        }
    }

    // ── Zone breaches: ST_Covers one-per-entry, 60s extend window ────────────

    private void evaluateZoneBreaches(PositionFix fix) {
        Vehicle vehicle = vehicleRepository.findById(fix.vehicleId()).orElse(null);
        if (vehicle == null) {
            return;
        }
        for (Object[] zoneRow : matchZones(fix, vehicle.getVehicleClass().name())) {
            try {
                extendOrCreate(fix, vehicle, zoneRow);
            } catch (Exception e) {
                log.warn("BreachWorker breach persist failed for vehicle {} zone {}: {}",
                        fix.vehicleId(), zoneRow[0], e.getMessage());
            }
        }
    }

    /**
     * Ports fn_detect_zone_breach (V18): active zones covering the fix whose
     * rule matches vehicle class + IST day bitmask + time window. Pure PostGIS
     * on zone tables — never touches the road network.
     */
    private List<Object[]> matchZones(PositionFix fix, String vehicleClass) {
        try {
            java.time.ZonedDateTime ist = fix.recordedAt().atZone(IST);
            int dayBit = 1 << (ist.getDayOfWeek().getValue() - 1); // Mon=bit0 .. Sun=bit6
            java.sql.Time now = java.sql.Time.valueOf(ist.toLocalTime());

            return jdbcTemplate.query(
                    "SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type AS restriction_type " +
                    "FROM zone_restriction zr " +
                    "JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id " +
                    "WHERE zr.is_active = TRUE " +
                    "AND zrr.is_active = TRUE " +
                    "AND ST_Covers(zr.boundary, ST_SetSRID(ST_Point(?, ?), 4326)) " +
                    "AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = ?) " +
                    "AND ((zrr.days_of_week_bitmask & ?) > 0) " +
                    "AND ((zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL) " +
                    "OR (zrr.restriction_start_time <= zrr.restriction_end_time " +
                    "AND ? BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time) " +
                    "OR (zrr.restriction_start_time > zrr.restriction_end_time " +
                    "AND (? >= zrr.restriction_start_time OR ? <= zrr.restriction_end_time)))",
                    (rs, rowNum) -> new Object[]{
                            rs.getLong("zone_id"), rs.getLong("rule_id"),
                            rs.getString("restriction_type")},
                    fix.lng(), fix.lat(), vehicleClass, dayBit, now, now, now);
        } catch (Exception e) {
            log.warn("BreachWorker zone match failed for vehicle {}: {}", fix.vehicleId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private void extendOrCreate(PositionFix fix, Vehicle vehicle, Object[] zoneRow) {
        long vehicleId = fix.vehicleId();
        Long zoneId = ((Number) zoneRow[0]).longValue();
        Long ruleId = ((Number) zoneRow[1]).longValue();
        String restrictionType = (String) zoneRow[2];

        Instant cutoff = fix.recordedAt().minusSeconds(EXTEND_WINDOW_SEC);
        // Row lock: concurrent fixes for one vehicle+zone serialize here
        // instead of opening duplicate rows (one-per-entry guard).
        var open = breachLogRepository.findOpenBreachForUpdate(vehicleId, zoneId, cutoff);
        if (open.isPresent()) {
            ZoneBreachLog breach = open.get();
            breach.setEndTime(fix.recordedAt());
            breach.setDistanceM(coalesce(breach.getDistanceM()) + tickDistance(fix));
            breach.setPositionLogId(fix.positionLogId());
            breachLogRepository.save(breach);
            return;
        }

        ZoneRestriction zone = zoneRepository.findById(zoneId).orElse(null);
        if (zone == null) {
            return;
        }

        ZoneBreachLog breach = new ZoneBreachLog();
        breach.setVehicle(vehicle);
        breach.setZone(zone);
        ruleRepository.findById(ruleId).ifPresent(breach::setRule);
        breach.setPositionLogId(fix.positionLogId());
        breach.setBreachTime(fix.recordedAt());
        breach.setEndTime(fix.recordedAt());
        breach.setBreachType(mapBreachType(restrictionType));
        breach.setDistanceM(0.0);
        ZoneBreachLog saved;
        try {
            saved = breachLogRepository.save(breach);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Lost the race on a concurrent open entry: fold into the winner.
            duplicateRejected.incrementAndGet();
            breachLogRepository.findOpenBreach(vehicleId, zoneId, cutoff).ifPresent(winner -> {
                winner.setEndTime(fix.recordedAt());
                breachLogRepository.save(winner);
            });
            return;
        }

        try {
            breachService.computeReroute(saved, vehicleId, fix.lat(), fix.lng());
        } catch (Exception e) {
            // Best-effort: the breach row stays, the reroute is skipped.
            log.warn("BreachWorker reroute failed for breach {}: {}", saved.getId(), e.getMessage());
        }
    }

    private double tickDistance(PositionFix fix) {
        try {
            Double d = jdbcTemplate.queryForObject(
                    "SELECT ST_Distance(prev.position::geography, " +
                    "ST_SetSRID(ST_Point(?, ?), 4326)::geography) " +
                    "FROM vehicle_position_log prev " +
                    "WHERE prev.vehicle_id = ? AND prev.recorded_at < ? " +
                    "ORDER BY prev.recorded_at DESC, prev.id DESC LIMIT 1",
                    Double.class, fix.lng(), fix.lat(), fix.vehicleId(), fix.recordedAt());
            return d != null ? d : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ── Off-route: >50m twice in a row → zone-agnostic reroute ───────────────

    private void evaluateOffRoute(PositionFix fix) {
        long vehicleId = fix.vehicleId();
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
        Long routeId = vehicle != null ? vehicle.getActiveDispatchRouteId() : null;
        if (routeId == null) {
            state.clearOffRoute(vehicleId);
            return;
        }

        // Reset the streak when the vehicle's active route changes (was: BUG-NEW-009 inline).
        Long previousRouteId = state.lastRouteId(vehicleId).orElse(null);
        state.saveRouteId(vehicleId, routeId);
        if (previousRouteId != null && !previousRouteId.equals(routeId)) {
            state.clearOffRoute(vehicleId);
        }

        final double distM;
        try {
            Double d = jdbcTemplate.queryForObject(
                    "SELECT ST_Distance(ST_SetSRID(ST_Point(?, ?), 4326)::geography, " +
                    "planned_route_geometry::geography) FROM dispatch_route WHERE id = ?",
                    Double.class, fix.lng(), fix.lat(), routeId);
            if (d == null) {
                return;
            }
            distM = d;
        } catch (Exception e) {
            log.warn("BreachWorker off-route probe failed for vehicle {}: {}", vehicleId, e.getMessage());
            return;
        }

        if (distM > OFF_ROUTE_THRESHOLD_M) {
            if (state.incrOffRoute(vehicleId) >= 2) {
                state.clearOffRoute(vehicleId);
                try {
                    breachService.computeOffRouteReroute(vehicleId, fix.lat(), fix.lng());
                } catch (Exception e) {
                    log.warn("BreachWorker off-route reroute failed for vehicle {}: {}",
                            vehicleId, e.getMessage());
                }
            }
        } else {
            state.clearOffRoute(vehicleId);
        }
    }

    /**
     * Explicit mapping of every known restriction type (mirrors the
     * fn_detect_zone_breach CASE in V18). Unknown values fail loud — a
     * silent default would mislabel breaches instead of flagging bad data.
     */
    private static BreachType mapBreachType(String restrictionType) {
        if ("NO_ENTRY".equals(restrictionType)) {
            return BreachType.NO_ENTRY;
        }
        if ("TIME_RESTRICTED".equals(restrictionType)) {
            return BreachType.TIME_WINDOW;
        }
        if ("VEHICLE_CLASS_RESTRICTED".equals(restrictionType)) {
            return BreachType.VEHICLE_CLASS;
        }
        throw new com.zonepilot.backend.exception.ValidationException(
                "unknown restriction type '" + restrictionType + "'");
    }

    private static double coalesce(Double d) {
        return d != null ? d : 0.0;
    }
}
