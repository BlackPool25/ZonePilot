package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.DutyLog;
import java.time.Instant;

public class DutyLogResponse {

    private Long id;
    private String status;
    private Instant recordedAt;
    private String note;

    public static DutyLogResponse from(DutyLog log) {
        DutyLogResponse r = new DutyLogResponse();
        r.id = log.getId();
        r.status = log.getDutyStatus().name();
        r.recordedAt = log.getRecordedAt();
        r.note = log.getNote();
        return r;
    }

    public Long getId() { return id; }
    public String getStatus() { return status; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getNote() { return note; }
}
