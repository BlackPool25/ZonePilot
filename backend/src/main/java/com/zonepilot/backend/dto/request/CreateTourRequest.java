package com.zonepilot.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public class CreateTourRequest {

    @NotNull
    private Long groupId;

    @NotNull
    private Long vehicleId;

    @NotNull
    private Long driverId;

    @NotNull
    @DecimalMin("-90")
    @DecimalMax("90")
    private Double originLat;

    @NotNull
    @DecimalMin("-180")
    @DecimalMax("180")
    private Double originLng;

    @NotEmpty
    @Valid
    private List<StopInput> stops;

    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public Double getOriginLat() { return originLat; }
    public void setOriginLat(Double originLat) { this.originLat = originLat; }
    public Double getOriginLng() { return originLng; }
    public void setOriginLng(Double originLng) { this.originLng = originLng; }
    public List<StopInput> getStops() { return stops; }
    public void setStops(List<StopInput> stops) { this.stops = stops; }

    public static class StopInput {

        @NotNull
        @DecimalMin("-90")
        @DecimalMax("90")
        private Double lat;

        @NotNull
        @DecimalMin("-180")
        @DecimalMax("180")
        private Double lng;

        private String address;
        private Integer quantity;
        private Double weightKg;
        private String recipientName;
        private String recipientPhone;
        private String windowStart;
        private String windowEnd;
        private BigDecimal codAmount;
        private String handlingNote;
        /** PoD gate: NONE (default), PIN, PHOTO, SIG. */
        private String podPolicy;
        /** Plaintext recipient PIN — hashed server-side, never stored or returned. */
        private String pin;

        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }
        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public Double getWeightKg() { return weightKg; }
        public void setWeightKg(Double weightKg) { this.weightKg = weightKg; }
        public String getRecipientName() { return recipientName; }
        public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
        public String getRecipientPhone() { return recipientPhone; }
        public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }
        public String getWindowStart() { return windowStart; }
        public void setWindowStart(String windowStart) { this.windowStart = windowStart; }
        public String getWindowEnd() { return windowEnd; }
        public void setWindowEnd(String windowEnd) { this.windowEnd = windowEnd; }
        public BigDecimal getCodAmount() { return codAmount; }
        public void setCodAmount(BigDecimal codAmount) { this.codAmount = codAmount; }
        public String getHandlingNote() { return handlingNote; }
        public void setHandlingNote(String handlingNote) { this.handlingNote = handlingNote; }
        public String getPodPolicy() { return podPolicy; }
        public void setPodPolicy(String podPolicy) { this.podPolicy = podPolicy; }
        public String getPin() { return pin; }
        public void setPin(String pin) { this.pin = pin; }
    }
}
