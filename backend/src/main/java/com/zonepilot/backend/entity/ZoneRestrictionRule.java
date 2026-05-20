package com.zonepilot.backend.entity;

import com.zonepilot.backend.enums.VehicleClass;
import jakarta.persistence.*;
import java.time.LocalTime;

@Entity
@Table(name = "zone_restriction_rule")
public class ZoneRestrictionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private ZoneRestriction zone;

    @Enumerated(EnumType.STRING)
    @Column(name = "applicable_vehicle_class", length = 20)
    private VehicleClass applicableVehicleClass;

    @Column(name = "restriction_start_time")
    private LocalTime restrictionStartTime;

    @Column(name = "restriction_end_time")
    private LocalTime restrictionEndTime;

    @Column(name = "days_of_week_bitmask", nullable = false)
    private Short daysOfWeekBitmask = 127;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ZoneRestriction getZone() { return zone; }
    public void setZone(ZoneRestriction zone) { this.zone = zone; }
    public VehicleClass getApplicableVehicleClass() { return applicableVehicleClass; }
    public void setApplicableVehicleClass(VehicleClass applicableVehicleClass) { this.applicableVehicleClass = applicableVehicleClass; }
    public LocalTime getRestrictionStartTime() { return restrictionStartTime; }
    public void setRestrictionStartTime(LocalTime restrictionStartTime) { this.restrictionStartTime = restrictionStartTime; }
    public LocalTime getRestrictionEndTime() { return restrictionEndTime; }
    public void setRestrictionEndTime(LocalTime restrictionEndTime) { this.restrictionEndTime = restrictionEndTime; }
    public Short getDaysOfWeekBitmask() { return daysOfWeekBitmask; }
    public void setDaysOfWeekBitmask(Short daysOfWeekBitmask) { this.daysOfWeekBitmask = daysOfWeekBitmask; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
