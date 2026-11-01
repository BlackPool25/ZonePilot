package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.DispatchRouteAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DispatchRouteAttemptRepository extends JpaRepository<DispatchRouteAttempt, Long> {
    List<DispatchRouteAttempt> findByDispatchRouteIdOrderByAttemptNumberAsc(Long dispatchRouteId);
}
