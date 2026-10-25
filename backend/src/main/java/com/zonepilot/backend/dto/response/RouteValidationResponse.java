package com.zonepilot.backend.dto.response;

import java.util.List;

public class RouteValidationResponse {

    private Boolean compliant;
    private String routeGeoJson;
    private List<ViolationDetail> violations;
    private String alternativeRouteGeoJson;
    private Boolean alternativeRouteUnavailable = false;
    private Long dispatchRouteId;
    private Long travelDurationSec; // own-network/Google total trip seconds, null when unknown
    // Epic 4: wait-state fields (populated when waiting is faster than rerouting)
    private String waitUntil;       // ISO-8601 timestamp
    private Integer waitDurationSec; // seconds to wait

    public Boolean getCompliant() { return compliant; }
    public void setCompliant(Boolean compliant) { this.compliant = compliant; }
    public String getRouteGeoJson() { return routeGeoJson; }
    public void setRouteGeoJson(String routeGeoJson) { this.routeGeoJson = routeGeoJson; }
    public List<ViolationDetail> getViolations() { return violations; }
    public void setViolations(List<ViolationDetail> violations) { this.violations = violations; }
    public String getAlternativeRouteGeoJson() { return alternativeRouteGeoJson; }
    public void setAlternativeRouteGeoJson(String alternativeRouteGeoJson) { this.alternativeRouteGeoJson = alternativeRouteGeoJson; }
    public Boolean getAlternativeRouteUnavailable() { return alternativeRouteUnavailable; }
    public void setAlternativeRouteUnavailable(Boolean alternativeRouteUnavailable) { this.alternativeRouteUnavailable = alternativeRouteUnavailable; }
    public Long getDispatchRouteId() { return dispatchRouteId; }
    public void setDispatchRouteId(Long dispatchRouteId) { this.dispatchRouteId = dispatchRouteId; }
    public Long getTravelDurationSec() { return travelDurationSec; }
    public void setTravelDurationSec(Long travelDurationSec) { this.travelDurationSec = travelDurationSec; }
    public String getWaitUntil() { return waitUntil; }
    public void setWaitUntil(String waitUntil) { this.waitUntil = waitUntil; }
    public Integer getWaitDurationSec() { return waitDurationSec; }
    public void setWaitDurationSec(Integer waitDurationSec) { this.waitDurationSec = waitDurationSec; }
    private Long googleDurationSec;
    private Long googleStaticDurationSec;
    private Long googleDistanceMeters;
    private Long googleDelaySec;
    private Double googleSimilarityScore;
    private List<AttemptDetail> attempts;
    public Long getGoogleDurationSec() { return googleDurationSec; }
    public void setGoogleDurationSec(Long v) { this.googleDurationSec = v; }
    public Long getGoogleStaticDurationSec() { return googleStaticDurationSec; }
    public void setGoogleStaticDurationSec(Long v) { this.googleStaticDurationSec = v; }
    public Long getGoogleDistanceMeters() { return googleDistanceMeters; }
    public void setGoogleDistanceMeters(Long v) { this.googleDistanceMeters = v; }
    public Long getGoogleDelaySec() { return googleDelaySec; }
    public void setGoogleDelaySec(Long v) { this.googleDelaySec = v; }
    public Double getGoogleSimilarityScore() { return googleSimilarityScore; }
    public void setGoogleSimilarityScore(Double v) { this.googleSimilarityScore = v; }
    public List<AttemptDetail> getAttempts() { return attempts; }
    public void setAttempts(List<AttemptDetail> v) { this.attempts = v; }
    public static class AttemptDetail {
        private int attemptNumber;
        private java.util.Set<Long> penalisedZoneIds;
        private java.util.List<Long> violatedZoneIds;
        private boolean accepted;
        private String reason;
        public int getAttemptNumber() { return attemptNumber; }
        public void setAttemptNumber(int v) { this.attemptNumber = v; }
        public java.util.Set<Long> getPenalisedZoneIds() { return penalisedZoneIds; }
        public void setPenalisedZoneIds(java.util.Set<Long> v) { this.penalisedZoneIds = v; }
        public java.util.List<Long> getViolatedZoneIds() { return violatedZoneIds; }
        public void setViolatedZoneIds(java.util.List<Long> v) { this.violatedZoneIds = v; }
        public boolean isAccepted() { return accepted; }
        public void setAccepted(boolean v) { this.accepted = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { this.reason = v; }
    }

    public static class ViolationDetail {
        private Long zoneId;
        private String zoneName;
        private String breachType;

        public Long getZoneId() { return zoneId; }
        public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
        public String getZoneName() { return zoneName; }
        public void setZoneName(String zoneName) { this.zoneName = zoneName; }
        public String getBreachType() { return breachType; }
        public void setBreachType(String breachType) { this.breachType = breachType; }
    }
}
