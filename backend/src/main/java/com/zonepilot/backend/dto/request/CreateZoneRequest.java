package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateZoneRequest {

    @NotBlank(message = "Zone name is required")
    private String name;

    private String description;

    @NotBlank(message = "Boundary GeoJSON is required")
    private String boundaryGeoJson;

    @NotBlank(message = "Restriction type is required")
    private String restrictionType;

    private Boolean isActive = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBoundaryGeoJson() { return boundaryGeoJson; }
    public void setBoundaryGeoJson(String boundaryGeoJson) { this.boundaryGeoJson = boundaryGeoJson; }
    public String getRestrictionType() { return restrictionType; }
    public void setRestrictionType(String restrictionType) { this.restrictionType = restrictionType; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
