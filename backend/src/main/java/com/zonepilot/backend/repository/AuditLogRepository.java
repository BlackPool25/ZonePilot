package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTourIdOrderByCreatedAtDesc(Long tourId);

    List<AuditLog> findAllByOrderByCreatedAtDesc();
}
