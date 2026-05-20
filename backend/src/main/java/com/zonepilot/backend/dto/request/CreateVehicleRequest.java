package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateVehicleRequest {

    @NotBlank(message = "Registration number is required")
    private String registrationNumber;

    @NotBlank(message = "Vehicle class is required")
    private String vehicleClass;

    @NotBlank(message = "Owner name is required")
    private String ownerName;

    @NotNull(message = "Depot ID is required")
    private Long depotId;

    private Boolean isActive = true;

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public String getVehicleClass() { return vehicleClass; }
    public void setVehicleClass(String vehicleClass) { this.vehicleClass = vehicleClass; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public Long getDepotId() { return depotId; }
    public void setDepotId(Long depotId) { this.depotId = depotId; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
