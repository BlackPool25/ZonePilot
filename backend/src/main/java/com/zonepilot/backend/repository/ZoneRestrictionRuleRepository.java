package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.ZoneRestrictionRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ZoneRestrictionRuleRepository extends JpaRepository<ZoneRestrictionRule, Long> {

    List<ZoneRestrictionRule> findByZoneIdAndIsActive(Long zoneId, Boolean isActive);

    @Query(value = """
        SELECT zrr.* FROM zone_restriction_rule zrr
        JOIN zone_restriction zr ON zr.id = zrr.zone_id
        WHERE zr.id = :zoneId
          AND zr.is_active = true
          AND zrr.is_active = true
          AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = :vehicleClass)
          AND (zrr.days_of_week_bitmask & (1 << (EXTRACT(DOW FROM NOW())::INT % 7))) > 0
          AND (
              zrr.restriction_start_time IS NULL
              OR CURRENT_TIME BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time
          )
        """, nativeQuery = true)
    List<ZoneRestrictionRule> findCurrentlyActiveRulesForZone(
            @Param("zoneId") Long zoneId,
            @Param("vehicleClass") String vehicleClass);
}
