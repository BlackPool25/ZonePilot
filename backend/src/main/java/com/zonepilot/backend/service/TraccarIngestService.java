package com.zonepilot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.PositionQuarantine;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ConflictException;
import com.zonepilot.backend.exception.RateLimitExceededException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.DispatchRouteRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.PositionQuarantineRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live pipe: consumes the Traccar MQTT feed ({@code zonepilot/fleet/vehicles/{id}/...})
 * and the HTTP burst endpoint. Position frames go through the quality filter
 * into strict-key {@code recordPosition}; failures land in quarantine — never
 * silently dropped, never scored. Broadcast is per-group inside recordPosition.
 */
@Service
public class TraccarIngestService {

    private static final Logger log = LoggerFactory.getLogger(TraccarIngestService.class);

    /** Ops counter: quarantined fixes (spike = feed or map regression). */
    private static final java.util.concurrent.atomic.AtomicLong quarantineTotal =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            quarantineByReason = new java.util.concurrent.ConcurrentHashMap<>();

    /** Ops counter: quarantined fixes (spike = feed or map regression). */
    public static long quarantineCount() {
        return quarantineTotal.get();
    }

    /** Ops counter: quarantines by reason (TELEPORT/BAD_TS/...). */
    public static long quarantineCount(String reason) {
        java.util.concurrent.atomic.AtomicLong n = quarantineByReason.get(reason);
        return n != null ? n.get() : 0L;
    }

    /** Ingest schema version the server speaks; anything else quarantines. */
    public static final int INGEST_SCHEMA_VERSION = 1;

    /** Per-vehicle/per-driver fix throttle (fixes/min). Tunable for tests. */
    static volatile int vehicleLimitPerMin = 10_000;
    static volatile int driverLimitPerMin = 10_000;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>
            throttleBuckets = new java.util.concurrent.ConcurrentHashMap<>();

    /** Test hook: tune the per-vehicle/per-driver throttle. */
    public static void setThrottleLimits(int perVehiclePerMin, int perDriverPerMin) {
        vehicleLimitPerMin = perVehiclePerMin;
        driverLimitPerMin = perDriverPerMin;
    }

    /** Test hook: clear throttle buckets. */
    public static void resetThrottle() {
        throttleBuckets.clear();
    }

