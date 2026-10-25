package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.enums.VehicleClass;

public class VehicleResponse {

    private Long id;
    private String registrationNumber;
    private VehicleClass vehicleClass;
    private String ownerName;
    private Boolean isActive;

    private Long activeDispatchRouteId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public VehicleClass getVehicleClass() { return vehicleClass; }
    public void setVehicleClass(VehicleClass vehicleClass) { this.vehicleClass = vehicleClass; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Long getActiveDispatchRouteId() { return activeDispatchRouteId; }
    public void setActiveDispatchRouteId(Long activeDispatchRouteId) { this.activeDispatchRouteId = activeDispatchRouteId; }
    private Long modelId;
    private String modelName;
    private Double payloadKg;
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public Double getPayloadKg() { return payloadKg; }
    public void setPayloadKg(Double payloadKg) { this.payloadKg = payloadKg; }
}

