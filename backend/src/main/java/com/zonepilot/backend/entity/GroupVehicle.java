package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "group_vehicle",
        uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "vehicle_id"}))
public class GroupVehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private FleetGroup group;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected GroupVehicle() {}

    public GroupVehicle(FleetGroup group, Vehicle vehicle) {
        this.group = group;
        this.vehicle = vehicle;
    }

    @PrePersist
    protected void onCreate() {
        this.joinedAt = Instant.now();
    }

    public Long getId() { return id; }
    public FleetGroup getGroup() { return group; }
    public Vehicle getVehicle() { return vehicle; }
    public Instant getJoinedAt() { return joinedAt; }
}
