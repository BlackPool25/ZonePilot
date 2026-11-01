package com.zonepilot.backend.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public class DeliverStopRequest {

    @NotNull
    @DecimalMin("-90")
    @DecimalMax("90")
    private Double lat;

    @NotNull
    @DecimalMin("-180")
    @DecimalMax("180")
    private Double lng;

    /** Recipient PIN for PIN-policy stops (compared against the server hash). */
    private String pin;
    /** Photo evidence reference for PHOTO-policy stops. */
    private String photoRef;
    /** Signature evidence reference for SIG-policy stops. */
    private String sigRef;
    /** DELIVERED (default), PARTIAL, or FAILED. */
    private String status;
    private Integer qtyDelivered;
    /** FAILED reason chip: DAMAGED, CUSTOMER_REFUSED, WRONG_ADDRESS, ADDRESS_CLOSED, OTHER:<text>. */
    private String reason;
    /** Client idempotency key: redelivered offline-queue PoDs dedupe to 409. */
    private String opId;

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }
    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }
    public String getPhotoRef() { return photoRef; }
    public void setPhotoRef(String photoRef) { this.photoRef = photoRef; }
    public String getSigRef() { return sigRef; }
    public void setSigRef(String sigRef) { this.sigRef = sigRef; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getQtyDelivered() { return qtyDelivered; }
    public void setQtyDelivered(Integer qtyDelivered) { this.qtyDelivered = qtyDelivered; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOpId() { return opId; }
    public void setOpId(String opId) { this.opId = opId; }
}
