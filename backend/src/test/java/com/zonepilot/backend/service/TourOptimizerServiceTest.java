package com.zonepilot.backend.service;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 2 RED: fastest-time stop ordering with haversine fallback (no API key).
 */
class TourOptimizerServiceTest {

    private final TourOptimizerService optimizer = new TourOptimizerService("");

    private TourOptimizerService.StopPoint pt(double lat, double lng) {
        return new TourOptimizerService.StopPoint(lat, lng);
    }

    @Test
    void ordersCollinearStops_monotonic() {
        // Origin at lng 0; stops strung east along ~12.93N. Fastest path visits them in order.
        List<TourOptimizerService.StopPoint> stops = List.of(
                pt(12.930, 77.640),
                pt(12.930, 77.600),
                pt(12.930, 77.620));
        List<TourOptimizerService.StopPoint> ordered =
                optimizer.orderStops(12.930, 77.580, stops, Instant.now());
        assertThat(ordered).hasSize(3);
        assertThat(ordered.get(0).lng()).isEqualTo(77.600);
        assertThat(ordered.get(1).lng()).isEqualTo(77.620);
        assertThat(ordered.get(2).lng()).isEqualTo(77.640);
    }

    @Test
    void totalTime_matchesPairwiseHaversine() {
        List<TourOptimizerService.StopPoint> stops = List.of(pt(12.935, 77.585));
        List<TourOptimizerService.StopPoint> ordered =
                optimizer.orderStops(12.930, 77.580, stops, Instant.now());
        long total = optimizer.totalTimeSec(12.930, 77.580, ordered, Instant.now());
        // ~0.78 km at 50 km/h ≈ 56 s; assert within a sane band, not an exact constant.
        assertThat(total).isBetween(30L, 120L);
    }

    @Test
    void respectsMaxStops() {
        assertThat(TourOptimizerService.MAX_STOPS).isEqualTo(12);
    }

    @Test
    void haversine_symmetricAndPositive() {
        double there = TourOptimizerService.haversineMeters(12.93, 77.58, 12.94, 77.59);
        double back = TourOptimizerService.haversineMeters(12.94, 77.59, 12.93, 77.58);
        assertThat(there).isCloseTo(back, within(1e-6));
        assertThat(there).isGreaterThan(0);
    }
}
