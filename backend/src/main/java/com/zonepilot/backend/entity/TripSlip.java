package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zonepilot.backend.enums.SlipKind;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Fuel/toll slip tied to a trip; seed rows for the slice-7 ledger. */
@Entity
@Table(name = "trip_slip")
public class TripSlip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    @JsonIgnore
    private DispatchRoute route;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @JsonIgnore
    private Driver driver;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SlipKind kind;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @PrePersist
    protected void onCreate() {
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public DispatchRoute getRoute() { return route; }
    public void setRoute(DispatchRoute route) { this.route = route; }
    public Driver getDriver() { return driver; }
    public void setDriver(Driver driver) { this.driver = driver; }
    public SlipKind getKind() { return kind; }
    public void setKind(SlipKind kind) { this.kind = kind; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getRecordedAt() { return recordedAt; }
}
