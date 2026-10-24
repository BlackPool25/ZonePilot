package com.zonepilot.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.PositionQuarantine;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.repository.PositionQuarantineRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
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
 * Slice 3: consumes the Traccar MQTT feed ({@code zonepilot/{depot}/vehicles/{id}/...}).
 * Position frames go through the quality filter into {@code recordPosition(TRACCAR)};
 * failures land in the quarantine table — never silently dropped, never scored.
 */
@Service
public class TraccarIngestService {

    private static final Logger log = LoggerFactory.getLogger(TraccarIngestService.class);

    private final VehicleRepository vehicles;
    private final VehiclePositionLogRepository positionLogs;
    private final PositionTrackingService positions;
    private final QualityFilter quality;
    private final IngestState state;
    private final PositionQuarantineRepository quarantine;
    private final TickBroadcaster broadcaster;
    private final ObjectMapper mapper;

    public TraccarIngestService(VehicleRepository vehicles,
                                VehiclePositionLogRepository positionLogs,
                                PositionTrackingService positions,
                                QualityFilter quality,
                                IngestState state,
                                PositionQuarantineRepository quarantine,
                                TickBroadcaster broadcaster,
                                ObjectMapper mapper) {
        this.vehicles = vehicles;
        this.positionLogs = positionLogs;
        this.positions = positions;
        this.quality = quality;
        this.state = state;
        this.quarantine = quarantine;
        this.broadcaster = broadcaster;
        this.mapper = mapper;
    }

    /** Entry point for the MQTT adapter: topic + raw JSON payload. */
    @Transactional
    public void handle(String topic, String payload) {
        String[] seg = topic.split("/");
        // zonepilot/{depot}/vehicles/{id}/{position|event|status}
        if (seg.length != 5 || !"zonepilot".equals(seg[0]) || !"vehicles".equals(seg[2])) {
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
        double lat = node.get("lat").asDouble();
        double lng = node.get("lon").asDouble();
        Instant ts = parseTs(node.get("ts"));
        BigDecimal speed = node.hasNonNull("speedKmh") ? node.get("speedKmh").decimalValue() : null;
        Double accuracy = null;
        if (node.hasNonNull("accuracy")) {
            accuracy = node.get("accuracy").asDouble();
        } else if (node.hasNonNull("hdop")) {
            accuracy = node.get("hdop").asDouble() * 5.0;
        }

        QualityFilter.Result check = quality.check(vehicleId, lat, lng, ts, speed, accuracy);
        if (!check.accepted()) {
            quarantine(vehicleId, ts, check.reason(), payload);
            return;
        }
        // QoS1 redelivery of an already-stored (vehicle, recorded_at): benign, ack and move on.
        if (ts != null && positionLogs.existsByVehicleIdAndRecordedAt(vehicleId, ts)) {
            log.debug("ingest.duplicate vehicle={} ts={}", vehicleId, ts);
            return;
        }
        try {
            var response = positions.recordPosition(vehicleId, lat, lng, ts, speed,
                    node.hasNonNull("heading") ? (short) node.get("heading").asInt() : null,
                    PositionSource.TRACCAR);
            state.saveFix(vehicleId, response.getPosition().getLatitude(),
                    response.getPosition().getLongitude(), response.getPosition().getRecordedAt());
            Vehicle v = vehicle.get();
            if (v.getDepot() != null) {
                broadcaster.tick(v.getDepot().getId(), vehicleId,
                        response.getPosition().getLatitude(), response.getPosition().getLongitude(),
                        speed, response.getPosition().getRecordedAt(),
                        Boolean.TRUE.equals(response.getBreachDetected()));
            }
        } catch (DataIntegrityViolationException dup) {
            // QoS1 redelivery of an already-stored (vehicle, recorded_at): benign, ack and move on.
            log.debug("ingest.duplicate vehicle={} ts={}", vehicleId, ts);
        } catch (RuntimeException e) {
            // Never poison-loop on QoS1 redelivery: quarantine records the failure.
            quarantine(vehicleId, ts, "INGEST_ERROR", payload);
            log.warn("ingest.error vehicle={} cause={}", vehicleId, e.toString());
        }
    }

    private static Instant parseTs(JsonNode ts) {
        if (ts == null || ts.isNull()) return Instant.now();
        if (ts.isNumber()) return Instant.ofEpochMilli(ts.asLong());
        try {
            return Instant.parse(ts.asText());
        } catch (Exception e) {
            return Instant.now();
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
        log.warn("ingest.quarantine vehicle={} reason={}", vehicleId, reason);
    }
}
