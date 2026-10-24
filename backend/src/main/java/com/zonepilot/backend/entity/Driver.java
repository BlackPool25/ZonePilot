package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zonepilot.backend.auth.AppUser;
import com.zonepilot.backend.auth.Role;
import com.zonepilot.backend.enums.SalaryMode;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Slice 2 driver roster. Depot-scoped via {@code depotFilter}; login optional
 * (record-only drivers cannot use driver endpoints until linked).
 */
@Entity
@Table(name = "driver",
        uniqueConstraints = @UniqueConstraint(columnNames = "licence_no"))
@Filter(name = "depotFilter", condition = "depot_id = :depotId")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "depot_id", nullable = false)
    @JsonIgnore
    private Depot depot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private AppUser user;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Column(name = "licence_no", nullable = false, unique = true)
    private String licenceNo;

    @Column(name = "licence_expiry")
    private LocalDate licenceExpiry;

    @Enumerated(EnumType.STRING)
    @Column(name = "salary_mode", nullable = false, length = 20)
    private SalaryMode salaryMode = SalaryMode.PER_TRIP;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean licenceValidOn(LocalDate date) {
        return licenceExpiry != null && !licenceExpiry.isBefore(date);
    }

    public boolean canLogin() {
        return user != null && user.getRole() == Role.DRIVER;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Depot getDepot() { return depot; }
    public void setDepot(Depot depot) { this.depot = depot; }
    public AppUser getUser() { return user; }
    public void setUser(AppUser user) { this.user = user; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLicenceNo() { return licenceNo; }
    public void setLicenceNo(String licenceNo) { this.licenceNo = licenceNo; }
    public LocalDate getLicenceExpiry() { return licenceExpiry; }
    public void setLicenceExpiry(LocalDate licenceExpiry) { this.licenceExpiry = licenceExpiry; }
    public SalaryMode getSalaryMode() { return salaryMode; }
    public void setSalaryMode(SalaryMode salaryMode) { this.salaryMode = salaryMode; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
}
