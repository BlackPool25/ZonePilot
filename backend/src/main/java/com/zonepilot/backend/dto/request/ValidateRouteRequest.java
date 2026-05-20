package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public class ValidateRouteRequest {

    @NotNull(message = "Vehicle ID is required")
    private Long vehicleId;

    @NotNull(message = "Origin latitude is required")
    @DecimalMin(value = "12.8", message = "Latitude must be within Bangalore")
    @DecimalMax(value = "13.2", message = "Latitude must be within Bangalore")
    private Double originLat;

    @NotNull(message = "Origin longitude is required")
    @DecimalMin(value = "77.4", message = "Longitude must be within Bangalore")
    @DecimalMax(value = "77.8", message = "Longitude must be within Bangalore")
    private Double originLng;

    @NotNull(message = "Destination latitude is required")
    @DecimalMin(value = "12.8", message = "Latitude must be within Bangalore")
    @DecimalMax(value = "13.2", message = "Latitude must be within Bangalore")
    private Double destLat;

    @NotNull(message = "Destination longitude is required")
    @DecimalMin(value = "77.4", message = "Longitude must be within Bangalore")
    @DecimalMax(value = "77.8", message = "Longitude must be within Bangalore")
    private Double destLng;

    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Double getOriginLat() { return originLat; }
    public void setOriginLat(Double originLat) { this.originLat = originLat; }
    public Double getOriginLng() { return originLng; }
    public void setOriginLng(Double originLng) { this.originLng = originLng; }
    public Double getDestLat() { return destLat; }
    public void setDestLat(Double destLat) { this.destLat = destLat; }
    public Double getDestLng() { return destLng; }
    public void setDestLng(Double destLng) { this.destLng = destLng; }
}
