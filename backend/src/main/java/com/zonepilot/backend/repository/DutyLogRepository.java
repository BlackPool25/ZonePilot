package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.DutyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DutyLogRepository extends JpaRepository<DutyLog, Long> {

    List<DutyLog> findByTourIdOrderByRecordedAtAscIdAsc(Long tourId);
}
