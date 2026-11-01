package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.DutyLog;
import java.time.Instant;

public class DutyLogResponse {

    private String status;
    private Instant recordedAt;

    public static DutyLogResponse from(DutyLog log) {
        DutyLogResponse r = new DutyLogResponse();
        r.status = log.getDutyStatus().name();
        r.recordedAt = log.getRecordedAt();
        return r;
    }

    public String getStatus() { return status; }
    public Instant getRecordedAt() { return recordedAt; }
}
