package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "group_driver",
        uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "driver_id"}))
public class GroupDriver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FleetGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected GroupDriver() {}

    public GroupDriver(FleetGroup group, Driver driver) {
        this.group = group;
        this.driver = driver;
    }

    @PrePersist
    protected void onCreate() {
        this.joinedAt = Instant.now();
    }

    public Long getId() { return id; }
    public FleetGroup getGroup() { return group; }
    public Driver getDriver() { return driver; }
    public Instant getJoinedAt() { return joinedAt; }
}
