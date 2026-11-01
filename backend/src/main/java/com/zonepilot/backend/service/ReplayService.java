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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Replays a tour's stored fixes as REPLAY-source inserts. Breach detection
 * is eventual: each re-insert publishes a worker event (same as live
 * ingest), so the replay UI must read breaches separately via /api/breaches.
 */
@Service
public class ReplayService {

    public static final int MIN_SPEED = 1;
    public static final int MAX_SPEED = 30;
    public static final int MAX_POINTS = 2000;

    public record ReplayResult(long tourId, int speed, int points) {}

    private final TourRepository tours;
    private final VehiclePositionLogRepository positions;
    private final PositionTrackingService tracking;
    private final GroupScope scope;

    public ReplayService(TourRepository tours, VehiclePositionLogRepository positions,
                         PositionTrackingService tracking, GroupScope scope) {
        this.tours = tours;
        this.positions = positions;
        this.tracking = tracking;
        this.scope = scope;
    }

    @Transactional
    public ReplayResult start(Long tourId, int speed) {
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new ValidationException("speed must be 1-30");
        }
        Tour tour = tours.findById(tourId)
                .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
        // Manager-only: drivers own no groups, so this rejects them (403).
        scope.requireGroupAccess(tour.getGroup());

        List<VehiclePositionLog> history = positions.findByTourIdOrderByRecordedAtAsc(tourId);
        if (history.isEmpty()) {
            throw new ValidationException("no position history for tour " + tourId);
        }
        List<VehiclePositionLog> batch = history.size() > MAX_POINTS
                ? history.subList(0, MAX_POINTS)
                : history;

        // Fresh stamps ending at now: ordered, under the +5min future guard,
        // and clear of the original recorded_at dedupe keys. Spacing = speed.
        long stepMs = 1000L / speed;
        Instant base = Instant.now().minusMillis(stepMs * (batch.size() - 1));
        for (int i = 0; i < batch.size(); i++) {
            VehiclePositionLog fix = batch.get(i);
            tracking.recordPosition(
                    tour.getVehicle().getId(), tour.getDriver().getId(), tour.getId(),
                    tour.getGroup().getId(),
                    fix.getPosition().getY(), fix.getPosition().getX(),
                    base.plusMillis(stepMs * i),
                    fix.getSpeedKmh(), fix.getHeadingDeg(), PositionSource.REPLAY);
        }
        return new ReplayResult(tourId, speed, batch.size());
    }
}
