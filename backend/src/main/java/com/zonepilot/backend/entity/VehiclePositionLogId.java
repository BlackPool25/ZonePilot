package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;

public class VehiclePositionLogId implements Serializable {

    private Long id;
    private Instant recordedAt;

    public VehiclePositionLogId() {}

    public VehiclePositionLogId(Long id, Instant recordedAt) {
        this.id = id;
        this.recordedAt = recordedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VehiclePositionLogId)) return false;
        VehiclePositionLogId that = (VehiclePositionLogId) o;
        return java.util.Objects.equals(id, that.id) &&
               java.util.Objects.equals(recordedAt, that.recordedAt);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, recordedAt);
    }
}
