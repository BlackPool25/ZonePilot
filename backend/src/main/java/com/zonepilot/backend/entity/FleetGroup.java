package com.zonepilot.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zonepilot.backend.auth.AppUser;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "fleet_group")
public class FleetGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    @JsonIgnore
    private AppUser manager;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AppUser getManager() { return manager; }
    public void setManager(AppUser manager) { this.manager = manager; }
    public Long getManagerId() { return manager == null ? null : manager.getId(); }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}
