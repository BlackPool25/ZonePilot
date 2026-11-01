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

    // Live reads exclude replays + the HISTORIC backfill tour (V38/V46):
    // replay rows and dummy-tour rows never appear in track/history.
    @Query(value = "SELECT * FROM vehicle_position_log WHERE vehicle_id = :vehicleId AND recorded_at BETWEEN :from AND :to AND is_replay = FALSE AND group_id NOT IN (SELECT id FROM fleet_group WHERE name = 'HISTORIC') ORDER BY recorded_at DESC", nativeQuery = true)
    List<VehiclePositionLog> findByVehicleIdAndRecordedAtBetween(
            @Param("vehicleId") Long vehicleId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query(value = "SELECT * FROM vehicle_position_log WHERE vehicle_id = :vehicleId AND is_replay = FALSE AND group_id NOT IN (SELECT id FROM fleet_group WHERE name = 'HISTORIC') ORDER BY recorded_at DESC LIMIT 1", nativeQuery = true)
    Optional<VehiclePositionLog> findLatestByVehicleId(@Param("vehicleId") Long vehicleId);

    @Query("SELECT p FROM VehiclePositionLog p WHERE p.vehicleId = :vehicleId AND p.replay = FALSE AND p.groupId NOT IN (SELECT g.id FROM FleetGroup g WHERE g.name = 'HISTORIC') ORDER BY p.recordedAt DESC")
    List<VehiclePositionLog> findByVehicleIdOrderByRecordedAtDesc(@Param("vehicleId") Long vehicleId);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM vehicle_position_log WHERE vehicle_id = :vehicleId AND recorded_at = :recordedAt)", nativeQuery = true)
    boolean existsByVehicleIdAndRecordedAt(@Param("vehicleId") Long vehicleId, @Param("recordedAt") Instant recordedAt);

    // Tour-scoped dedupe key (V38): same-ms burst redelivery collides here, not 500.
    boolean existsByVehicleIdAndTourIdAndRecordedAt(Long vehicleId, Long tourId, Instant recordedAt);

    Optional<VehiclePositionLog> findByVehicleIdAndTourIdAndRecordedAt(Long vehicleId, Long tourId, Instant recordedAt);

    List<VehiclePositionLog> findByTourIdOrderByRecordedAtAsc(Long tourId);

    // Tour/group-scoped history (live reads only, like the vehicle queries above).
    @Query("SELECT p FROM VehiclePositionLog p WHERE p.vehicleId = :vehicleId AND p.tourId = :tourId "
            + "AND p.replay = FALSE AND p.groupId NOT IN (SELECT g.id FROM FleetGroup g WHERE g.name = 'HISTORIC') "
            + "ORDER BY p.recordedAt DESC")
    List<VehiclePositionLog> findByVehicleIdAndTourIdOrderByRecordedAtDesc(
            @Param("vehicleId") Long vehicleId, @Param("tourId") Long tourId);

    @Query("SELECT p FROM VehiclePositionLog p WHERE p.vehicleId = :vehicleId AND p.tourId = :tourId "
            + "AND p.recordedAt BETWEEN :from AND :to "
            + "AND p.replay = FALSE AND p.groupId NOT IN (SELECT g.id FROM FleetGroup g WHERE g.name = 'HISTORIC') "
            + "ORDER BY p.recordedAt DESC")
    List<VehiclePositionLog> findByVehicleIdAndTourIdAndRecordedAtBetween(
            @Param("vehicleId") Long vehicleId, @Param("tourId") Long tourId,
            @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT p FROM VehiclePositionLog p WHERE p.vehicleId = :vehicleId AND p.groupId = :groupId "
            + "AND p.replay = FALSE AND p.groupId NOT IN (SELECT g.id FROM FleetGroup g WHERE g.name = 'HISTORIC') "
            + "ORDER BY p.recordedAt DESC")
    List<VehiclePositionLog> findByVehicleIdAndGroupIdOrderByRecordedAtDesc(
            @Param("vehicleId") Long vehicleId, @Param("groupId") Long groupId);

    @Query("SELECT p FROM VehiclePositionLog p WHERE p.vehicleId = :vehicleId AND p.groupId = :groupId "
            + "AND p.recordedAt BETWEEN :from AND :to "
            + "AND p.replay = FALSE AND p.groupId NOT IN (SELECT g.id FROM FleetGroup g WHERE g.name = 'HISTORIC') "
            + "ORDER BY p.recordedAt DESC")
    List<VehiclePositionLog> findByVehicleIdAndGroupIdAndRecordedAtBetween(
            @Param("vehicleId") Long vehicleId, @Param("groupId") Long groupId,
            @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Replay window: latest-2000 tail at SQL level (never full-load history).
     * Reads live rows only (is_replay=FALSE): replaying a tour must re-insert
     * the original fixes, never a previous replay's copy.
     */
    @Query(value = "SELECT * FROM vehicle_position_log WHERE tour_id = :tourId AND is_replay = FALSE ORDER BY recorded_at DESC LIMIT 2000", nativeQuery = true)
    List<VehiclePositionLog> findTop2000ByTourIdOrderByRecordedAtDesc(@Param("tourId") Long tourId);

    /** Stored-fix lookup for HTTP duplicate redelivery (200, not 400). */
    Optional<VehiclePositionLog> findFirstByVehicleIdAndRecordedAt(Long vehicleId, Instant recordedAt);
}
