package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.enums.BreachType;
import java.time.Instant;

public class BreachResponse {

    private Long id;
    private Long vehicleId;
    private String registrationNumber;
    private Long zoneId;
    private String zoneName;
    private BreachType breachType;
    private Instant breachTime;
    private Instant endTime;
    private Long durationSec;
    private Double distanceM;
    private String rerouteGeoJson;
    private Boolean isAcknowledged;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public Long getZoneId() { return zoneId; }
    public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public BreachType getBreachType() { return breachType; }
    public void setBreachType(BreachType breachType) { this.breachType = breachType; }
    public Instant getBreachTime() { return breachTime; }
    public void setBreachTime(Instant breachTime) { this.breachTime = breachTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public Long getDurationSec() { return durationSec; }
    public void setDurationSec(Long durationSec) { this.durationSec = durationSec; }
    public Double getDistanceM() { return distanceM; }
    public void setDistanceM(Double distanceM) { this.distanceM = distanceM; }
    public String getRerouteGeoJson() { return rerouteGeoJson; }
    public void setRerouteGeoJson(String rerouteGeoJson) { this.rerouteGeoJson = rerouteGeoJson; }
    public Boolean getIsAcknowledged() { return isAcknowledged; }
    public void setIsAcknowledged(Boolean isAcknowledged) { this.isAcknowledged = isAcknowledged; }
}
