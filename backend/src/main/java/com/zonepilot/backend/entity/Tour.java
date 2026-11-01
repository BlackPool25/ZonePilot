package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.DutyStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "tour")
public class Tour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private FleetGroup group;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TourStatus status = TourStatus.PLANNED;

    @Enumerated(EnumType.STRING)
    @Column(name = "duty_status", nullable = false, length = 20)
    private DutyStatus dutyStatus = DutyStatus.OFF_DUTY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Creator's app-user id for approve separation-of-duties: the dispatcher
     * who created the tour cannot approve it.
     */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public FleetGroup getGroup() { return group; }
    public void setGroup(FleetGroup group) { this.group = group; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public TourStatus getStatus() { return status; }
    public void setStatus(TourStatus status) { this.status = status; }
    public DutyStatus getDutyStatus() { return dutyStatus; }
    public void setDutyStatus(DutyStatus dutyStatus) { this.dutyStatus = dutyStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
}
