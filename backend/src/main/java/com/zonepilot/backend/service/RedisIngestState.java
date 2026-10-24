package com.zonepilot.backend.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis implementation of {@link IngestState} (keys expire; state is a cache, not truth). */
@Service
public class RedisIngestState implements IngestState {

    private static final String PREFIX = "zp:ingest:";
    private static final Duration FIX_TTL = Duration.ofHours(24);
    private static final Duration COUNTER_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public RedisIngestState(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(long vehicleId, String field) {
        return PREFIX + vehicleId + ":" + field;
    }

    @Override
    public Optional<LastFix> lastFix(long vehicleId) {
        String raw = redis.opsForValue().get(key(vehicleId, "fix"));
        if (raw == null) return Optional.empty();
        String[] parts = raw.split(",", 3);
        if (parts.length != 3) return Optional.empty();
        try {
            return Optional.of(new LastFix(Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]), Instant.parse(parts[2])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveFix(long vehicleId, double lat, double lng, Instant recordedAt) {
        redis.opsForValue().set(key(vehicleId, "fix"),
                lat + "," + lng + "," + recordedAt.toString(), FIX_TTL);
    }

    @Override
    public int offRouteCount(long vehicleId) {
        String raw = redis.opsForValue().get(key(vehicleId, "offroute"));
        return raw == null ? 0 : Integer.parseInt(raw);
    }

    @Override
    public int incrOffRoute(long vehicleId) {
        Long next = redis.opsForValue().increment(key(vehicleId, "offroute"));
        redis.expire(key(vehicleId, "offroute"), COUNTER_TTL);
        return next == null ? 1 : next.intValue();
    }

    @Override
    public void clearOffRoute(long vehicleId) {
        redis.delete(key(vehicleId, "offroute"));
    }

    @Override
    public Optional<Long> lastRouteId(long vehicleId) {
        String raw = redis.opsForValue().get(key(vehicleId, "route"));
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveRouteId(long vehicleId, long routeId) {
        redis.opsForValue().set(key(vehicleId, "route"), String.valueOf(routeId), COUNTER_TTL);
    }

    @Override
    public void clearRoute(long vehicleId) {
        redis.delete(key(vehicleId, "route"));
    }
}
