package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.VehicleClass;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registration_number", nullable = false, unique = true, length = 20)
    private String registrationNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_class", nullable = false, length = 20)
    private VehicleClass vehicleClass;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "depot_id", nullable = false)
    private Depot depot;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "active_dispatch_route_id")
    private Long activeDispatchRouteId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public VehicleClass getVehicleClass() { return vehicleClass; }
    public void setVehicleClass(VehicleClass vehicleClass) { this.vehicleClass = vehicleClass; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public Depot getDepot() { return depot; }
    public void setDepot(Depot depot) { this.depot = depot; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Long getActiveDispatchRouteId() { return activeDispatchRouteId; }
    public void setActiveDispatchRouteId(Long activeDispatchRouteId) { this.activeDispatchRouteId = activeDispatchRouteId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
