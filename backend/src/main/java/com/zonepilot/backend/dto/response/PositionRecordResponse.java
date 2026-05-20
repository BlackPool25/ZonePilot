package com.zonepilot.backend.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class PositionRecordResponse {

    private Boolean breachDetected;
    private PositionDetail position;
    private List<BreachDetail> breaches;

    public Boolean getBreachDetected() { return breachDetected; }
    public void setBreachDetected(Boolean breachDetected) { this.breachDetected = breachDetected; }
    public PositionDetail getPosition() { return position; }
    public void setPosition(PositionDetail position) { this.position = position; }
    public List<BreachDetail> getBreaches() { return breaches; }
    public void setBreaches(List<BreachDetail> breaches) { this.breaches = breaches; }

    public static class PositionDetail {
        private Double latitude;
        private Double longitude;
        private Instant recordedAt;
        private BigDecimal speedKmh;
        private String source;

        public Double getLatitude() { return latitude; }
        public void setLatitude(Double latitude) { this.latitude = latitude; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double longitude) { this.longitude = longitude; }
        public Instant getRecordedAt() { return recordedAt; }
        public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
        public BigDecimal getSpeedKmh() { return speedKmh; }
        public void setSpeedKmh(BigDecimal speedKmh) { this.speedKmh = speedKmh; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
    }

    public static class BreachDetail {
        private Long breachId;
        private Long zoneId;
        private String zoneName;
        private String breachType;
        private String rerouteGeoJson;

        public Long getBreachId() { return breachId; }
        public void setBreachId(Long breachId) { this.breachId = breachId; }
        public Long getZoneId() { return zoneId; }
        public void setZoneId(Long zoneId) { this.zoneId = zoneId; }
        public String getZoneName() { return zoneName; }
        public void setZoneName(String zoneName) { this.zoneName = zoneName; }
        public String getBreachType() { return breachType; }
        public void setBreachType(String breachType) { this.breachType = breachType; }
        public String getRerouteGeoJson() { return rerouteGeoJson; }
        public void setRerouteGeoJson(String rerouteGeoJson) { this.rerouteGeoJson = rerouteGeoJson; }
    }
}
