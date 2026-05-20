package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.enums.VehicleClass;

public class VehicleResponse {

    private Long id;
    private String registrationNumber;
    private VehicleClass vehicleClass;
    private String ownerName;
    private Long depotId;
    private String depotName;
    private Boolean isActive;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public VehicleClass getVehicleClass() { return vehicleClass; }
    public void setVehicleClass(VehicleClass vehicleClass) { this.vehicleClass = vehicleClass; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public Long getDepotId() { return depotId; }
    public void setDepotId(Long depotId) { this.depotId = depotId; }
    public String getDepotName() { return depotName; }
    public void setDepotName(String depotName) { this.depotName = depotName; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
