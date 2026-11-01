package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.SosAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SosAlertRepository extends JpaRepository<SosAlert, Long> {

    List<SosAlert> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    List<SosAlert> findAllByOrderByCreatedAtDesc();
}
