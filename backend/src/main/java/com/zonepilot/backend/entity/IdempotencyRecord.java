package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Client-supplied idempotency keys (PoD, duty, SOS, positions). The op_id is
 * the primary key: a concurrent duplicate insert fails unique and the caller
 * gets a 409 instead of a double-applied action. Rows older than the TTL are
 * purged best-effort on each claim.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @Column(name = "op_id", length = 100)
    private String opId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public String getOpId() { return opId; }
    public void setOpId(String opId) { this.opId = opId; }
    public Instant getCreatedAt() { return createdAt; }
}
