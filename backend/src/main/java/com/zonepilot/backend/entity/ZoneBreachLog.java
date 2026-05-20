package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.BreachType;
import jakarta.persistence.*;
import org.locationtech.jts.geom.LineString;
import java.time.Instant;

@Entity
@Table(name = "zone_breach_log")
public class ZoneBreachLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private ZoneRestriction zone;

    @Column(name = "position_log_id", nullable = false)
    private Long positionLogId;

    @Column(name = "breach_time", nullable = false)
    private Instant breachTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "breach_type", nullable = false, length = 30)
    private BreachType breachType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private ZoneRestrictionRule rule;

    @Column(name = "resolved_route_geometry", columnDefinition = "geometry(LineString,4326)")
    private LineString resolvedRouteGeometry;

    @Column(name = "is_acknowledged", nullable = false)
    private Boolean isAcknowledged = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.breachTime == null) {
            this.breachTime = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public ZoneRestriction getZone() { return zone; }
    public void setZone(ZoneRestriction zone) { this.zone = zone; }
    public Long getPositionLogId() { return positionLogId; }
    public void setPositionLogId(Long positionLogId) { this.positionLogId = positionLogId; }
    public Instant getBreachTime() { return breachTime; }
    public void setBreachTime(Instant breachTime) { this.breachTime = breachTime; }
    public BreachType getBreachType() { return breachType; }
    public void setBreachType(BreachType breachType) { this.breachType = breachType; }
    public ZoneRestrictionRule getRule() { return rule; }
    public void setRule(ZoneRestrictionRule rule) { this.rule = rule; }
    public LineString getResolvedRouteGeometry() { return resolvedRouteGeometry; }
    public void setResolvedRouteGeometry(LineString resolvedRouteGeometry) { this.resolvedRouteGeometry = resolvedRouteGeometry; }
    public Boolean getIsAcknowledged() { return isAcknowledged; }
    public void setIsAcknowledged(Boolean isAcknowledged) { this.isAcknowledged = isAcknowledged; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
