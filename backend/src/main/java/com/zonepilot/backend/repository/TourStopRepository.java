package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.TourStop;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TourStopRepository extends JpaRepository<TourStop, Long> {

    List<TourStop> findByTourIdOrderBySeqAsc(Long tourId);
}
