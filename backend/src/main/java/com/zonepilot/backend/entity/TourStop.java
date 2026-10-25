package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "tour_stop", uniqueConstraints = @UniqueConstraint(columnNames = {"tour_id", "seq"}))
public class TourStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    @JsonIgnore
    private Tour tour;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, columnDefinition = "geometry(Point,4326)")
    private Point point;

    @Column(columnDefinition = "TEXT")
    private String address;

    private Integer quantity;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(name = "recipient_name")
    private String recipientName;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhone;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "cod_amount", precision = 12, scale = 2)
    private BigDecimal codAmount;

    @Column(name = "handling_note", columnDefinition = "TEXT")
    private String handlingNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StopStatus status = StopStatus.PENDING;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "skip_reason", columnDefinition = "TEXT")
    private String skipReason;

    @Column(name = "pod_lat")
    private Double podLat;

    @Column(name = "pod_lng")
    private Double podLng;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Tour getTour() { return tour; }
    public void setTour(Tour tour) { this.tour = tour; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public Point getPoint() { return point; }
    public void setPoint(Point point) { this.point = point; }
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
    public Instant getWindowStart() { return windowStart; }
    public void setWindowStart(Instant windowStart) { this.windowStart = windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public void setWindowEnd(Instant windowEnd) { this.windowEnd = windowEnd; }
    public BigDecimal getCodAmount() { return codAmount; }
    public void setCodAmount(BigDecimal codAmount) { this.codAmount = codAmount; }
    public String getHandlingNote() { return handlingNote; }
    public void setHandlingNote(String handlingNote) { this.handlingNote = handlingNote; }
    public StopStatus getStatus() { return status; }
    public void setStatus(StopStatus status) { this.status = status; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public Double getPodLat() { return podLat; }
    public void setPodLat(Double podLat) { this.podLat = podLat; }
    public Double getPodLng() { return podLng; }
    public void setPodLng(Double podLng) { this.podLng = podLng; }
}
