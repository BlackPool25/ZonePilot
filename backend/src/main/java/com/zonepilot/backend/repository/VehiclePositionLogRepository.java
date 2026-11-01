package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.VehiclePositionLogId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehiclePositionLogRepository extends JpaRepository<VehiclePositionLog, VehiclePositionLogId> {

    @Query(value = "SELECT * FROM vehicle_position_log WHERE vehicle_id = :vehicleId AND recorded_at BETWEEN :from AND :to ORDER BY recorded_at DESC", nativeQuery = true)
    List<VehiclePositionLog> findByVehicleIdAndRecordedAtBetween(
            @Param("vehicleId") Long vehicleId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = "SELECT * FROM vehicle_position_log WHERE vehicle_id = :vehicleId ORDER BY recorded_at DESC LIMIT 1", nativeQuery = true)
    Optional<VehiclePositionLog> findLatestByVehicleId(@Param("vehicleId") Long vehicleId);

    List<VehiclePositionLog> findByVehicleIdOrderByRecordedAtDesc(Long vehicleId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM vehicle_position_log WHERE vehicle_id = :vehicleId AND recorded_at = :recordedAt)", nativeQuery = true)
    boolean existsByVehicleIdAndRecordedAt(@Param("vehicleId") Long vehicleId, @Param("recordedAt") Instant recordedAt);

    // Tour-scoped dedupe key (V38): same-ms burst redelivery collides here, not 500.
    boolean existsByVehicleIdAndTourIdAndRecordedAt(Long vehicleId, Long tourId, Instant recordedAt);

    Optional<VehiclePositionLog> findByVehicleIdAndTourIdAndRecordedAt(Long vehicleId, Long tourId, Instant recordedAt);
}
