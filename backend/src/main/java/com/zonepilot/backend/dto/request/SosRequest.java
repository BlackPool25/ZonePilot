package com.zonepilot.backend.dto.request;

public class SosRequest {

    private Double lat;
    private Double lng;
    private Long routeId;
    /** GPS accuracy in meters, when the fix comes from the device. */
    private Double accuracyM;
    /** True when the fix is the last-known (offline queue replay), not live. */
    private Boolean stale;
    /** Client idempotency key: redelivered offline-queue SOS dedupes to 409. */
    private String opId;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public Double getAccuracyM() { return accuracyM; }
    public void setAccuracyM(Double accuracyM) { this.accuracyM = accuracyM; }
    public Boolean getStale() { return stale; }
    public void setStale(Boolean stale) { this.stale = stale; }
    public String getOpId() { return opId; }
    public void setOpId(String opId) { this.opId = opId; }
}
