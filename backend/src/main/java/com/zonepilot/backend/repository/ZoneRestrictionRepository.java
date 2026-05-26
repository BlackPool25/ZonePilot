package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.ZoneRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ZoneRestrictionRepository extends JpaRepository<ZoneRestriction, Long> {

    List<ZoneRestriction> findByIsActive(Boolean isActive);

    @Query(value = "SELECT * FROM zone_restriction zr WHERE zr.is_active = true AND ST_Intersects(zr.boundary, ST_SetSRID(ST_Point(:lng, :lat), 4326))", nativeQuery = true)
    List<ZoneRestriction> findActiveZonesContainingPoint(@Param("lat") double lat, @Param("lng") double lng);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "DELETE FROM zone_breach_log WHERE zone_id = :zoneId", nativeQuery = true)
    void deleteBreachLogsByZoneId(@Param("zoneId") Long zoneId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "DELETE FROM zone_restriction_rule WHERE zone_id = :zoneId", nativeQuery = true)
    void deleteRulesByZoneId(@Param("zoneId") Long zoneId);
}

