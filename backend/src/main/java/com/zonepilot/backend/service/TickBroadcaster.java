package com.zonepilot.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Live pipe broadcaster: derived state (never raw echo) per fleet group on
 * {@code /topic/group/{id}}. JWT-scoped subscribe enforced in WsConfig.
 *
 * <p>Backfill: every tick is also kept in a bounded per-group ring
 * ({@link #BACKFILL_MAX} frames). Redis (one list per group) is the shared
 * ring across instances; an in-memory ring is the fallback when Redis is
 * unreachable. Reconnecting clients replay missed ticks via
 * {@link #recentTicks} (SUBSCRIBE push + missed-since REST endpoint).
 */
@Service
public class TickBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TickBroadcaster.class);

    /** Ring size per group; SUBSCRIBE push sends at most this many. */
    public static final int BACKFILL_MAX = 50;
    /** Default push size on SUBSCRIBE (newest N). */
    public static final int BACKFILL_PUSH = 20;

    private static final String REDIS_PREFIX = "zp:ticks:";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SimpMessagingTemplate messaging;
    private final Optional<StringRedisTemplate> redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, Deque<Map<String, Object>>> memory = new ConcurrentHashMap<>();

    public TickBroadcaster(SimpMessagingTemplate messaging) {
        this(messaging, Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TickBroadcaster(SimpMessagingTemplate messaging, Optional<StringRedisTemplate> redis) {
        this.messaging = messaging;
        this.redis = redis;
    }

    public void tick(long groupId, long vehicleId, double lat, double lng,
                     BigDecimal speedKmh, Instant recordedAt, boolean breachDetected) {
        Map<String, Object> frame = Map.of(
                "type", "tick",
                "groupId", groupId,
                "vehicleId", vehicleId,
                "lat", lat,
                "lng", lng,
                "speedKmh", speedKmh == null ? 0 : speedKmh.doubleValue(),
                "recordedAt", recordedAt.toString(),
                "breachDetected", breachDetected);
        messaging.convertAndSend("/topic/group/" + groupId, frame);
        store(groupId, frame);
    }

    public void hosAlarm(long groupId, long tourId, long vehicleId, String reason) {
        Map<String, Object> frame = Map.of(
                "type", "hos-alarm",
                "groupId", groupId,
                "tourId", tourId,
                "vehicleId", vehicleId,
                "reason", reason);
        messaging.convertAndSend("/topic/group/" + groupId, frame);
        store(groupId, frame);
    }

    /**
     * Missed ticks for a group, oldest first. {@code since} is exclusive
     * (pass the last seen recordedAt); null returns the newest {@code limit}.
     */
    public List<Map<String, Object>> recentTicks(long groupId, Instant since, int limit) {
        int n = Math.max(1, Math.min(limit, BACKFILL_MAX));
        List<Map<String, Object>> frames = readRing(groupId);
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>(n);
        for (Map<String, Object> f : frames) {
            if (since != null && !recordedAtOf(f).isAfter(since)) {
                continue;
            }
            out.add(f);
        }
        return out.size() <= n ? out : out.subList(out.size() - n, out.size());
    }

    private void store(long groupId, Map<String, Object> frame) {
        Deque<Map<String, Object>> ring =
                memory.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (ring) {
            ring.addLast(frame);
            while (ring.size() > BACKFILL_MAX) {
                ring.pollFirst();
            }
        }
        if (redis.isPresent()) {
            try {
                StringRedisTemplate t = redis.get();
                String k = REDIS_PREFIX + groupId;
                t.opsForList().leftPush(k, mapper.writeValueAsString(frame));
                t.opsForList().trim(k, 0, BACKFILL_MAX - 1);
                t.expire(k, java.time.Duration.ofMinutes(30));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.debug("tick backfill redis unavailable, memory ring only: {}", e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> readRing(long groupId) {
        if (redis.isPresent()) {
            try {
                List<String> raw = redis.get().opsForList()
                        .range(REDIS_PREFIX + groupId, 0, BACKFILL_MAX - 1);
                if (raw != null && !raw.isEmpty()) {
                    java.util.ArrayList<Map<String, Object>> out =
                            new java.util.ArrayList<>(raw.size());
                    for (int i = raw.size() - 1; i >= 0; i--) {
                        out.add(mapper.readValue(raw.get(i), MAP_TYPE));
                    }
                    return out;
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.debug("tick backfill redis unavailable, memory ring only: {}", e.getMessage());
            }
        }
        Deque<Map<String, Object>> ring = memory.get(groupId);
        if (ring == null) {
            return List.of();
        }
        synchronized (ring) {
            return List.copyOf(ring);
        }
    }

    private static Instant recordedAtOf(Map<String, Object> frame) {
        try {
            return Instant.parse(String.valueOf(frame.get("recordedAt")));
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
