package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

public class RecordPositionRequest {

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "12.8", message = "Latitude must be within Bangalore")
    @DecimalMax(value = "13.2", message = "Latitude must be within Bangalore")
    private Double lat;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "77.4", message = "Longitude must be within Bangalore")
    @DecimalMax(value = "77.8", message = "Longitude must be within Bangalore")
    private Double lng;

    private Instant timestamp;

    @DecimalMin(value = "0", message = "Speed must be non-negative")
    private BigDecimal speedKmh;

    private Short headingDeg;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public BigDecimal getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(BigDecimal speedKmh) { this.speedKmh = speedKmh; }
    public Short getHeadingDeg() { return headingDeg; }
    public void setHeadingDeg(Short headingDeg) { this.headingDeg = headingDeg; }
}
