package com.zonepilot.backend.dto.response;

import java.util.List;

public class SimulationTickResponse {

    private Integer tickNumber;
    private List<TickVehicleResult> vehicles;

    public Integer getTickNumber() { return tickNumber; }
    public void setTickNumber(Integer tickNumber) { this.tickNumber = tickNumber; }
    public List<TickVehicleResult> getVehicles() { return vehicles; }
    public void setVehicles(List<TickVehicleResult> vehicles) { this.vehicles = vehicles; }

    public static class TickVehicleResult {
        private Long vehicleId;
        private String registrationNumber;
        private Double latitude;
        private Double longitude;
        private Boolean breachDetected;
        private List<PositionRecordResponse.BreachDetail> breaches;
        private String status;

        public Long getVehicleId() { return vehicleId; }
        public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
        public String getRegistrationNumber() { return registrationNumber; }
        public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
        public Boolean getBreachDetected() { return breachDetected; }
        public void setBreachDetected(Boolean breachDetected) { this.breachDetected = breachDetected; }
        public List<PositionRecordResponse.BreachDetail> getBreaches() { return breaches; }
        public void setBreaches(List<PositionRecordResponse.BreachDetail> breaches) { this.breaches = breaches; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
