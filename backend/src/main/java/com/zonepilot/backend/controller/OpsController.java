package com.zonepilot.backend.controller;

import com.zonepilot.backend.auth.DepotPrincipal;
import com.zonepilot.backend.auth.DepotScope;
import com.zonepilot.backend.auth.Role;
import org.springframework.security.access.AccessDeniedException;
import com.zonepilot.backend.config.MqttConfig;
import com.zonepilot.backend.dto.response.ApiResponse;
import com.zonepilot.backend.entity.PositionQuarantine;
import com.zonepilot.backend.repository.AuditLogRepository;
import com.zonepilot.backend.repository.PositionQuarantineRepository;
import com.zonepilot.backend.service.OsmImportService;
import com.zonepilot.backend.service.PartitionMonitor;
import com.zonepilot.backend.service.PartitionRolloverService;
import com.zonepilot.backend.service.ReplayPurgeService;
import com.zonepilot.backend.service.ReplayService;
import com.zonepilot.backend.service.TraccarIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ops endpoints (OWNER only): saturation signals, quarantine dead-letter
 * reads, partition health + rollover, replay purge, audit tail, OSM import
 * version. Every number here is a dashboard input, not a debug print.
 */
@RestController
@RequestMapping("/api/ops")
@Tag(name = "Ops", description = "OWNER ops endpoints: saturation, quarantine, partitions, replay, audit")
public class OpsController {

    private final PositionQuarantineRepository quarantine;
    private final AuditLogRepository audits;
    private final PartitionMonitor partitions;
    private final PartitionRolloverService rollover;
    private final ReplayPurgeService replayPurge;
    private final OsmImportService osmImports;

    public OpsController(PositionQuarantineRepository quarantine,
                         AuditLogRepository audits,
                         PartitionMonitor partitions,
                         PartitionRolloverService rollover,
                         ReplayPurgeService replayPurge,
                         OsmImportService osmImports) {
        this.quarantine = quarantine;
        this.audits = audits;
        this.partitions = partitions;
        this.rollover = rollover;
        this.replayPurge = replayPurge;
        this.osmImports = osmImports;
    }

    private static void requireOwner() {
        DepotPrincipal p = DepotScope.current();
        if (p == null || p.role() != Role.OWNER) {
            throw new AccessDeniedException("OWNER login required");
        }
    }

    @GetMapping("/saturation")
    @Operation(summary = "Ingest saturation signals: MQTT discards, quarantine counts, replay runs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saturation() {
        requireOwner();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mqttDiscarded", MqttConfig.rejectedCount());
        out.put("quarantineTotal", TraccarIngestService.quarantineCount());
        out.put("replayRuns", ReplayService.replayRunCount());
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (String reason : new String[]{"TELEPORT", "BAD_TS", "AUTH_REJECT",
                "TOUR_MISMATCH", "GROUP_MISMATCH", "VERSION_CONFLICT", "THROTTLED",
                "UNKNOWN_VEHICLE", "MALFORMED_JSON", "INGEST_ERROR"}) {
            long n = TraccarIngestService.quarantineCount(reason);
            if (n > 0) {
                byReason.put(reason, n);
            }
        }
        out.put("quarantineByReason", byReason);
        out.put("status", MqttConfig.rejectedCount() > 0 ? "WARN" : "OK");
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    @GetMapping("/quarantine")
    @Operation(summary = "Dead-letter quarantine rows, newest first")
    public ResponseEntity<ApiResponse<Page<PositionQuarantine>>> quarantine(Pageable pageable) {
        requireOwner();
        return ResponseEntity.ok(ApiResponse.success(quarantine.findAll(pageable)));
    }

    @GetMapping("/partitions")
    @Operation(summary = "Partition rollover health: coverage + DEFAULT rows")
    public ResponseEntity<ApiResponse<PartitionMonitor.Report>> partitionHealth() {
        requireOwner();
        return ResponseEntity.ok(ApiResponse.success(partitions.report()));
    }

    @PostMapping("/partitions/rollover")
    @Operation(summary = "Ensure monthly partitions exist 6 months ahead")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rollover() {
        requireOwner();
        var created = rollover.ensureFuturePartitions(6);
        return ResponseEntity.ok(ApiResponse.success(Map.of("ensured", created)));
    }

    @PostMapping("/replay-purge")
    @Operation(summary = "Purge expired replay copies (30d TTL)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replayPurge() {
        requireOwner();
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("deleted", replayPurge.purge())));
    }

    @GetMapping("/osm-import")
    @Operation(summary = "Latest OSM import meta (map version behind routing)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> osmImport() {
        requireOwner();
        return ResponseEntity.ok(ApiResponse.success(osmImports.latest()
                .map(m -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("extractName", m.getExtractName());
                    out.put("importedAt", m.getImportedAt().toString());
                    out.put("nodes", m.getNodesCount());
                    out.put("ways", m.getWaysCount());
                    return out;
                })
                .orElse(Map.of("status", "never-imported"))));
    }

    @GetMapping("/audit")
    @Operation(summary = "Audit trail tail (dispatch/PoD/skip/approve/SOS/ingest-reject)")
    public ResponseEntity<ApiResponse<Page<com.zonepilot.backend.entity.AuditLog>>> audit(
            Pageable pageable) {
        requireOwner();
        return ResponseEntity.ok(ApiResponse.success(audits.findAll(pageable)));
    }
}
