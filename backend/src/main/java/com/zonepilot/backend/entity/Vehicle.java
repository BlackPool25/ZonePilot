package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.VehicleAvailability;
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

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VehicleAvailability availability = VehicleAvailability.AVAILABLE;

    @Column(name = "active_dispatch_route_id")
    private Long activeDispatchRouteId;

    /**
     * Per-device MQTT credential. When set, ingest frames for this vehicle
     * must carry the matching {@code deviceKey} or they quarantine as
     * AUTH_REJECT. Unset = legacy open pipe (provision a key to fail closed).
     */
    @Column(name = "device_api_key", length = 100)
    private String deviceApiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    private VehicleModel model;

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
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Long getActiveDispatchRouteId() { return activeDispatchRouteId; }
    public void setActiveDispatchRouteId(Long activeDispatchRouteId) { this.activeDispatchRouteId = activeDispatchRouteId; }
    public String getDeviceApiKey() { return deviceApiKey; }
    public void setDeviceApiKey(String deviceApiKey) { this.deviceApiKey = deviceApiKey; }
    public VehicleAvailability getAvailability() { return availability; }
    public void setAvailability(VehicleAvailability availability) { this.availability = availability; }
    public VehicleModel getModel() { return model; }
    public void setModel(VehicleModel model) { this.model = model; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
