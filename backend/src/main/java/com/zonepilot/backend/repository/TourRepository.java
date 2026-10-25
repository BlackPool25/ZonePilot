package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.Tour;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TourRepository extends JpaRepository<Tour, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tour t where t.id = :id")
    Optional<Tour> findByIdWithLock(Long id);
}
