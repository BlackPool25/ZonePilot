package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.Depot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepotRepository extends JpaRepository<Depot, Long> {

    List<Depot> findAllByNameContainingIgnoreCase(String name);

    @Query(value = "SELECT id FROM depot ORDER BY location <-> ST_SetSRID(ST_Point(:lng, :lat), 4326) LIMIT 1", nativeQuery = true)
    Long findNearestDepotId(@Param("lat") double lat, @Param("lng") double lng);
}
