package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared login throttle: the failure bucket lives in Redis (a ZSET per key,
 * so every instance counts against the same window) with an in-memory LRU
 * fallback when Redis is unreachable. Two keys per attempt — the normalized
 * email (10/5min, cleared on success) and the client IP (30/5min, decays by
 * window) — so credential stuffing across many addresses still trips the
 * IP bucket. Blocks are audit-logged and surface as 429.
 */
@Service
public class LoginThrottleService {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleService.class);

    static final int MAX_EMAIL_FAILURES = 10;
    static final int MAX_IP_FAILURES = 30;
    static final int MAX_KEYS = 10_000;
    static final Duration WINDOW = Duration.ofMinutes(5);

    private static final String REDIS_PREFIX = "zp:login:";

    private final Optional<StringRedisTemplate> redis;
    private final Map<String, Deque<Instant>> memory;

    @org.springframework.beans.factory.annotation.Autowired
    public LoginThrottleService(Optional<StringRedisTemplate> redis) {
        this(redis, newMemoryMap());
    }

    /** Package-visible for tests: two instances sharing a map model two nodes sharing Redis. */
    LoginThrottleService(Optional<StringRedisTemplate> redis, Map<String, Deque<Instant>> memory) {
        this.redis = redis;
        this.memory = memory;
    }

    static Map<String, Deque<Instant>> newMemoryMap() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> e) {
                return size() > MAX_KEYS;
            }
        });
    }

    /** Throws {@link RateLimitExceededException} (429) when either bucket is full. */
    public void requireAllowed(String email, String clientIp) {
        if (blocked(emailKey(email), MAX_EMAIL_FAILURES)) {
            log.warn("login throttled: email bucket full email={}", email);
            throw new RateLimitExceededException("Too many login attempts, try again later");
        }
        if (clientIp != null && !clientIp.isBlank() && blocked(ipKey(clientIp), MAX_IP_FAILURES)) {
            log.warn("login throttled: ip bucket full ip={}", clientIp);
            throw new RateLimitExceededException("Too many login attempts, try again later");
        }
    }

    public void recordFailure(String email, String clientIp) {
        Instant now = Instant.now();
        add(emailKey(email), now);
        if (clientIp != null && !clientIp.isBlank()) {
            add(ipKey(clientIp), now);
        }
    }

    /** Success clears the email bucket; the IP bucket decays by window (stuffing protection). */
    public void clearFailures(String email) {
        if (email == null) {
            return;
        }
        delete(emailKey(email));
    }

    /** Test hook: number of in-memory buckets (Redis path needs no bound — keys expire). */
    int bucketCount() {
        return memory.size();
    }

    private static String emailKey(String email) {
        return "email:" + (email == null ? "?" : email);
    }

    private static String ipKey(String ip) {
        return "ip:" + ip;
    }

    private boolean blocked(String key, int max) {
        Instant cutoff = Instant.now().minus(WINDOW);
        if (redis.isPresent()) {
            try {
                StringRedisTemplate t = redis.get();
                String k = REDIS_PREFIX + key;
                t.opsForZSet().removeRangeByScore(k, 0, cutoff.toEpochMilli());
                Long n = t.opsForZSet().zCard(k);
                return n != null && n >= max;
            } catch (RuntimeException e) {
                log.debug("login throttle redis unavailable, memory fallback: {}", e.getMessage());
            }
        }
        Deque<Instant> attempts = memory.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            while (!attempts.isEmpty() && attempts.peekFirst().isBefore(cutoff)) {
                attempts.pollFirst();
            }
            return attempts.size() >= max;
        }
    }

    private void add(String key, Instant now) {
        if (redis.isPresent()) {
            try {
                StringRedisTemplate t = redis.get();
                String k = REDIS_PREFIX + key;
                t.opsForZSet().add(k, UUID.randomUUID().toString(), now.toEpochMilli());
                t.expire(k, WINDOW.plusMinutes(1));
                return;
            } catch (RuntimeException e) {
                log.debug("login throttle redis unavailable, memory fallback: {}", e.getMessage());
            }
        }
        Deque<Instant> attempts = memory.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            attempts.addLast(now);
        }
    }

    private void delete(String key) {
        if (redis.isPresent()) {
            try {
                redis.get().delete(REDIS_PREFIX + key);
            } catch (RuntimeException e) {
                log.debug("login throttle redis unavailable, memory fallback: {}", e.getMessage());
            }
        }
        memory.remove(key);
    }
}
