package com.zonepilot.backend.service;

import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Replay TTL purge: replay copies (is_replay=TRUE) are transient debug
 * material, not history. Rows older than the retention window are deleted in
 * one statement; invoked from the ops endpoint.
 */
@Service
public class ReplayPurgeService {

    private static final Logger log = LoggerFactory.getLogger(ReplayPurgeService.class);

    /** Replay copies live this long before the purge collects them. */
    public static final Duration REPLAY_TTL = Duration.ofDays(30);

    private final VehiclePositionLogRepository positions;
    private final Duration ttl;

    @org.springframework.beans.factory.annotation.Autowired
    public ReplayPurgeService(VehiclePositionLogRepository positions) {
        this(positions, REPLAY_TTL);
    }

    public ReplayPurgeService(VehiclePositionLogRepository positions, long ttlDays) {
        this(positions, Duration.ofDays(ttlDays));
    }

    public ReplayPurgeService(VehiclePositionLogRepository positions, Duration ttl) {
        this.positions = positions;
        this.ttl = ttl;
    }

    @Transactional
    public long purge() {
        long deleted = positions.deleteByReplayTrueAndRecordedAtBefore(
                Instant.now().minus(ttl));
        log.info("replay.purge deleted={}", deleted);
        return deleted;
    }
}
