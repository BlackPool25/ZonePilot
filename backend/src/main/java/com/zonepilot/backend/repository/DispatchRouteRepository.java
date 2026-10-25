package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.enums.AssignmentStatus;
import com.zonepilot.backend.enums.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface DispatchRouteRepository extends JpaRepository<DispatchRoute, Long> {

    List<DispatchRoute> findByVehicleIdOrderByCreatedAtDesc(Long vehicleId);

    List<DispatchRoute> findByVehicleIdAndStatusOrderByCreatedAtDesc(Long vehicleId, RouteStatus status);

    boolean existsByVehicleIdAndAssignmentStatusIn(Long vehicleId, Collection<AssignmentStatus> statuses);

    List<DispatchRoute> findByDriverIdAndAssignmentStatusInOrderByAssignedAtDesc(
            Long driverId, Collection<AssignmentStatus> statuses);

    boolean existsByDriverIdAndAssignmentStatusIn(Long driverId, Collection<AssignmentStatus> statuses);
}
