package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.PositionQuarantine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionQuarantineRepository extends JpaRepository<PositionQuarantine, Long> {
    List<PositionQuarantine> findByVehicleIdOrderByCreatedAtDesc(Long vehicleId);
    long countByVehicleId(Long vehicleId);
}
