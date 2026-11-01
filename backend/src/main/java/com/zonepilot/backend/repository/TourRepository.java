package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TourRepository extends JpaRepository<Tour, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tour t where t.id = :id")
    Optional<Tour> findByIdWithLock(Long id);

    List<Tour> findByDriverIdOrderByIdDesc(Long driverId);

    List<Tour> findByGroupId(Long groupId);

    /** Active-only myTours window (COMPLETED/PENDING_APPROVAL excluded), paged. */
    Page<Tour> findByDriverIdAndStatusInOrderByIdDesc(
            Long driverId, Collection<TourStatus> statuses, Pageable pageable);
}
