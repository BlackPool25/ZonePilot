package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateDepotRequest {

    @NotBlank(message = "Depot name is required")
    private String name;

    @NotBlank(message = "Address is required")
    private String address;

    @NotNull(message = "Latitude is required")
    @DecimalMin(value = "12.8", message = "Latitude must be within Bangalore")
    @DecimalMax(value = "13.2", message = "Latitude must be within Bangalore")
    private Double lat;

    @NotNull(message = "Longitude is required")
    @DecimalMin(value = "77.4", message = "Longitude must be within Bangalore")
    @DecimalMax(value = "77.8", message = "Longitude must be within Bangalore")
    private Double lng;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
}
