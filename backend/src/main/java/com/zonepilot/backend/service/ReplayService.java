package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a tour's stored fixes as REPLAY-source inserts. Breach detection
 * is eventual: each re-insert publishes a worker event (same as live
 * ingest), so the replay UI must read breaches separately via /api/breaches.
 *
 * <p>Bounded replay: at most {@link #MAX_POINTS} fixes, chunked in groups of
 * 100 with independent REQUIRES_NEW transactions per chunk (a failure loses
 * at most one chunk, never the whole replay, and never holds one giant TX).
 * Overlong histories keep the <em>latest</em> 2000 fixes (the tail — the
 * oldest fixes are dropped). Per-fix ticks are suppressed; one summary tick
 * for the final fix fans out at the end.
 */
@Service
public class ReplayService {

    public static final int MIN_SPEED = 1;
    public static final int MAX_SPEED = 30;
    public static final int MAX_POINTS = 2000;
    static final int CHUNK = 100;

    /** Ops counter: replay attempts (live traffic shares the ingest path). */
    private static final java.util.concurrent.atomic.AtomicLong replayRuns =
            new java.util.concurrent.atomic.AtomicLong();

    /** Ops counter: replay attempts (live traffic shares the ingest path). */
    public static long replayRunCount() {
        return replayRuns.get();
    }

    public record ReplayResult(long tourId, int speed, int points) {}

    private final TourRepository tours;
    private final VehiclePositionLogRepository positions;
    private final PositionTrackingService tracking;
    private final GroupScope scope;
    private final TickBroadcaster broadcaster;
    private final TransactionTemplate chunkTx;

    public ReplayService(TourRepository tours, VehiclePositionLogRepository positions,
                         PositionTrackingService tracking, GroupScope scope,
                         TickBroadcaster broadcaster, PlatformTransactionManager txm) {
        this.tours = tours;
        this.positions = positions;
        this.tracking = tracking;
        this.scope = scope;
        this.broadcaster = broadcaster;
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.chunkTx = new TransactionTemplate(txm, def);
    }

    public ReplayResult start(Long tourId, int speed) {
        return start(tourId, speed, null);
    }

    /**
     * Retry-safe entry: pass the first attempt's end stamp so a retried replay
     * re-inserts identical timestamps — the tour-scoped dedupe key then makes
     * the retry a no-op instead of a second shifted copy. Live traffic is not
     * starved: replays run on the same bounded per-fix path (one summary tick),
     * and replayRuns counts attempts for ops dashboards.
     */
    public ReplayResult start(Long tourId, int speed, Instant endAt) {
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new ValidationException("speed must be 1-30");
        }
        Tour tour = tours.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        // Manager-only: drivers own no groups, so this rejects them (403).
        scope.requireGroupAccess(tour.getGroup());
        replayRuns.incrementAndGet();

        // Paged tail: latest-2000 at SQL level, never a full-history load
        // (OOM guard on long tours). SubList caps in code too — the derived
        // Top2000 query is the SQL guard, this is the memory guard.
        List<VehiclePositionLog> desc = positions.findTop2000ByTourIdOrderByRecordedAtDesc(tourId);
        if (desc.isEmpty()) {
            throw new ValidationException("no position history for tour " + tourId);
        }
        List<VehiclePositionLog> window =
                desc.size() > MAX_POINTS ? desc.subList(0, MAX_POINTS) : desc;
        List<VehiclePositionLog> batch = new ArrayList<>(window);
        java.util.Collections.reverse(batch);

        // Fresh stamps ending at endAt: ordered, under the +5min future guard,
        // and clear of the original recorded_at dedupe keys. Spacing = speed.
        // Retries pass the first attempt's end stamp so both runs re-insert
        // identical timestamps — the tour-scoped dedupe key then makes the
        // retry a no-op instead of a second shifted copy.
        long stepMs = 1000L / speed;
        Instant end = endAt != null ? endAt : Instant.now();
        if (end.isAfter(Instant.now().plusSeconds(295))) {
            end = Instant.now();
        }
        Instant base = end.minusMillis(stepMs * (batch.size() - 1));
        for (int from = 0; from < batch.size(); from += CHUNK) {
            List<VehiclePositionLog> chunk = batch.subList(from, Math.min(from + CHUNK, batch.size()));
            int offset = from;
            chunkTx.executeWithoutResult(status -> {
                for (int i = 0; i < chunk.size(); i++) {
                    VehiclePositionLog fix = chunk.get(i);
                    tracking.recordReplayFix(
                            tour.getVehicle().getId(), tour.getDriver().getId(), tour.getId(),
                            tour.getGroup().getId(),
                            fix.getPosition().getY(), fix.getPosition().getX(),
                            base.plusMillis(stepMs * (offset + i)),
                            fix.getSpeedKmh(), fix.getHeadingDeg(), PositionSource.REPLAY);
                }
            });
        }

        // One summary tick for the final fix instead of 2000 per-fix ticks.
        VehiclePositionLog last = batch.get(batch.size() - 1);
        broadcaster.tick(tour.getGroup().getId(), tour.getVehicle().getId(),
                last.getPosition().getY(), last.getPosition().getX(),
                last.getSpeedKmh(), base.plusMillis(stepMs * (batch.size() - 1)), false);
        return new ReplayResult(tourId, speed, batch.size());
    }
}
