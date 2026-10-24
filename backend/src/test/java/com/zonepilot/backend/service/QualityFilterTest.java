package com.zonepilot.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 3: Traccar points failing quality are quarantined, never scored. */
class QualityFilterTest {

    private FakeIngestState state;
    private QualityFilter filter;

    @BeforeEach
    void setUp() {
        state = new FakeIngestState();
        filter = new QualityFilter(state);
    }

    @Test
    void cleanPoint_accepts() {
        QualityFilter.Result r = filter.check(7L, 12.9352, 77.6245,
                Instant.now(), new BigDecimal("42.5"), null);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void futureTimestamp_quarantines() {
        QualityFilter.Result r = filter.check(7L, 12.9352, 77.6245,
                Instant.now().plusSeconds(3600), BigDecimal.ONE, null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("FUTURE");
    }

    @Test
    void staleTimestamp_quarantines() {
        QualityFilter.Result r = filter.check(7L, 12.9352, 77.6245,
                Instant.now().minusSeconds(100_000), BigDecimal.ONE, null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("STALE");
    }

    @Test
    void impossibleSpeed_quarantines() {
        QualityFilter.Result r = filter.check(7L, 12.9352, 77.6245,
                Instant.now(), new BigDecimal("250"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("SPEED");
    }

    @Test
    void badAccuracy_quarantines() {
        QualityFilter.Result r = filter.check(7L, 12.9352, 77.6245,
                Instant.now(), BigDecimal.ONE, 250.0);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("ACCURACY");
    }

    @Test
    void outOfBounds_quarantines() {
        QualityFilter.Result r = filter.check(7L, 28.6, 77.2,
                Instant.now(), BigDecimal.ONE, null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("BOUNDS");
    }

    @Test
    void teleportVsLastFix_quarantines() {
        Instant t0 = Instant.now().minusSeconds(60);
        state.saveFix(7L, 12.9352, 77.6245, t0);
        // ~8.5km in 60s => ~510 km/h implied: teleport, still inside Bangalore bounds
        QualityFilter.Result r = filter.check(7L, 12.9900, 77.6800,
                t0.plusSeconds(60), new BigDecimal("50"), null);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("TELEPORT");
    }

    @Test
    void normalMoveVsLastFix_accepts() {
        Instant t0 = Instant.now().minusSeconds(60);
        state.saveFix(7L, 12.9352, 77.6245, t0);
        QualityFilter.Result r = filter.check(7L, 12.9452, 77.6345,
                t0.plusSeconds(60), new BigDecimal("50"), null);
        assertThat(r.accepted()).isTrue();
    }

}
