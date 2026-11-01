package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.NotNull;

public class AssignDriverRequest {

    @NotNull
    private Long driverId;

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
}
