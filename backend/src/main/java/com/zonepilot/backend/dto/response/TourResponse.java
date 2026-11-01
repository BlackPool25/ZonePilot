package com.zonepilot.backend.dto.response;

import com.zonepilot.backend.entity.TourStop;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class TourResponse {

    private Long id;
    private String status;
    private Long groupId;
    private Long vehicleId;
    private Long driverId;
    private Long activeRouteId;
    private String dutyStatus;
    private List<StopDto> stops;

    public static class StopDto {
        private Long id;
        private Integer seq;
        private Double lat;
        private Double lng;
        private String address;
        private Integer quantity;
        private Double weightKg;
        private String recipientName;
        private String recipientPhone;
        private Instant windowStart;
        private Instant windowEnd;
        private BigDecimal codAmount;
        private String handlingNote;
        private String status;
        private Instant deliveredAt;
        private String skipReason;
        private Double podLat;
        private Double podLng;
        private Instant etaAt;
        private Long legDurationSec;

        public static StopDto from(TourStop s) {
            StopDto d = new StopDto();
            d.id = s.getId();
            d.seq = s.getSeq();
            d.lat = s.getPoint().getY();
            d.lng = s.getPoint().getX();
            d.address = s.getAddress();
            d.quantity = s.getQuantity();
            d.weightKg = s.getWeightKg();
            d.recipientName = s.getRecipientName();
            d.recipientPhone = s.getRecipientPhone();
            d.windowStart = s.getWindowStart();
            d.windowEnd = s.getWindowEnd();
            d.codAmount = s.getCodAmount();
            d.handlingNote = s.getHandlingNote();
            d.status = s.getStatus().name();
            d.deliveredAt = s.getDeliveredAt();
            d.skipReason = s.getSkipReason();
            d.podLat = s.getPodLat();
            d.podLng = s.getPodLng();
            d.etaAt = s.getEtaAt();
            d.legDurationSec = s.getLegDurationSec();
            return d;
        }

        public Long getId() { return id; }
        public Integer getSeq() { return seq; }
        public Double getLat() { return lat; }
        public Double getLng() { return lng; }
        public String getAddress() { return address; }
        public Integer getQuantity() { return quantity; }
        public Double getWeightKg() { return weightKg; }
        public String getRecipientName() { return recipientName; }
        public String getRecipientPhone() { return recipientPhone; }
        public Instant getWindowStart() { return windowStart; }
        public Instant getWindowEnd() { return windowEnd; }
        public BigDecimal getCodAmount() { return codAmount; }
        public String getHandlingNote() { return handlingNote; }
        public String getStatus() { return status; }
        public Instant getDeliveredAt() { return deliveredAt; }
        public String getSkipReason() { return skipReason; }
        public Double getPodLat() { return podLat; }
        public Double getPodLng() { return podLng; }
        public Instant getEtaAt() { return etaAt; }
        public Long getLegDurationSec() { return legDurationSec; }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getVehicleId() { return vehicleId; }
    public void setVehicleId(Long vehicleId) { this.vehicleId = vehicleId; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public Long getActiveRouteId() { return activeRouteId; }
    public void setActiveRouteId(Long activeRouteId) { this.activeRouteId = activeRouteId; }
    public String getDutyStatus() { return dutyStatus; }
    public void setDutyStatus(String dutyStatus) { this.dutyStatus = dutyStatus; }
    public List<StopDto> getStops() { return stops; }
    public void setStops(List<StopDto> stops) { this.stops = stops; }
}
