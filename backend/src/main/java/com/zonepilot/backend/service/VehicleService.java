package com.zonepilot.backend.service;

import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneRestrictionRule;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.DepotRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final DepotRepository depotRepository;
    private final ZoneRestrictionRepository zoneRestrictionRepository;
    private final ZoneRestrictionRuleRepository zoneRestrictionRuleRepository;
    private final GeometryFactory geometryFactory;

    public VehicleService(VehicleRepository vehicleRepository,
                          DepotRepository depotRepository,
                          ZoneRestrictionRepository zoneRestrictionRepository,
                          ZoneRestrictionRuleRepository zoneRestrictionRuleRepository) {
        this.vehicleRepository = vehicleRepository;
        this.depotRepository = depotRepository;
        this.zoneRestrictionRepository = zoneRestrictionRepository;
        this.zoneRestrictionRuleRepository = zoneRestrictionRuleRepository;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    public List<com.zonepilot.backend.dto.response.VehicleResponse> getAllVehicles(
            VehicleClass vehicleClass, Boolean isActive) {
        List<Vehicle> vehicles;
        if (vehicleClass != null && isActive != null) {
            vehicles = vehicleRepository.findByVehicleClassAndIsActive(vehicleClass, isActive);
        } else if (isActive != null) {
            vehicles = vehicleRepository.findByIsActive(isActive);
        } else {
            vehicles = vehicleRepository.findAll();
        }
        return vehicles.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public com.zonepilot.backend.dto.response.VehicleResponse getVehicleById(Long id) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", id));
        return toResponse(vehicle);
    }

    @Transactional
    public com.zonepilot.backend.dto.response.VehicleResponse createVehicle(
            com.zonepilot.backend.dto.request.CreateVehicleRequest request) {
        if (vehicleRepository.existsByRegistrationNumber(request.getRegistrationNumber())) {
            throw new com.zonepilot.backend.exception.ValidationException(
                    "Vehicle with registration number " + request.getRegistrationNumber() + " already exists");
        }

        Depot depot = depotRepository.findById(request.getDepotId())
                .orElseThrow(() -> new ResourceNotFoundException("Depot", "id", request.getDepotId()));

        Vehicle vehicle = new Vehicle();
        vehicle.setRegistrationNumber(request.getRegistrationNumber());
        vehicle.setVehicleClass(VehicleClass.valueOf(request.getVehicleClass()));
        vehicle.setOwnerName(request.getOwnerName());
        vehicle.setDepot(depot);
        vehicle.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);

        Vehicle saved = vehicleRepository.save(vehicle);
        return toResponse(saved);
    }

    @Transactional
    public com.zonepilot.backend.dto.response.VehicleResponse updateVehicle(
            Long id, com.zonepilot.backend.dto.request.CreateVehicleRequest request) {
        Vehicle vehicle = vehicleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", id));

        // Check uniqueness only if the registration number is being changed
        if (!vehicle.getRegistrationNumber().equals(request.getRegistrationNumber())
                && vehicleRepository.existsByRegistrationNumber(request.getRegistrationNumber())) {
            throw new com.zonepilot.backend.exception.ValidationException(
                    "Vehicle with registration number " + request.getRegistrationNumber() + " already exists");
        }

        Depot depot = depotRepository.findById(request.getDepotId())
                .orElseThrow(() -> new ResourceNotFoundException("Depot", "id", request.getDepotId()));

        vehicle.setRegistrationNumber(request.getRegistrationNumber());
        vehicle.setVehicleClass(VehicleClass.valueOf(request.getVehicleClass()));
        vehicle.setOwnerName(request.getOwnerName());
        vehicle.setDepot(depot);
        if (request.getIsActive() != null) {
            vehicle.setIsActive(request.getIsActive());
        }

        return toResponse(vehicleRepository.save(vehicle));
    }

    public List<com.zonepilot.backend.dto.response.ZoneResponse> getActiveZonesAtPoint(double lat, double lng) {
        List<ZoneRestriction> zones = zoneRestrictionRepository.findActiveZonesContainingPoint(lat, lng);
        return zones.stream().map(this::toZoneResponse).collect(Collectors.toList());
    }

    private com.zonepilot.backend.dto.response.VehicleResponse toResponse(Vehicle v) {
        com.zonepilot.backend.dto.response.VehicleResponse r = new com.zonepilot.backend.dto.response.VehicleResponse();
        r.setId(v.getId());
        r.setRegistrationNumber(v.getRegistrationNumber());
        r.setVehicleClass(v.getVehicleClass());
        r.setOwnerName(v.getOwnerName());
        r.setDepotId(v.getDepot().getId());
        r.setDepotName(v.getDepot().getName());
        r.setIsActive(v.getIsActive());
        return r;
    }

    private com.zonepilot.backend.dto.response.ZoneResponse toZoneResponse(ZoneRestriction z) {
        com.zonepilot.backend.dto.response.ZoneResponse r = new com.zonepilot.backend.dto.response.ZoneResponse();
        r.setId(z.getId());
        r.setName(z.getName());
        r.setDescription(z.getDescription());
        r.setRestrictionType(z.getRestrictionType());
        r.setIsActive(z.getIsActive());
        if (z.getBoundary() != null) {
            r.setBoundaryGeoJson(z.getBoundary().toText());
        }
        return r;
    }
}
