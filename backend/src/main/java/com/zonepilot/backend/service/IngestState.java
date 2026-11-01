package com.zonepilot.backend.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Per-vehicle ingest state. Redis-backed in prod (survives restarts, shared
 * across instances); the quality filter and map-matching both read it.
 */
public interface IngestState {

    /** Last accepted fix, for teleport/implied-speed checks. */
    Optional<LastFix> lastFix(long vehicleId);

    void saveFix(long vehicleId, double lat, double lng, Instant recordedAt);

    int offRouteCount(long vehicleId);

    int incrOffRoute(long vehicleId);

    void clearOffRoute(long vehicleId);

    Optional<Long> lastRouteId(long vehicleId);

    void saveRouteId(long vehicleId, long routeId);

    void clearRoute(long vehicleId);

    record LastFix(double lat, double lng, Instant recordedAt) {}
}
