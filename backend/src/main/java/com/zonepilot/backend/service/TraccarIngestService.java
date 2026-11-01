package com.zonepilot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.PositionQuarantine;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
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

    private final VehicleRepository vehicles;
    private final PositionTrackingService positions;
    private final QualityFilter quality;
    private final IngestState state;
    private final PositionQuarantineRepository quarantine;
    private final ObjectMapper mapper;

    public TraccarIngestService(VehicleRepository vehicles,
                                PositionTrackingService positions,
                                QualityFilter quality,
                                IngestState state,
                                PositionQuarantineRepository quarantine,
                                ObjectMapper mapper) {
        this.vehicles = vehicles;
        this.positions = positions;
        this.quality = quality;
        this.state = state;
        this.quarantine = quarantine;
        this.mapper = mapper;
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
        processFix(vehicleId, node.get("lat").asDouble(), node.get("lon").asDouble(),
                ts,
                node.hasNonNull("speedKmh") ? node.get("speedKmh").decimalValue() : null,
                accuracyOf(node),
                node.hasNonNull("heading") ? (short) node.get("heading").asInt() : null,
                source,
                node.hasNonNull("driverId") ? node.get("driverId").asLong() : null,
                node.hasNonNull("tourId") ? node.get("tourId").asLong() : null,
                node.hasNonNull("groupId") ? node.get("groupId").asLong() : null,
                payload);
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
        Vehicle vehicle = vehicles.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));
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
        quarantineTotal.incrementAndGet();
        quarantineByReason.computeIfAbsent(reason, r -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        log.warn("ingest.quarantine vehicle={} reason={}", vehicleId, reason);
    }
}
