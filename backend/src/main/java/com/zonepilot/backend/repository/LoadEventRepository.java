package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.LoadEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoadEventRepository extends JpaRepository<LoadEvent, Long> {
    Optional<LoadEvent> findTopByVehicleIdOrderByRecordedAtDescIdDesc(Long vehicleId);
    List<LoadEvent> findByVehicleIdOrderByRecordedAtDesc(Long vehicleId);
    List<LoadEvent> findByTourIdOrderByRecordedAtAsc(Long tourId);
}
