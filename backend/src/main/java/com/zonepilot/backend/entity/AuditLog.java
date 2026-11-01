package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only audit trail for sensitive fleet actions: dispatch, PoD, skip,
 * approve, SOS, and ingest rejects. Written best-effort (never fails the
 * business transaction); queried from the ops endpoint.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String actor;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "tour_id")
    private Long tourId;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(length = 60)
    private String ip;

    @Column(length = 120)
    private String device;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Long getStopId() { return stopId; }
    public void setStopId(Long stopId) { this.stopId = stopId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public Instant getCreatedAt() { return createdAt; }
}
