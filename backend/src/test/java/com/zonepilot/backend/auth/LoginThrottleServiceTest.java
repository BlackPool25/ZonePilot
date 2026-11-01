package com.zonepilot.backend.auth;

import com.zonepilot.backend.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Login throttle: shared bucket (Redis in prod, in-memory fallback), per-email
 * AND per-IP keys, audit log on block. Refresh revocation pins rotation.
 */
class LoginThrottleServiceTest {

    private static LoginThrottleService memoryOnly() {
        return new LoginThrottleService(Optional.empty());
    }

    @Test
    void emailBucket_blocksAfterMaxFailures() {
        LoginThrottleService throttle = memoryOnly();
        for (int i = 0; i < LoginThrottleService.MAX_EMAIL_FAILURES; i++) {
            assertDoesNotThrow(() -> throttle.requireAllowed("a@zp.test", "1.2.3.4"));
            throttle.recordFailure("a@zp.test", "1.2.3.4");
        }
        assertThrows(RateLimitExceededException.class,
                () -> throttle.requireAllowed("a@zp.test", "9.9.9.9"));
    }

    @Test
    void emailBucket_clearsOnSuccess() {
        LoginThrottleService throttle = memoryOnly();
        for (int i = 0; i < LoginThrottleService.MAX_EMAIL_FAILURES; i++) {
            throttle.recordFailure("b@zp.test", "1.2.3.4");
        }
        assertThrows(RateLimitExceededException.class,
                () -> throttle.requireAllowed("b@zp.test", "5.5.5.5"));
        throttle.clearFailures("b@zp.test");
        assertDoesNotThrow(() -> throttle.requireAllowed("b@zp.test", "5.5.5.5"));
    }

    @Test
    void ipBucket_blocksCredentialStuffingAcrossEmails() {
        LoginThrottleService throttle = memoryOnly();
        for (int i = 0; i < LoginThrottleService.MAX_IP_FAILURES; i++) {
            final int k = i;
            assertDoesNotThrow(() -> throttle.requireAllowed("victim" + k + "@zp.test", "6.6.6.6"));
            throttle.recordFailure("victim" + k + "@zp.test", "6.6.6.6");
        }
        // Same IP, fresh email: still blocked by the IP bucket.
        assertThrows(RateLimitExceededException.class,
                () -> throttle.requireAllowed("fresh@zp.test", "6.6.6.6"));
        // Different IP is unaffected.
        assertDoesNotThrow(() -> throttle.requireAllowed("fresh@zp.test", "7.7.7.7"));
    }

    @Test
    void redisFailure_fallsBackToMemory() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
                Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        when(redis.opsForZSet()).thenThrow(new RuntimeException("redis down"));
        LoginThrottleService throttle = new LoginThrottleService(Optional.of(redis));
        for (int i = 0; i < LoginThrottleService.MAX_EMAIL_FAILURES; i++) {
            throttle.recordFailure("c@zp.test", "1.1.1.1");
        }
        assertThrows(RateLimitExceededException.class,
                () -> throttle.requireAllowed("c@zp.test", "2.2.2.2"));
    }

    @Test
    void sharedBucket_twoInstancesSeeEachOthersCounts() {
        // Models multi-instance prod: both nodes share one Redis bucket, so a
        // flood spread across instances still trips the same counter. Here the
        // shared in-memory map stands in for the shared Redis ZSET.
        java.util.Map<String, java.util.Deque<java.time.Instant>> shared =
                LoginThrottleService.newMemoryMap();
        LoginThrottleService nodeA = new LoginThrottleService(Optional.empty(), shared);
        LoginThrottleService nodeB = new LoginThrottleService(Optional.empty(), shared);
        for (int i = 0; i < LoginThrottleService.MAX_EMAIL_FAILURES; i++) {
            nodeA.recordFailure("d@zp.test", "3.3.3.3");
        }
        assertThrows(RateLimitExceededException.class,
                () -> nodeB.requireAllowed("d@zp.test", "4.4.4.4"));
    }

    @Test
    void nullIp_skipsIpBucketButKeepsEmailBucket() {
        LoginThrottleService throttle = memoryOnly();
        for (int i = 0; i < LoginThrottleService.MAX_EMAIL_FAILURES; i++) {
            throttle.recordFailure("e@zp.test", null);
        }
        assertThrows(RateLimitExceededException.class,
                () -> throttle.requireAllowed("e@zp.test", null));
        // Null IP never trips the IP bucket for other users.
        assertDoesNotThrow(() -> throttle.requireAllowed("other@zp.test", null));
    }
}
