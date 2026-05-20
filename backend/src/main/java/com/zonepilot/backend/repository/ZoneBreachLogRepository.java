package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.ZoneBreachLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ZoneBreachLogRepository extends JpaRepository<ZoneBreachLog, Long> {

    List<ZoneBreachLog> findByPositionLogId(Long positionLogId);

    @Query(value = "SELECT * FROM zone_breach_log WHERE vehicle_id = :vehicleId AND breach_time >= :after ORDER BY breach_time DESC", nativeQuery = true)
    List<ZoneBreachLog> findByVehicleIdAndBreachTimeAfter(
            @Param("vehicleId") Long vehicleId,
            @Param("after") java.time.Instant after);

    @Query(value = "SELECT * FROM zone_breach_log WHERE vehicle_id = :vehicleId AND breach_time = :breachTime", nativeQuery = true)
    List<ZoneBreachLog> findByVehicleIdAndBreachTime(
            @Param("vehicleId") Long vehicleId,
            @Param("breachTime") java.time.Instant breachTime);

    List<ZoneBreachLog> findByVehicleIdOrderByBreachTimeDesc(Long vehicleId);

    @Query(value = "SELECT * FROM zone_breach_log WHERE vehicle_id = :vehicleId AND breach_time BETWEEN :from AND :to ORDER BY breach_time DESC", nativeQuery = true)
    List<ZoneBreachLog> findByVehicleIdAndBreachTimeBetween(
            @Param("vehicleId") Long vehicleId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    List<ZoneBreachLog> findByZoneIdOrderByBreachTimeDesc(Long zoneId);

    List<ZoneBreachLog> findByIsAcknowledgedFalseOrderByBreachTimeDesc();

    @Query(value = "SELECT * FROM v_vehicle_breach_summary", nativeQuery = true)
    List<Object[]> getVehicleBreachSummary();

    @Query(value = "SELECT * FROM v_zone_violation_stats", nativeQuery = true)
    List<Object[]> getZoneViolationStats();

    @Query(value = "SELECT * FROM v_zone_violation_stats WHERE zone_id = :zoneId", nativeQuery = true)
    List<Object[]> getZoneViolationStatsByZoneId(@Param("zoneId") Long zoneId);
}
