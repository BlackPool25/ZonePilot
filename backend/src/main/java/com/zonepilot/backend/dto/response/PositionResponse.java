package com.zonepilot.backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public class PositionResponse {

    private Long id;
    private Long vehicleId;
    private Double latitude;
    private Double longitude;
    private Instant recordedAt;
    private BigDecimal speedKmh;
    private Short headingDeg;
    private String source;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
    public BigDecimal getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(BigDecimal speedKmh) { this.speedKmh = speedKmh; }
    public Short getHeadingDeg() { return headingDeg; }
    public void setHeadingDeg(Short headingDeg) { this.headingDeg = headingDeg; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
