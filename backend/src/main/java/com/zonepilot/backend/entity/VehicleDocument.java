package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.DocType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** One live row per (vehicle, type): renewal overwrites. Slice 2 doc wall. */
@Entity
@Table(name = "vehicle_document",
        uniqueConstraints = @UniqueConstraint(columnNames = {"vehicle_id", "type"}))
public class VehicleDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocType type;

    @Column(name = "doc_no", nullable = false)
    private String docNo;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean expiredOn(LocalDate date) {
        return expiryDate.isBefore(date);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Vehicle getVehicle() { return vehicle; }
    public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    public DocType getType() { return type; }
    public void setType(DocType type) { this.type = type; }
    public String getDocNo() { return docNo; }
    public void setDocNo(String docNo) { this.docNo = docNo; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public Instant getCreatedAt() { return createdAt; }
}
