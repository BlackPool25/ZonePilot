package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.LoadSource;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "load_event")
public class LoadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "tour_id")
    private Long tourId;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "delta_kg", nullable = false)
    private Double deltaKg;

    @Column(name = "running_total_kg", nullable = false)
    private Double runningTotalKg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoadSource source;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @PrePersist
    protected void onCreate() {
        if (this.recordedAt == null) this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Long getStopId() { return stopId; }
    public void setStopId(Long stopId) { this.stopId = stopId; }
    public Double getDeltaKg() { return deltaKg; }
    public void setDeltaKg(Double deltaKg) { this.deltaKg = deltaKg; }
    public Double getRunningTotalKg() { return runningTotalKg; }
    public void setRunningTotalKg(Double runningTotalKg) { this.runningTotalKg = runningTotalKg; }
    public LoadSource getSource() { return source; }
    public void setSource(LoadSource source) { this.source = source; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
