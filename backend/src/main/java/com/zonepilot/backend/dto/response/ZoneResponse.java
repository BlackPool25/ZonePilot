package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.enums.RestrictionType;
import java.util.List;

public class ZoneResponse {

    private Long id;
    private String name;
    private String description;
    private String boundaryGeoJson;
    private RestrictionType restrictionType;
    private Boolean isActive;
    private List<ZoneRuleResponse> rules;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBoundaryGeoJson() { return boundaryGeoJson; }
    public void setBoundaryGeoJson(String boundaryGeoJson) { this.boundaryGeoJson = boundaryGeoJson; }
    public RestrictionType getRestrictionType() { return restrictionType; }
    public void setRestrictionType(RestrictionType restrictionType) { this.restrictionType = restrictionType; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public List<ZoneRuleResponse> getRules() { return rules; }
    public void setRules(List<ZoneRuleResponse> rules) { this.rules = rules; }

    public static class ZoneRuleResponse {
        private Long id;
        private String applicableVehicleClass;
        private String restrictionStartTime;
        private String restrictionEndTime;
        private Short daysOfWeekBitmask;
        private Boolean isActive;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
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
    }
}
