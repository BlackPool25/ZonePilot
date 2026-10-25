package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public class DeliverStopRequest {

    @NotNull
    @DecimalMin("-90")
    @DecimalMax("90")
    private Double lat;

    @NotNull
    @DecimalMin("-180")
    @DecimalMax("180")
    private Double lng;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
}
