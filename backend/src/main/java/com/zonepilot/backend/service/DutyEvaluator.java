package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.DutyLog;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.enums.DutyStatus;
import com.zonepilot.backend.enums.VehicleAvailability;
import com.zonepilot.backend.repository.DutyLogRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ELD auto-duty + HOS, BreachWorker-adjacent. Consumes the position-insert
 * event AFTER_COMMIT (own txn, best-effort — never poisons ingest):
 * speed &gt; 8 km/h sustained 60s auto-sets DRIVING (FMCSA &le;5mph pattern
 * adapted); stopped 5 min drops back to ON_DUTY. Auto events bypass the
 * manual spam throttle; the driver confirms/overrides via the manual duty
 * endpoint. HOS alarms (11h driving / 14h window / 30-min break after 8h
 * driving) broadcast on {@code /topic/group/{id}} as {@code hos-alarm}.
 */
@Service
public class DutyEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DutyEvaluator.class);

    /** FMCSA &le;5mph motion pattern adapted to km/h. */
    static final double MOVING_KMH = 8.0;
    static final long DRIVE_TRIGGER_SEC = 60;
    static final long STOP_TRIGGER_SEC = 300;
    static final long MAX_DRIVING_SEC = 11 * 3600;
    static final long MAX_WINDOW_SEC = 14 * 3600;
    static final long BREAK_AFTER_DRIVING_SEC = 8 * 3600;
    static final long BREAK_MIN_SEC = 30 * 60;
    static final long OFF_DUTY_RESET_SEC = 10 * 3600;

    /** Published by PositionTrackingService after a live fix commits (replay-silent). */
    public record DutyFix(long tourId, long vehicleId, long driverId, long groupId,
                          Instant recordedAt, BigDecimal speedKmh) {}

    public record HosStatus(long drivingSec, long windowSec, boolean breakDue,
                            boolean drivingViolation, boolean windowViolation) {
        public boolean alarm() {
            return drivingViolation || windowViolation || breakDue;
        }
    }

    private final TourRepository tours;
    private final DutyLogRepository dutyLogs;
    private final VehiclePositionLogRepository positionLogs;
    private final TickBroadcaster broadcaster;

    public DutyEvaluator(TourRepository tours, DutyLogRepository dutyLogs,
                         VehiclePositionLogRepository positionLogs, TickBroadcaster broadcaster) {
        this.tours = tours;
        this.dutyLogs = dutyLogs;
        this.positionLogs = positionLogs;
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFix(DutyFix fix) {
        try {
            evaluate(fix);
        } catch (Exception e) {
            log.warn("DutyEvaluator failed for tour {}: {}", fix.tourId(), e.getMessage());
        }
    }

    private void evaluate(DutyFix fix) {
        Tour tour = tours.findById(fix.tourId()).orElse(null);
        if (tour == null) {
            return;
        }
        boolean moving = fix.speedKmh() != null && fix.speedKmh().doubleValue() > MOVING_KMH;
        DutyStatus current = tour.getDutyStatus();
        if (current != DutyStatus.DRIVING && moving
                && sustainedMotion(fix.tourId(), fix.recordedAt(), DRIVE_TRIGGER_SEC, true)) {
            // Auto-DRIVING: bypasses the manual spam throttle by design
            // (ELD motion event, not a PWA replay); driver overrides by hand.
            setDuty(tour, DutyStatus.DRIVING, fix, VehicleAvailability.IN_TRANSIT);
        } else if (current == DutyStatus.DRIVING && !moving
                && sustainedMotion(fix.tourId(), fix.recordedAt(), STOP_TRIGGER_SEC, false)) {
            setDuty(tour, DutyStatus.ON_DUTY, fix, VehicleAvailability.RESERVED);
        }
        HosStatus hos = computeHos(
                dutyLogs.findByTourIdOrderByRecordedAtAscIdAsc(fix.tourId()), fix.recordedAt());
        if (hos.alarm()) {
            // shortcut: broadcast on every alarmed fix (clients dedupe by tourId);
            // upgrade to edge-triggered once shared ingest state exists.
            broadcaster.hosAlarm(fix.groupId(), fix.tourId(), fix.vehicleId(), hosReason(hos));
        }
    }

    /**
     * Sustained-state check over stored fixes in [recordedAt-needSec, recordedAt]:
     * every fix must agree (all moving / all stopped, null speed = stopped)
     * and the covered span must reach needSec.
     */
    private boolean sustainedMotion(Long tourId, Instant recordedAt, long needSec, boolean wantMoving) {
        Instant from = recordedAt.minusSeconds(needSec + 1);
        List<VehiclePositionLog> fixes =
                positionLogs.findByTourIdAndRecordedAtBetweenOrderByRecordedAtAsc(tourId, from, recordedAt);
        if (fixes.isEmpty()) {
            return false;
        }
        for (VehiclePositionLog f : fixes) {
            boolean m = f.getSpeedKmh() != null && f.getSpeedKmh().doubleValue() > MOVING_KMH;
            if (m != wantMoving) {
                return false;
            }
        }
        Instant windowStart = recordedAt.minusSeconds(needSec);
        Instant spanStart = fixes.get(0).getRecordedAt().isAfter(windowStart)
                ? fixes.get(0).getRecordedAt() : windowStart;
        return Duration.between(spanStart, recordedAt).getSeconds() >= needSec;
    }

    private void setDuty(Tour tour, DutyStatus duty, DutyFix fix, VehicleAvailability availability) {
        tour.setDutyStatus(duty);
        tour.getVehicle().setAvailability(availability);
        DutyLog entry = new DutyLog();
        entry.setTour(tour);
        entry.setDriver(tour.getDriver());
        entry.setDutyStatus(duty);
        entry.setRecordedAt(fix.recordedAt());
        dutyLogs.save(entry);
    }

    /**
     * HOS counters from the duty log. Shift starts at the first log, or after
     * the last &ge;10h OFF_DUTY reset; the 8h driving clock resets on any
     * &ge;30-min BREAK/OFF_DUTY.
     */
    static HosStatus computeHos(List<DutyLog> logs, Instant now) {
        if (logs == null || logs.isEmpty()) {
            return new HosStatus(0, 0, false, false, false);
        }
        List<DutyLog> ordered = logs.stream()
                .sorted(Comparator.comparing(DutyLog::getRecordedAt).thenComparing(DutyLog::getId))
                .toList();
        long driving = 0;
        long sinceBreak = 0;
        Instant shiftStart = ordered.get(0).getRecordedAt();
        for (int i = 0; i < ordered.size(); i++) {
            DutyLog entry = ordered.get(i);
            Instant end = i + 1 < ordered.size() ? ordered.get(i + 1).getRecordedAt() : now;
            long sec = Math.max(0, Duration.between(entry.getRecordedAt(), end).getSeconds());
            if (entry.getDutyStatus() == DutyStatus.DRIVING) {
                driving += sec;
                sinceBreak += sec;
            } else if ((entry.getDutyStatus() == DutyStatus.BREAK
                    || entry.getDutyStatus() == DutyStatus.OFF_DUTY) && sec >= BREAK_MIN_SEC) {
                sinceBreak = 0;
            }
            if (entry.getDutyStatus() == DutyStatus.OFF_DUTY && sec >= OFF_DUTY_RESET_SEC) {
                shiftStart = end;
            }
        }
        long window = Math.max(0, Duration.between(shiftStart, now).getSeconds());
        boolean breakDue = sinceBreak >= BREAK_AFTER_DRIVING_SEC;
        return new HosStatus(driving, window, breakDue,
                driving >= MAX_DRIVING_SEC, window >= MAX_WINDOW_SEC);
    }

    private static String hosReason(HosStatus hos) {
        if (hos.drivingViolation()) {
            return "driving>11h";
        }
        if (hos.windowViolation()) {
            return "window>14h";
        }
        return "30-min break due";
    }
}
