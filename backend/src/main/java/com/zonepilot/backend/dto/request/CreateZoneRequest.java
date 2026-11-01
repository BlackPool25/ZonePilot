package com.zonepilot.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class CreateZoneRequest {

    @NotBlank(message = "Zone name is required")
    private String name;

    private String description;

    @NotBlank(message = "Boundary GeoJSON is required")
    private String boundaryGeoJson;

    @NotBlank(message = "Restriction type is required")
    private String restrictionType;

    private Boolean isActive = true;

    private String geometryType;
    private Double corridorWidthM;
    private Long corridorRouteId;

    @Valid
    private List<CreateZoneRuleRequest> rules;

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
    public String getGeometryType() { return geometryType; }
    public void setGeometryType(String geometryType) { this.geometryType = geometryType; }
    public Double getCorridorWidthM() { return corridorWidthM; }
    public void setCorridorWidthM(Double corridorWidthM) { this.corridorWidthM = corridorWidthM; }
    public Long getCorridorRouteId() { return corridorRouteId; }
    public void setCorridorRouteId(Long corridorRouteId) { this.corridorRouteId = corridorRouteId; }
    public List<CreateZoneRuleRequest> getRules() { return rules; }
    public void setRules(List<CreateZoneRuleRequest> rules) { this.rules = rules; }

    public static class CreateZoneRuleRequest {
        private String applicableVehicleClass;
        private String restrictionStartTime;
        private String restrictionEndTime;
        private Short daysOfWeekBitmask = 127;
        private Boolean isActive = true;
        private Integer dwellThresholdMin;
        private String direction;

        public String getApplicableVehicleClass() { return applicableVehicleClass; }
        public void setApplicableVehicleClass(String applicableVehicleClass) { this.applicableVehicleClass = applicableVehicleClass; }
        public String getRestrictionStartTime() { return restrictionStartTime; }
        public void setRestrictionStartTime(String restrictionStartTime) { this.restrictionStartTime = restrictionStartTime; }
        public String getRestrictionEndTime() { return restrictionEndTime; }
        public void setRestrictionEndTime(String restrictionEndTime) { this.restrictionEndTime = restrictionEndTime; }
        public Short getDaysOfWeekBitmask() { return daysOfWeekBitmask; }
        public void setDaysOfWeekBitmask(Short daysOfWeekBitmask) { this.daysOfWeekBitmask = daysOfWeekBitmask; }
        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }
        public Integer getDwellThresholdMin() { return dwellThresholdMin; }
        public void setDwellThresholdMin(Integer dwellThresholdMin) { this.dwellThresholdMin = dwellThresholdMin; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
    }
}