    private static boolean admit(String bucket, int limit) {
        long now = System.currentTimeMillis();
        java.util.ArrayDeque<Long> q =
                throttleBuckets.computeIfAbsent(bucket, k -> new java.util.ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private final VehicleRepository vehicles;
    private final PositionTrackingService positions;
    private final QualityFilter quality;
    private final IngestState state;
    private final PositionQuarantineRepository quarantine;
    private final ObjectMapper mapper;
    private final DispatchRouteRepository dispatchRoutes;
    private final TourRepository tourRepo;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public TraccarIngestService(VehicleRepository vehicles,
                                PositionTrackingService positions,
                                QualityFilter quality,
                                IngestState state,
                                PositionQuarantineRepository quarantine,
                                ObjectMapper mapper) {
        this(vehicles, positions, quality, state, quarantine, mapper,
                null, null, AuditService.noop(), new IdempotencyService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TraccarIngestService(VehicleRepository vehicles,
                                PositionTrackingService positions,
                                QualityFilter quality,
                                IngestState state,
                                PositionQuarantineRepository quarantine,
                                ObjectMapper mapper,
                                DispatchRouteRepository dispatchRoutes,
                                TourRepository tourRepo,
                                AuditService audit,
                                IdempotencyService idempotency) {
        this.vehicles = vehicles;
        this.positions = positions;
        this.quality = quality;
        this.state = state;
        this.quarantine = quarantine;
        this.mapper = mapper;
        this.dispatchRoutes = dispatchRoutes;
        this.tourRepo = tourRepo;
        this.audit = audit == null ? AuditService.noop() : audit;
        this.idempotency = idempotency == null ? new IdempotencyService() : idempotency;
    }

    /** Entry point for the MQTT adapter: topic + raw JSON payload. */
    @Transactional
    public void handle(String topic, String payload) {
        String[] seg = topic.split("/");
        // zonepilot/fleet/vehicles/{id}/{position|event|status}
        if (seg.length != 5 || !"zonepilot".equals(seg[0]) || !"fleet".equals(seg[1])
                || !"vehicles".equals(seg[2])) {
            log.warn("ingest.reject reason=MALFORMED_TOPIC topic={}", topic);
            return;
        }
        long vehicleId;
        try {
            vehicleId = Long.parseLong(seg[3]);
        } catch (NumberFormatException e) {
            log.warn("ingest.reject reason=BAD_TOPIC_VEHICLE topic={}", topic);
            return;
        }
        String kind = seg[4];
        if ("position".equals(kind)) {
            handlePosition(vehicleId, payload);
        } else if ("event".equals(kind) || "status".equals(kind)) {
            // Traccar event names reused (overspeed, fuel drop, geofence enter/exit):
            // validated + logged here; derived handling is a later slice.
            log.info("ingest.{} vehicle={} payload={}", kind, vehicleId, payload);
        } else {
            log.warn("ingest.reject reason=UNKNOWN_KIND topic={}", topic);
        }
    }

    private void handlePosition(long vehicleId, String payload) {
        JsonNode node;
        try {
            node = mapper.readTree(payload);
        } catch (Exception e) {
            quarantine(vehicleId, null, "MALFORMED_JSON", payload);
            return;
        }
        Optional<Vehicle> vehicle = vehicles.findById(vehicleId);
        if (vehicle.isEmpty()) {
            quarantine(vehicleId, null, "UNKNOWN_VEHICLE", payload);
            return;
        }
        if (!node.hasNonNull("lat") || !node.hasNonNull("lon")) {
            quarantine(vehicleId, null, "MISSING_COORDS", payload);
            return;
        }
        if (node.has("vehicleId") && node.get("vehicleId").asLong(-1) != vehicleId) {
            quarantine(vehicleId, null, "TOPIC_MISMATCH", payload);
            return;
        }
        // Ingest schema gate: a device on a newer/older frame version must
        // quarantine loudly (VERSION_CONFLICT), never silently mis-parse.
        if (node.hasNonNull("v") && node.get("v").asInt(-1) != INGEST_SCHEMA_VERSION) {
            quarantine(vehicleId, null, "VERSION_CONFLICT", payload);
            return;
        }
        // Per-device auth: once a vehicle has a provisioned key, frames must
        // carry it. Unprovisioned vehicles keep the legacy open pipe.
        String expectedKey = vehicle.get().getDeviceApiKey();
        if (expectedKey != null && !expectedKey.isBlank()) {
            String presented = node.hasNonNull("deviceKey") ? node.get("deviceKey").asText() : null;
            if (!expectedKey.equals(presented)) {
                quarantine(vehicleId, null, "AUTH_REJECT", payload);
                return;
            }
        }
        // Per-vehicle/per-driver throttle: shed load to quarantine, never
        // poison-loop on QoS1 redelivery.
        Long payloadDriver = node.hasNonNull("driverId") ? node.get("driverId").asLong() : null;
        if (!admit("v:" + vehicleId, vehicleLimitPerMin)
                || (payloadDriver != null && !admit("d:" + payloadDriver, driverLimitPerMin))) {
            quarantine(vehicleId, null, "THROTTLED", payload);
            return;
        }
        // Present-but-unparseable ts quarantines as BAD_TS: silently storing
        // it as now() would fabricate a fix time the device never sent.
        Instant ts = parseTsOrNull(node.get("ts"));
        if (ts == null && node.has("ts") && !node.get("ts").isNull()) {
            quarantine(vehicleId, null, "BAD_TS", payload);
            return;
        }
        if (ts == null) {
            ts = Instant.now();
        }
        // Live pipe default is TRACCAR; batched replay frames carry source=REPLAY.
        PositionSource source = PositionSource.TRACCAR;
        if (node.hasNonNull("source")) {
            try {
                source = PositionSource.valueOf(node.get("source").asText().toUpperCase());
            } catch (IllegalArgumentException e) {
                quarantine(vehicleId, null, "BAD_SOURCE", payload);
                return;
            }
            if (source != PositionSource.TRACCAR && source != PositionSource.LIVE
                    && source != PositionSource.REPLAY) {
                quarantine(vehicleId, null, "BAD_SOURCE", payload);
                return;
            }
        }
        // Server-side tour binding: the topic vehicleId resolves the tour
        // from the vehicle's active dispatch route. Client tourId/groupId are
        // untrusted — a mismatch quarantines instead of rebinding the fix.
        Long clientTourId = node.hasNonNull("tourId") ? node.get("tourId").asLong() : null;
        Long clientGroupId = node.hasNonNull("groupId") ? node.get("groupId").asLong() : null;
        Long boundTourId = clientTourId;
        Long boundGroupId = clientGroupId;
        com.zonepilot.backend.entity.Tour serverTour = serverTourOf(vehicle.get());
        if (serverTour != null) {
            if (clientTourId != null && !clientTourId.equals(serverTour.getId())) {
                quarantine(vehicleId, null, "TOUR_MISMATCH", payload);
                return;
            }
            if (clientGroupId != null && serverTour.getGroup() != null
                    && !clientGroupId.equals(serverTour.getGroup().getId())) {
                // Forged groupId is ignored (never bound to another group).
                quarantine(vehicleId, null, "GROUP_MISMATCH", payload);
                return;
            }
            boundTourId = serverTour.getId();
            boundGroupId = serverTour.getGroup() != null ? serverTour.getGroup().getId() : null;
        }
        String opId = node.hasNonNull("opId") ? node.get("opId").asText() : null;
        if (opId != null) {
            try {
                idempotency.claim(opId);
            } catch (ConflictException dup) {
                // QoS1 redelivery of an already-applied op: ack and move on.
                log.debug("ingest.duplicate-op vehicle={} opId={}", vehicleId, opId);
                return;
            }
        }
        processFix(vehicleId, node.get("lat").asDouble(), node.get("lon").asDouble(),
                ts,
                node.hasNonNull("speedKmh") ? node.get("speedKmh").decimalValue() : null,
                accuracyOf(node),
                node.hasNonNull("heading") ? (short) node.get("heading").asInt() : null,
                source,
                node.hasNonNull("driverId") ? node.get("driverId").asLong() : null,
                boundTourId,
                boundGroupId,
                payload);
    }

    /** Server tour for a topic vehicle: active dispatch route → linked tour. */
    private com.zonepilot.backend.entity.Tour serverTourOf(Vehicle vehicle) {
        if (dispatchRoutes == null || vehicle.getActiveDispatchRouteId() == null) {
            return null;
        }
        try {
            return dispatchRoutes.findById(vehicle.getActiveDispatchRouteId())
                    .map(DispatchRoute::getTour)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * HTTP ingest (PWA burst upload, replay): same quality gate as MQTT, but
     * rejections surface as 4xx instead of silent quarantine rows. A retry of
     * an already-stored fix answers the stored row (200), not 400.
     */
    public PositionRecordResponse ingest(long vehicleId, double lat, double lng, Instant ts,
                                         BigDecimal speedKmh, Double accuracyM, Short headingDeg,
                                         PositionSource source, Long driverId, Long tourId,
                                         Long groupId) {
        return ingest(vehicleId, lat, lng, ts, speedKmh, accuracyM, headingDeg,
                source, driverId, tourId, groupId, null);
    }

    /**
     * HTTP ingest with client idempotency key: offline-queue redeliveries
     * with a duplicate opId answer 409 instead of double-applying.
     */
    public PositionRecordResponse ingest(long vehicleId, double lat, double lng, Instant ts,
                                         BigDecimal speedKmh, Double accuracyM, Short headingDeg,
                                         PositionSource source, Long driverId, Long tourId,
                                         Long groupId, String opId) {
        Vehicle vehicle = vehicles.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
        idempotency.claim(opId);
        if (!admit("v:" + vehicleId, vehicleLimitPerMin)
                || (driverId != null && !admit("d:" + driverId, driverLimitPerMin))) {
            throw new RateLimitExceededException(
                    "ingest throttled for vehicle " + vehicleId);
        }
        Optional<PositionRecordResponse> accepted = processFix(vehicle.getId(), lat, lng, ts,
                speedKmh, accuracyM, headingDeg, source, driverId, tourId, groupId, null);
        if (accepted.isPresent()) {
            return accepted.get();
        }
        // Not quarantined-with-cause: a retry of a stored fix. Answer 200.
        return positions.findStoredFix(vehicle.getId(), ts)
                .orElseThrow(() -> new ValidationException(
                        "Position rejected by ingest quality gate (see quarantine)"));
    }

    /**
     * Shared MQTT + HTTP fix path. Empty = quarantined (reason already stored);
     * the MQTT caller drops it, the HTTP caller 400s.
     */
    private Optional<PositionRecordResponse> processFix(long vehicleId, double lat, double lng,
                                                        Instant ts, BigDecimal speedKmh,
                                                        Double accuracyM, Short headingDeg,
                                                        PositionSource source, Long driverId,
                                                        Long tourId, Long groupId,
                                                        String rawPayload) {
        QualityFilter.Result check = quality.check(vehicleId, lat, lng, ts, speedKmh, accuracyM);
        if (!check.accepted()) {
            quarantine(vehicleId, ts, check.reason(), rawPayload);
            return Optional.empty();
        }
        try {
            var response = positions.recordPosition(vehicleId, driverId, tourId, groupId,
                    lat, lng, ts, speedKmh, headingDeg, source);
            state.saveFix(vehicleId, response.getPosition().getLatitude(),
                    response.getPosition().getLongitude(), response.getPosition().getRecordedAt());
            return Optional.of(response);
        } catch (DataIntegrityViolationException dup) {
            // QoS1 redelivery racing a concurrent insert: the row is stored.
            // MQTT acks and moves on; HTTP answers the stored row (200) below.
            log.debug("ingest.duplicate vehicle={} ts={}", vehicleId, ts);
            if (rawPayload == null) {
                return positions.findStoredFix(vehicleId, ts);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            if (rawPayload == null) {
                throw e;
            }
            // Never poison-loop on QoS1 redelivery: quarantine records the failure.
            quarantine(vehicleId, ts, "INGEST_ERROR", rawPayload);
            log.warn("ingest.error vehicle={} cause={}", vehicleId, e.toString());
            return Optional.empty();
        }
    }

    private static Double accuracyOf(JsonNode node) {
        if (node.hasNonNull("accuracy")) {
            return node.get("accuracy").asDouble();
        } else if (node.hasNonNull("hdop")) {
            return node.get("hdop").asDouble() * 5.0;
        }
        return null;
    }

    /**
     * Absent ts defaults to now(); present-but-unparseable returns null so
     * the caller quarantines BAD_TS instead of fabricating now().
     */
    private static Instant parseTsOrNull(JsonNode ts) {
        if (ts == null || ts.isNull()) return null;
        if (ts.isNumber()) return Instant.ofEpochMilli(ts.asLong());
        try {
            return Instant.parse(ts.asText());
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    void quarantine(long vehicleId, Instant recordedAt, String reason, String payload) {
        PositionQuarantine row = new PositionQuarantine();
        row.setVehicleId(vehicleId);
        row.setRecordedAt(recordedAt);
        row.setReason(reason);
        row.setPayload(payload);
        quarantine.save(row);
        audit.log("ingest", "INGEST_REJECT", null, null,
                reason + " vehicle=" + vehicleId, null, "mqtt");
        quarantineTotal.incrementAndGet();
        quarantineByReason.computeIfAbsent(reason, r -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        log.warn("ingest.quarantine vehicle={} reason={}", vehicleId, reason);
    }
}
