package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.TripSlip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripSlipRepository extends JpaRepository<TripSlip, Long> {

    List<TripSlip> findByRouteIdOrderByRecordedAtDesc(Long routeId);
}
