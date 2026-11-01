package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.enums.BreachType;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.enums.RuleDirection;
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
    /** Corridor breach only after continuously outside the corridor this long. */
    private static final long CORRIDOR_OUTSIDE_SEC = 60L;

    /** vehicleId:zoneId -> first fix outside the corridor (streak start). */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> corridorOutsideSince =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** vehicleId:zoneId -> first fix of the current continuous inside-run. */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> zoneEntrySince =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** vehicleId:zoneId -> last inside fix (gap > window resets the entry). */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> zoneLastSeen =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** vehicleId -> zones the last fix was inside (zoneId -> ruleId), for EXIT detection. */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.Map<Long, Long>> insideByVehicle =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        java.util.Map<Long, Long> currentInside = new java.util.HashMap<>();
        for (Object[] zoneRow : matchZones(fix, vehicle.getVehicleClass().name())) {
            currentInside.put(((Number) zoneRow[0]).longValue(), ((Number) zoneRow[1]).longValue());
            try {
                extendOrCreate(fix, vehicle, zoneRow);
            } catch (Exception e) {
                log.warn("BreachWorker breach persist failed for vehicle {} zone {}: {}",
                        fix.vehicleId(), zoneRow[0], e.getMessage());
            }
        }
        // Entry/exit split: zones left since the last fix fire EXIT-direction rules.
        java.util.Map<Long, Long> previous =
                insideByVehicle.getOrDefault(fix.vehicleId(), java.util.Map.of());
        for (java.util.Map.Entry<Long, Long> exited : previous.entrySet()) {
            if (!currentInside.containsKey(exited.getKey())) {
                try {
                    createExitBreach(fix, vehicle, exited.getKey(), exited.getValue());
                } catch (Exception e) {
                    log.warn("BreachWorker exit breach failed for vehicle {} zone {}: {}",
                            fix.vehicleId(), exited.getKey(), e.getMessage());
                }
            }
        }
        insideByVehicle.put(fix.vehicleId(), currentInside);
        // Corridor rule: breach only after >60s continuously outside the corridor.
        Long routeId = vehicle.getActiveDispatchRouteId();
        if (routeId != null) {
            try {
                evaluateCorridorBreaches(fix, vehicle, routeId);
            } catch (Exception e) {
                log.warn("BreachWorker corridor evaluation failed for vehicle {}: {}",
                        fix.vehicleId(), e.getMessage());
            }
        }
    }

    /**
     * Ports fn_detect_zone_breach (V18): active zones covering the fix whose
     * rule matches vehicle class + IST day bitmask + time window. Pure PostGIS
     * on zone tables — never touches the road network.
     *
     * CORRIDOR zones are excluded here (own ST_DWithin check in
     * matchCorridorZones); CIRCLE zones match within corridor_width_m
     * (radius) of the boundary centroid.
     */
    private List<Object[]> matchZones(PositionFix fix, String vehicleClass) {
        try {
            java.time.ZonedDateTime ist = fix.recordedAt().atZone(IST);
            int dayBit = 1 << (ist.getDayOfWeek().getValue() - 1); // Mon=bit0 .. Sun=bit6
            java.sql.Time now = java.sql.Time.valueOf(ist.toLocalTime());

            return nullSafe(jdbcTemplate.query(
                    "SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type AS restriction_type " +
                    "FROM zone_restriction zr " +
                    "JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id " +
                    "WHERE zr.is_active = TRUE " +
                    "AND zrr.is_active = TRUE " +
                    "AND (zr.geometry_type IS NULL OR zr.geometry_type <> 'CORRIDOR') " +
                    "AND (ST_Covers(zr.boundary, ST_SetSRID(ST_Point(?, ?), 4326)) " +
                    "OR (zr.geometry_type = 'CIRCLE' AND ST_DWithin(" +
                    "ST_SetSRID(ST_Point(?, ?), 4326)::geography, " +
                    "ST_Centroid(zr.boundary)::geography, COALESCE(zr.corridor_width_m, 0)))) " +
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
                    fix.lng(), fix.lat(), fix.lng(), fix.lat(), vehicleClass, dayBit, now, now, now));
        } catch (Exception e) {
            log.warn("BreachWorker zone match failed for vehicle {}: {}", fix.vehicleId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Null-safe wrapper: an unstubbed/misbehaving JdbcTemplate yields no zones, never an NPE. */
    private List<Object[]> nullSafe(List<Object[]> rows) {
        return rows != null ? rows : Collections.emptyList();
    }

    /**
     * CORRIDOR check: ST_DWithin(position, corridor centerline, width).
     * Each row is [zoneId, ruleId, restrictionType, inside]. A null/empty
     * result (e.g. road tables missing) degrades to "no corridor info".
     */
    private List<Object[]> matchCorridorZones(PositionFix fix, String vehicleClass, long routeId) {
        try {
            java.time.ZonedDateTime ist = fix.recordedAt().atZone(IST);
            int dayBit = 1 << (ist.getDayOfWeek().getValue() - 1); // Mon=bit0 .. Sun=bit6
            java.sql.Time now = java.sql.Time.valueOf(ist.toLocalTime());

            List<Object[]> rows = jdbcTemplate.query(
                    "SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type AS restriction_type, " +
                    "ST_DWithin(ST_SetSRID(ST_Point(?, ?), 4326)::geography, " +
                    "dr.planned_route_geometry::geography, zr.corridor_width_m) AS inside " +
                    "FROM zone_restriction zr " +
                    "JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id " +
                    "JOIN dispatch_route dr ON dr.id = zr.corridor_route_id " +
                    "WHERE zr.is_active = TRUE " +
                    "AND zrr.is_active = TRUE " +
                    "AND zr.geometry_type = 'CORRIDOR' " +
                    "AND zr.corridor_route_id = ? " +
                    "AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = ?) " +
                    "AND ((zrr.days_of_week_bitmask & ?) > 0) " +
                    "AND ((zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL) " +
                    "OR (zrr.restriction_start_time <= zrr.restriction_end_time " +
                    "AND ? BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time) " +
                    "OR (zrr.restriction_start_time > zrr.restriction_end_time " +
                    "AND (? >= zrr.restriction_start_time OR ? <= zrr.restriction_end_time)))",
                    (rs, rowNum) -> new Object[]{
                            rs.getLong("zone_id"), rs.getLong("rule_id"),
                            rs.getString("restriction_type"), rs.getBoolean("inside")},
                    fix.lng(), fix.lat(), routeId, vehicleClass, dayBit, now, now, now);
            return rows != null ? rows : Collections.emptyList();
        } catch (Exception e) {
            log.warn("BreachWorker corridor match failed for vehicle {}: {}", fix.vehicleId(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Corridor streak: an inside fix resets, an outside fix starts (or
     * continues) the streak, and only &gt;60s continuously outside opens
     * (or extends) a breach. Immediate creation would false-positive on
     * every GPS jitter at the corridor edge.
     */
    private void evaluateCorridorBreaches(PositionFix fix, Vehicle vehicle, long routeId) {
        for (Object[] corridorRow : matchCorridorZones(fix, vehicle.getVehicleClass().name(), routeId)) {
            long vehicleId = fix.vehicleId();
            Long zoneId = ((Number) corridorRow[0]).longValue();
            boolean inside = corridorRow.length > 3 && Boolean.TRUE.equals(corridorRow[3]);
            String key = vehicleId + ":" + zoneId;
            if (inside) {
                corridorOutsideSince.remove(key);
                continue;
            }
            Instant streakStart = corridorOutsideSince.computeIfAbsent(key, k -> fix.recordedAt());
            if (fix.recordedAt().minusSeconds(CORRIDOR_OUTSIDE_SEC).isBefore(streakStart)) {
                continue; // outside, but not yet >60s continuously
            }
            try {
                extendOrCreate(fix, vehicle,
                        new Object[]{corridorRow[0], corridorRow[1], corridorRow[2]});
            } catch (Exception e) {
                log.warn("BreachWorker corridor breach persist failed for vehicle {} zone {}: {}",
                        vehicleId, zoneId, e.getMessage());
            }
        }
    }

    private void extendOrCreate(PositionFix fix, Vehicle vehicle, Object[] zoneRow) {
        long vehicleId = fix.vehicleId();
        Long zoneId = ((Number) zoneRow[0]).longValue();
        Long ruleId = ((Number) zoneRow[1]).longValue();
        String restrictionType = (String) zoneRow[2];

        // Dwell runs on the continuous inside-run: a gap > window restarts it.
        String entryKey = vehicleId + ":" + zoneId;
        Instant lastSeen = zoneLastSeen.get(entryKey);
        if (lastSeen == null || fix.recordedAt().minusSeconds(EXTEND_WINDOW_SEC).isAfter(lastSeen)) {
            zoneEntrySince.put(entryKey, fix.recordedAt());
        }
        zoneLastSeen.put(entryKey, fix.recordedAt());

        // One-per-entry guard: serialize concurrent fixes for one vehicle+zone
        // on a transaction-scoped advisory lock BEFORE the locked open-entry
        // read. Two racers can't both see "no open entry" and double-insert;
        // the loser blocks here, then finds the winner's row and extends it.
        // Auto-released at commit/rollback; never held across transactions.
        // (A partial unique index can't express the 60s extend window, and the
        // row lock below only serializes readers that find a row — not the
        // both-empty race.) Lock failure degrades to the row lock alone.
        try {
            // IDs are server-side longs, never user input (same rule as BUG-NEW-006).
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + vehicleId + ", " + zoneId + ")");
        } catch (Exception e) {
            log.debug("Breach advisory lock unavailable for vehicle {} zone {}: {}",
                    vehicleId, zoneId, e.getMessage());
        }

        // Entry/exit split: entering an EXIT-only zone is not a breach —
        // the violation fires on the outside transition (createExitBreach).
        var ruleOpt = ruleRepository.findById(ruleId);
        RuleDirection direction = ruleOpt
                .map(r -> r.getDirection() != null ? r.getDirection() : RuleDirection.BOTH)
                .orElse(RuleDirection.BOTH);
        if (direction == RuleDirection.EXIT) {
            return;
        }
        int dwellMin = ruleOpt
                .map(r -> r.getDwellThresholdMin() != null ? r.getDwellThresholdMin() : 0)
                .orElse(0);

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

        // Dwell: breach only after inside continuously >= threshold. Extending
        // an open entry is unaffected — the threshold gates creation only.
        if (dwellMin > 0) {
            Instant entered = zoneEntrySince.getOrDefault(entryKey, fix.recordedAt());
            if (fix.recordedAt().minusSeconds((long) dwellMin * 60L).isBefore(entered)) {
                return;
            }
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

    /**
     * EXIT-direction rules fire on the inside→outside transition, never on
     * entry. Same one-per-entry guard as the inside path: extend the open
     * entry when one exists, otherwise open a single exit row.
     */
    private void createExitBreach(PositionFix fix, Vehicle vehicle, Long zoneId, Long ruleId) {
        long vehicleId = fix.vehicleId();
        var ruleOpt = ruleRepository.findById(ruleId);
        RuleDirection direction = ruleOpt
                .map(r -> r.getDirection() != null ? r.getDirection() : RuleDirection.BOTH)
                .orElse(RuleDirection.BOTH);
        if (direction != RuleDirection.EXIT) {
            return;
        }
        try {
            jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + vehicleId + ", " + zoneId + ")");
        } catch (Exception e) {
            log.debug("Breach advisory lock unavailable for vehicle {} zone {}: {}",
                    vehicleId, zoneId, e.getMessage());
        }
        Instant cutoff = fix.recordedAt().minusSeconds(EXTEND_WINDOW_SEC);
        var open = breachLogRepository.findOpenBreachForUpdate(vehicleId, zoneId, cutoff);
        if (open.isPresent()) {
            ZoneBreachLog breach = open.get();
            breach.setEndTime(fix.recordedAt());
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
        ruleOpt.ifPresent(breach::setRule);
        breach.setPositionLogId(fix.positionLogId());
        breach.setBreachTime(fix.recordedAt());
        breach.setEndTime(fix.recordedAt());
        breach.setBreachType(mapBreachType(zone.getRestrictionType() != null
                ? zone.getRestrictionType().name() : "NO_ENTRY"));
        breach.setDistanceM(0.0);
        ZoneBreachLog saved = breachLogRepository.save(breach);
        try {
            breachService.computeReroute(saved, vehicleId, fix.lat(), fix.lng());
        } catch (Exception e) {
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
