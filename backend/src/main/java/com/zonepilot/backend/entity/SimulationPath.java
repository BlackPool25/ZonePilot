package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;
import java.time.Instant;

@Entity
@Table(name = "simulation_path")
public class SimulationPath {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(name = "scenario_name", nullable = false, length = 100)
    private String scenarioName;

    @Column(nullable = false, columnDefinition = "geometry(LineString,4326)")
    private LineString waypoints;

    @Column(name = "current_step_index", nullable = false)
    private Integer currentStepIndex = 0;

    @Column(name = "total_steps", nullable = false)
    private Integer totalSteps;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }
    public LineString getWaypoints() { return waypoints; }
    public void setWaypoints(LineString waypoints) { this.waypoints = waypoints; }
    public Integer getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(Integer currentStepIndex) { this.currentStepIndex = currentStepIndex; }
    public Integer getTotalSteps() { return totalSteps; }
    public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
