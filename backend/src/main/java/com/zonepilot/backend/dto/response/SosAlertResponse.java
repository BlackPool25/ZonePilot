package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.SosAlert;
import java.time.Instant;

/** Dispatcher-facing SOS view: coords inline + unknown/stale flags for the UI warning. */
public class SosAlertResponse {

    private Long id;
    private String driverName;
    private Long routeId;
    private Double lat;
    private Double lng;
    private Double accuracyM;
    private boolean stale;
    private boolean unknown;
    private String status;
    private Instant createdAt;

    public static SosAlertResponse from(SosAlert alert) {
        SosAlertResponse r = new SosAlertResponse();
        r.setId(alert.getId());
        r.setDriverName(alert.getDriver() != null ? alert.getDriver().getName() : null);
        r.setRouteId(alert.getRoute() != null ? alert.getRoute().getId() : null);
        if (alert.getLocation() != null) {
            r.setLat(alert.getLocation().getY());
            r.setLng(alert.getLocation().getX());
        }
        r.setAccuracyM(alert.getAccuracyM());
        r.setStale(alert.getStale());
        r.setUnknown(alert.getLocation() == null);
        r.setStatus(alert.getStatus() != null ? alert.getStatus().name() : null);
        r.setCreatedAt(alert.getCreatedAt());
        return r;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public Double getAccuracyM() { return accuracyM; }
    public void setAccuracyM(Double accuracyM) { this.accuracyM = accuracyM; }
    public boolean isStale() { return stale; }
    public void setStale(boolean stale) { this.stale = stale; }
    public boolean isUnknown() { return unknown; }
    public void setUnknown(boolean unknown) { this.unknown = unknown; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
