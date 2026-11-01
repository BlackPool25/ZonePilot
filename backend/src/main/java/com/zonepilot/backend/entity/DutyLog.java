package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zonepilot.backend.enums.DutyStatus;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * ELD-hybrid timestamped duty log: every duty transition on a tour
 * is appended here; Tour.dutyStatus is the rollup of the latest row.
 */
@Entity
@Table(name = "duty_log")
public class DutyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    @JsonIgnore
    private Tour tour;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @JsonIgnore
    private Driver driver;

    @Enumerated(EnumType.STRING)
    @Column(name = "duty_status", nullable = false, length = 20)
    private DutyStatus dutyStatus;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @PrePersist
    protected void onCreate() {
        if (this.recordedAt == null) {
            this.recordedAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tour getTour() { return tour; }
    public void setTour(Tour tour) { this.tour = tour; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public DutyStatus getDutyStatus() { return dutyStatus; }
    public void setDutyStatus(DutyStatus dutyStatus) { this.dutyStatus = dutyStatus; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
