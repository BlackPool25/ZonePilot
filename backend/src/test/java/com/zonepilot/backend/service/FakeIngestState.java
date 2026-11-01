package com.zonepilot.backend.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link IngestState} for unit tests. Redis behavior is covered by the ingest IT. */
public class FakeIngestState implements IngestState {

    private final Map<Long, LastFix> fixes = new HashMap<>();
    private final Map<Long, Integer> counts = new HashMap<>();
    private final Map<Long, Long> routes = new HashMap<>();

    @Override
    public Optional<LastFix> lastFix(long vehicleId) {
        return Optional.ofNullable(fixes.get(vehicleId));
    }

    @Override
    public void saveFix(long vehicleId, double lat, double lng, Instant recordedAt) {
        fixes.put(vehicleId, new LastFix(lat, lng, recordedAt));
    }

    @Override
    public int offRouteCount(long vehicleId) {
        return counts.getOrDefault(vehicleId, 0);
    }

    @Override
    public int incrOffRoute(long vehicleId) {
        int next = offRouteCount(vehicleId) + 1;
        counts.put(vehicleId, next);
        return next;
    }

    @Override
    public void clearOffRoute(long vehicleId) {
        counts.remove(vehicleId);
    }

    @Override
    public Optional<Long> lastRouteId(long vehicleId) {
        return Optional.ofNullable(routes.get(vehicleId));
    }

    @Override
    public void saveRouteId(long vehicleId, long routeId) {
        routes.put(vehicleId, routeId);
    }

    @Override
    public void clearRoute(long vehicleId) {
        routes.remove(vehicleId);
    }
}
