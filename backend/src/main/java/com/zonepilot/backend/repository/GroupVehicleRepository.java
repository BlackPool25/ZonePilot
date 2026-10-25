package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.GroupVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupVehicleRepository extends JpaRepository<GroupVehicle, Long> {

    List<GroupVehicle> findByGroupId(Long groupId);

    List<GroupVehicle> findByVehicleId(Long vehicleId);

    boolean existsByGroupIdAndVehicleId(Long groupId, Long vehicleId);

    void deleteByGroupIdAndVehicleId(Long groupId, Long vehicleId);
}
