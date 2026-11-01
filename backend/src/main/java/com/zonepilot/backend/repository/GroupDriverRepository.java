package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.GroupDriver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupDriverRepository extends JpaRepository<GroupDriver, Long> {

    List<GroupDriver> findByGroupId(Long groupId);

    List<GroupDriver> findByDriverId(Long driverId);

    boolean existsByGroupIdAndDriverId(Long groupId, Long driverId);

    void deleteByGroupIdAndDriverId(Long groupId, Long driverId);
}
