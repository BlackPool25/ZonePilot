package com.zonepilot.backend.dto.request;

public class RecordLoadRequest {
    private Double deltaKg;
    private Long tourId;
    private Long stopId;
    private String source; // DRIVER_APP | SIDECAR

    public Double getDeltaKg() { return deltaKg; }
    public void setDeltaKg(Double deltaKg) { this.deltaKg = deltaKg; }
    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Long getStopId() { return stopId; }
    public void setStopId(Long stopId) { this.stopId = stopId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
