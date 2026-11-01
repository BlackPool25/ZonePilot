package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.RouteStatus;
import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import java.time.Instant;

@Entity
@Table(name = "dispatch_route")
public class DispatchRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @JsonIgnore
    @Column(name = "origin_point", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point originPoint;

    @JsonIgnore
    @Column(name = "destination_point", nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point destinationPoint;

    @JsonIgnore
    @Column(name = "planned_route_geometry", columnDefinition = "geometry(LineString,4326)")
    private LineString plannedRouteGeometry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RouteStatus status = RouteStatus.PENDING;

    @Column(name = "validation_timestamp")
    private Instant validationTimestamp;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "wait_until")
    private Instant waitUntil;

    @Column(name = "wait_duration_sec")
    private Integer waitDurationSec;

    @Column(name = "google_duration_sec")
    private Long googleDurationSec;

    @Column(name = "google_static_duration_sec")
    private Long googleStaticDurationSec;

    @Column(name = "google_distance_meters")
    private Long googleDistanceMeters;

    @Column(name = "google_delay_sec")
    private Long googleDelaySec;

    @Column(name = "google_similarity_score")
    private Double googleSimilarityScore;

    @Column(name = "travel_duration_sec")
    private Long travelDurationSec;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id")
    private Tour tour;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_status", nullable = false, length = 20)
    private AssignmentStatus assignmentStatus = AssignmentStatus.UNASSIGNED;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isCompliant() {
        return this.status == RouteStatus.COMPLIANT;
    }

    public String getRouteGeoJson() {
        return this.plannedRouteGeometry != null ? this.plannedRouteGeometry.toText() : null;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public Point getOriginPoint() { return originPoint; }
    public void setOriginPoint(Point originPoint) { this.originPoint = originPoint; }
    public Point getDestinationPoint() { return destinationPoint; }
    public void setDestinationPoint(Point destinationPoint) { this.destinationPoint = destinationPoint; }
    public LineString getPlannedRouteGeometry() { return plannedRouteGeometry; }
    public void setPlannedRouteGeometry(LineString plannedRouteGeometry) { this.plannedRouteGeometry = plannedRouteGeometry; }
    public RouteStatus getStatus() { return status; }
    public void setStatus(RouteStatus status) { this.status = status; }
    public Instant getValidationTimestamp() { return validationTimestamp; }
    public void setValidationTimestamp(Instant validationTimestamp) { this.validationTimestamp = validationTimestamp; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public void setDispatchedAt(Instant dispatchedAt) { this.dispatchedAt = dispatchedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getWaitUntil() { return waitUntil; }
    public void setWaitUntil(Instant waitUntil) { this.waitUntil = waitUntil; }
    public Integer getWaitDurationSec() { return waitDurationSec; }
    public void setWaitDurationSec(Integer waitDurationSec) { this.waitDurationSec = waitDurationSec; }
    public Long getGoogleDurationSec() { return googleDurationSec; }
    public void setGoogleDurationSec(Long v) { this.googleDurationSec = v; }
    public Long getGoogleStaticDurationSec() { return googleStaticDurationSec; }
    public void setGoogleStaticDurationSec(Long v) { this.googleStaticDurationSec = v; }
    public Long getGoogleDistanceMeters() { return googleDistanceMeters; }
    public void setGoogleDistanceMeters(Long v) { this.googleDistanceMeters = v; }
    public Long getGoogleDelaySec() { return googleDelaySec; }
    public void setGoogleDelaySec(Long v) { this.googleDelaySec = v; }
    public Long getTravelDurationSec() { return travelDurationSec; }
    public void setTravelDurationSec(Long v) { this.travelDurationSec = v; }
    public Double getGoogleSimilarityScore() { return googleSimilarityScore; }
    public void setGoogleSimilarityScore(Double v) { this.googleSimilarityScore = v; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public Tour getTour() { return tour; }
    public void setTour(Tour tour) { this.tour = tour; }
    public AssignmentStatus getAssignmentStatus() { return assignmentStatus; }
    public void setAssignmentStatus(AssignmentStatus assignmentStatus) { this.assignmentStatus = assignmentStatus; }
    public Instant getAssignedAt() { return assignedAt; }
    public void setAssignedAt(Instant assignedAt) { this.assignedAt = assignedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
