package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.SimulationPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulationPathRepository extends JpaRepository<SimulationPath, Long> {

    List<SimulationPath> findByIsActive(Boolean isActive);

    @Query(value = "SELECT * FROM simulation_path WHERE is_active = true", nativeQuery = true)
    List<SimulationPath> findAllActive();

    @Modifying
    @Query(value = "UPDATE simulation_path SET is_active = false, current_step_index = 0", nativeQuery = true)
    void resetAll();

    @Modifying
    @Query(value = "UPDATE simulation_path SET current_step_index = current_step_index + 1 WHERE id = :id", nativeQuery = true)
    void incrementStep(@Param("id") Long id);

    @Query(value = "SELECT ST_AsText(ST_PointN(waypoints, :stepIndex)) FROM simulation_path WHERE id = :id", nativeQuery = true)
    String getWaypointAtStep(@Param("id") Long id, @Param("stepIndex") int stepIndex);
}
