package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByLicenceNo(String licenceNo);

    boolean existsByLicenceNo(String licenceNo);

    @Query("select d from Driver d join fetch d.depot where d.user.id = :userId")
    Optional<Driver> findByUserId(Long userId);

    List<Driver> findByIsActiveTrue();
}
