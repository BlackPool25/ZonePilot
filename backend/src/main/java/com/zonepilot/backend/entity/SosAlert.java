package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zonepilot.backend.enums.SosStatus;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;
import java.time.Instant;

/** Driver distress signal. Rate limiting is a hardening-slice follow-up. */
@Entity
@Table(name = "sos_alert")
public class SosAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @JsonIgnore
    private Driver driver;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    @JsonIgnore
    private DispatchRoute route;

    @Column(nullable = true, columnDefinition = "geometry(Point,4326)")
    @JsonIgnore
    private Point location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SosStatus status = SosStatus.OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public DispatchRoute getRoute() { return route; }
    public void setRoute(DispatchRoute route) { this.route = route; }
    public Point getLocation() { return location; }
    public void setLocation(Point location) { this.location = location; }
    public SosStatus getStatus() { return status; }
    public void setStatus(SosStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}
