package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.PositionSource;
import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "vehicle_position_log")
@IdClass(VehiclePositionLogId.class)
public class VehiclePositionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Id
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false, insertable = false, updatable = false)
    private Vehicle vehicle;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point position;

    @Column(name = "speed_kmh", precision = 5, scale = 2)
    private BigDecimal speedKmh;

    @Column(name = "heading_deg")
    private Short headingDeg;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PositionSource source = PositionSource.LIVE;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Point getPosition() { return position; }
    public void setPosition(Point position) { this.position = position; }
    public BigDecimal getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(BigDecimal speedKmh) { this.speedKmh = speedKmh; }
    public Short getHeadingDeg() { return headingDeg; }
    public void setHeadingDeg(Short headingDeg) { this.headingDeg = headingDeg; }
    public PositionSource getSource() { return source; }
    public void setSource(PositionSource source) { this.source = source; }
}
