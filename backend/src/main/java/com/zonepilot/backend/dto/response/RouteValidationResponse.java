package com.zonepilot.backend.dto.response;

import java.util.List;

public class RouteValidationResponse {

    private Boolean compliant;
    private String routeGeoJson;
    private List<ViolationDetail> violations;
    private String alternativeRouteGeoJson;
    private Boolean alternativeRouteUnavailable = false;
    private Long dispatchRouteId;
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
    public String getWaitUntil() { return waitUntil; }
    public void setWaitUntil(String waitUntil) { this.waitUntil = waitUntil; }
    public Integer getWaitDurationSec() { return waitDurationSec; }
    public void setWaitDurationSec(Integer waitDurationSec) { this.waitDurationSec = waitDurationSec; }

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
