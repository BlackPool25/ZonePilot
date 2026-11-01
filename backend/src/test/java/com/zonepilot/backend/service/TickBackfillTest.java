package com.zonepilot.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

/**
 * WS backfill: every tick is kept in a bounded per-group ring (Redis in prod,
 * in-memory fallback) so a reconnecting subscriber replays what it missed.
 * recentTicks(group, since, limit) powers both the SUBSCRIBE push and the
 * explicit missed-since REST endpoint.
 */
@ExtendWith(MockitoExtension.class)
class TickBackfillTest {

    @Mock SimpMessagingTemplate messaging;

    private TickBroadcaster broadcaster() {
        return new TickBroadcaster(messaging, Optional.empty());
    }

    private static Instant tickAt(TickBroadcaster b, long group, long vehicle, Instant at) {
        b.tick(group, vehicle, 12.93, 77.62, BigDecimal.ONE, at, false);
        return at;
    }

    @Test
    void tick_stillBroadcastsLive() {
        TickBroadcaster b = broadcaster();
        Instant at = Instant.now();
        tickAt(b, 7L, 1L, at);
        verify(messaging).convertAndSend(eq("/topic/group/7"), any(Map.class));
    }

    @Test
    void recentTicks_returnsGroupTicksOldestFirst() {
        TickBroadcaster b = broadcaster();
        Instant t1 = tickAt(b, 7L, 1L, Instant.now().minusSeconds(30));
        Instant t2 = tickAt(b, 7L, 2L, Instant.now());

        List<Map<String, Object>> ticks = b.recentTicks(7L, null, 20);

        assertEquals(2, ticks.size());
        assertEquals(t1.toString(), ticks.get(0).get("recordedAt"));
        assertEquals(t2.toString(), ticks.get(1).get("recordedAt"));
    }

    @Test
    void recentTicks_sinceFiltersAlreadySeen() {
        TickBroadcaster b = broadcaster();
        Instant t1 = tickAt(b, 7L, 1L, Instant.now().minusSeconds(30));
        tickAt(b, 7L, 2L, Instant.now());

        List<Map<String, Object>> ticks = b.recentTicks(7L, t1, 20);

        assertEquals(1, ticks.size());
        assertEquals(2L, ((Number) ticks.get(0).get("vehicleId")).longValue());
    }

    @Test
    void recentTicks_isolatedPerGroup() {
        TickBroadcaster b = broadcaster();
        tickAt(b, 7L, 1L, Instant.now());

        assertTrue(b.recentTicks(8L, null, 20).isEmpty());
    }

    @Test
    void recentTicks_respectsLimitAndRingCap() {
        TickBroadcaster b = broadcaster();
        Instant base = Instant.now().minusSeconds(300);
        for (int i = 0; i < TickBroadcaster.BACKFILL_MAX + 10; i++) {
            tickAt(b, 7L, i, base.plusSeconds(i));
        }
        List<Map<String, Object>> all = b.recentTicks(7L, null, 1000);
        assertEquals(TickBroadcaster.BACKFILL_MAX, all.size());
        // Oldest evicted, newest kept.
        assertEquals((double) TickBroadcaster.BACKFILL_MAX + 9,
                ((Number) all.get(all.size() - 1).get("vehicleId")).doubleValue());

        assertEquals(5, b.recentTicks(7L, null, 5).size());
    }
}
