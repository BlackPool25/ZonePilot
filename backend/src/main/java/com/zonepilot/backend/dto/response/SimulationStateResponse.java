package com.zonepilot.backend.dto.response;

public class SimulationStateResponse {

    private Long pathId;
    private Long vehicleId;
    private String registrationNumber;
    private String scenarioName;
    private Integer currentStep;
    private Integer totalSteps;
    private Boolean isActive;
    private Double latitude;
    private Double longitude;
    private String routeGeoJson;
    private Boolean compliant;

    public Long getPathId() { return pathId; }
    public void setPathId(Long pathId) { this.pathId = pathId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }
    public Integer getCurrentStep() { return currentStep; }
    public void setCurrentStep(Integer currentStep) { this.currentStep = currentStep; }
    public Integer getTotalSteps() { return totalSteps; }
    public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getRouteGeoJson() { return routeGeoJson; }
    public void setRouteGeoJson(String routeGeoJson) { this.routeGeoJson = routeGeoJson; }
    public Boolean getCompliant() { return compliant; }
    public void setCompliant(Boolean compliant) { this.compliant = compliant; }
}
