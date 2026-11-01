package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.FleetGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FleetGroupRepository extends JpaRepository<FleetGroup, Long> {

    List<FleetGroup> findByManager_IdOrderByCreatedAtDesc(Long managerId);
}
