package com.zonepilot.backend.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Slice 3 quality gate for the Traccar feed. A failing point is quarantined
 * (dead-letter row) — never persisted, never scored, never answered with a
 * fake position. Duplicate (vehicle, recorded_at) redeliveries are benign and
 * handled by the caller via the V22 unique key.
 */
@Service
public class QualityFilter {

    /** Mirrors recordPosition's +5min blind-box. */
    static final long FUTURE_SKEW_SECONDS = 300;
    static final long STALE_SECONDS = 24 * 3600;
    /** No fleet vehicle exceeds this; above = sensor glitch. */
    static final double MAX_PLAUSIBLE_SPEED_KMH = 180.0;
    /** Implied speed vs last fix above this = teleport jump. */
    static final double TELEPORT_SPEED_KMH = 300.0;
    static final double MAX_ACCURACY_M = 50.0;

    private final IngestState state;

    public QualityFilter(IngestState state) {
        this.state = state;
    }

    public Result check(long vehicleId, double lat, double lng, Instant ts,
                        BigDecimal speedKmh, Double accuracyM) {
        if (!GeoBounds.inBounds(lat, lng)) {
            return reject("OUT_OF_BOUNDS");
        }
        Instant at = ts != null ? ts : Instant.now();
        long skew = at.getEpochSecond() - Instant.now().getEpochSecond();
        if (skew > FUTURE_SKEW_SECONDS) {
            return reject("FUTURE_SKEW");
        }
        if (-skew > STALE_SECONDS) {
            return reject("STALE");
        }
        if (speedKmh != null && speedKmh.doubleValue() > MAX_PLAUSIBLE_SPEED_KMH) {
            return reject("IMPLAUSIBLE_SPEED");
        }
        if (accuracyM != null && accuracyM > MAX_ACCURACY_M) {
            return reject("BAD_ACCURACY");
        }
        if (state.lastFix(vehicleId).map(last -> teleports(last, lat, lng, at)).orElse(false)) {
            return reject("TELEPORT");
        }
        return new Result(true, null);
    }

    private static boolean teleports(IngestState.LastFix last, double lat, double lng, Instant at) {
        long dtSeconds = Duration.between(last.recordedAt(), at).getSeconds();
        double distM = haversineM(last.lat(), last.lng(), lat, lng);
        if (dtSeconds <= 0) {
            // Same-ms redelivery of the stored fix is benign (the tour-scoped
            // dedupe key acks it); only an out-of-order jump in space teleports.
            return distM > 1.0;
        }
        double impliedKmh = distM / dtSeconds * 3.6;
        return impliedKmh > TELEPORT_SPEED_KMH;
    }

    static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(a));
    }

    private static Result reject(String reason) {
        return new Result(false, reason);
    }

    public record Result(boolean accepted, String reason) {}
}
