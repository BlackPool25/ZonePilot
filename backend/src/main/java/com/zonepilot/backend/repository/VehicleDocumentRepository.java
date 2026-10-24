package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.VehicleDocument;
import com.zonepilot.backend.enums.DocType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleDocumentRepository extends JpaRepository<VehicleDocument, Long> {

    Optional<VehicleDocument> findByVehicleIdAndType(Long vehicleId, DocType type);

    List<VehicleDocument> findByVehicleId(Long vehicleId);

    @Query(value = "SELECT * FROM vehicle_document WHERE expiry_date BETWEEN :today AND :until ORDER BY expiry_date", nativeQuery = true)
    List<VehicleDocument> findExpiringBetween(@Param("today") LocalDate today, @Param("until") LocalDate until);
}
