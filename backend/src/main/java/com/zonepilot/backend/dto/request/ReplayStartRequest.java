package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class ReplayStartRequest {

    @NotNull
    private Long tourId;

    @NotNull
    @Min(1)
    @Max(30)
    private Integer speed;

    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Integer getSpeed() { return speed; }
    public void setSpeed(Integer speed) { this.speed = speed; }
}
