package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.GeometryType;
import com.zonepilot.backend.enums.RestrictionType;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Polygon;
import java.time.Instant;

@Entity
@Table(name = "zone_restriction")
public class ZoneRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon boundary;

    @Enumerated(EnumType.STRING)
    @Column(name = "restriction_type", nullable = false, length = 30)
    private RestrictionType restrictionType;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "geometry_type", nullable = false, length = 20)
    private GeometryType geometryType = GeometryType.POLYGON;

    /** Corridor half-width tolerance in meters; doubles as CIRCLE radius. */
    @Column(name = "corridor_width_m")
    private Double corridorWidthM;

    /** Corridor centerline: dispatch_route whose planned_route_geometry is the reference. */
    @Column(name = "corridor_route_id")
    private Long corridorRouteId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Polygon getBoundary() { return boundary; }
    public void setBoundary(Polygon boundary) { this.boundary = boundary; }
    public RestrictionType getRestrictionType() { return restrictionType; }
    public void setRestrictionType(RestrictionType restrictionType) { this.restrictionType = restrictionType; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public GeometryType getGeometryType() { return geometryType; }
    public void setGeometryType(GeometryType geometryType) { this.geometryType = geometryType; }
    public Double getCorridorWidthM() { return corridorWidthM; }
    public void setCorridorWidthM(Double corridorWidthM) { this.corridorWidthM = corridorWidthM; }
    public Long getCorridorRouteId() { return corridorRouteId; }
    public void setCorridorRouteId(Long corridorRouteId) { this.corridorRouteId = corridorRouteId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
