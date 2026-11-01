package com.zonepilot.backend.entity;

import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;
import java.time.Instant;

@Entity
@Table(name = "dispatch_route_attempt")
public class DispatchRouteAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "dispatch_route_id", nullable = false)
    private Long dispatchRouteId;
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;
    @Column(name = "planned_route_geometry", columnDefinition = "geometry(LineString,4326)")
    private LineString plannedRouteGeometry;
    @Column(name = "penalised_zone_ids", columnDefinition = "TEXT")
    private String penalisedZoneIds;
    @Column(name = "violated_zone_ids", columnDefinition = "TEXT")
    private String violatedZoneIds;
    @Column(name = "accepted", nullable = false)
    private Boolean accepted;
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @PrePersist protected void onCreate() { this.createdAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getDispatchRouteId() { return dispatchRouteId; }
    public void setDispatchRouteId(Long v) { this.dispatchRouteId = v; }
    public Integer getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(Integer v) { this.attemptNumber = v; }
    public LineString getPlannedRouteGeometry() { return plannedRouteGeometry; }
    public void setPlannedRouteGeometry(LineString v) { this.plannedRouteGeometry = v; }
    public String getPenalisedZoneIds() { return penalisedZoneIds; }
    public void setPenalisedZoneIds(String v) { this.penalisedZoneIds = v; }
    public String getViolatedZoneIds() { return violatedZoneIds; }
    public void setViolatedZoneIds(String v) { this.violatedZoneIds = v; }
    public Boolean getAccepted() { return accepted; }
    public void setAccepted(Boolean v) { this.accepted = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { this.rejectReason = v; }
}
