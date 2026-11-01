package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.DutyLog;
import com.zonepilot.backend.enums.DutyStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HOS counters from duty_log: 11h driving / 14h window / 30-min break
 * after 8h driving. Pure unit test, no containers.
 */
class HosStatusTest {

    private static DutyLog row(DutyStatus status, Instant at) {
        DutyLog log = new DutyLog();
        log.setDutyStatus(status);
        log.setRecordedAt(at);
        return log;
    }

    @Test
    void exceed11hDriving_alarmFlag() {
        Instant t0 = Instant.parse("2026-11-01T02:00:00Z");
        DutyEvaluator.HosStatus hos = DutyEvaluator.computeHos(
                List.of(row(DutyStatus.DRIVING, t0)), t0.plusSeconds(12 * 3600));
        assertEquals(12 * 3600, hos.drivingSec());
        assertTrue(hos.drivingViolation());
        assertTrue(hos.alarm());
        // 12h window: driving alarm only, not the 14h window.
        assertFalse(hos.windowViolation());
    }

    @Test
    void windowBeyond14h_windowViolation() {
        Instant t0 = Instant.parse("2026-11-01T02:00:00Z");
        DutyEvaluator.HosStatus hos = DutyEvaluator.computeHos(
                List.of(row(DutyStatus.ON_DUTY, t0)), t0.plusSeconds(15 * 3600));
        assertTrue(hos.windowViolation());
        assertTrue(hos.alarm());
        assertFalse(hos.drivingViolation());
    }

    @Test
    void thirtyMinuteBreak_resetsEightHourClock() {
        Instant t0 = Instant.parse("2026-11-01T02:00:00Z");
        // 7h driving, 30-min break, 1h driving: no break due, no alarm.
        DutyEvaluator.HosStatus hos = DutyEvaluator.computeHos(List.of(
                row(DutyStatus.DRIVING, t0),
                row(DutyStatus.BREAK, t0.plusSeconds(7 * 3600)),
                row(DutyStatus.DRIVING, t0.plusSeconds(7 * 3600 + 1800))),
                t0.plusSeconds(8 * 3600 + 1800));
        assertFalse(hos.breakDue());
        assertFalse(hos.alarm());
    }

    @Test
    void eightHoursDrivingWithoutBreak_breakDue() {
        Instant t0 = Instant.parse("2026-11-01T02:00:00Z");
        DutyEvaluator.HosStatus hos = DutyEvaluator.computeHos(
                List.of(row(DutyStatus.DRIVING, t0)), t0.plusSeconds(9 * 3600));
        assertTrue(hos.breakDue());
        assertTrue(hos.alarm());
    }

    @Test
    void shortTour_noAlarm() {
        Instant t0 = Instant.parse("2026-11-01T02:00:00Z");
        DutyEvaluator.HosStatus hos = DutyEvaluator.computeHos(List.of(
                row(DutyStatus.ON_DUTY, t0),
                row(DutyStatus.DRIVING, t0.plusSeconds(3600))),
                t0.plusSeconds(2 * 3600));
        assertFalse(hos.alarm());
        assertEquals(3600, hos.drivingSec());
    }
}
