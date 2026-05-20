package com.zonepilot.backend.repository;

import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    List<Vehicle> findByVehicleClassAndIsActive(VehicleClass vehicleClass, Boolean isActive);

    List<Vehicle> findByIsActive(Boolean isActive);

    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);

    boolean existsByRegistrationNumber(String registrationNumber);
}
