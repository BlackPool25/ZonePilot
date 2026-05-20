package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.DepotResponse;
import com.zonepilot.backend.entity.Depot;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.DepotRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class DepotService {

    private final DepotRepository depotRepository;
    private final GeometryFactory geometryFactory;

    public DepotService(DepotRepository depotRepository) {
        this.depotRepository = depotRepository;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    public List<DepotResponse> getAllDepots() {
        return depotRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public DepotResponse getDepotById(Long id) {
        Depot depot = depotRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Depot", "id", id));
        return toResponse(depot);
    }

    @Transactional
    public DepotResponse createDepot(String name, String address, double latitude, double longitude) {
        Depot depot = new Depot();
        depot.setName(name);
        depot.setAddress(address);
        depot.setLocation(geometryFactory.createPoint(new org.locationtech.jts.geom.Coordinate(longitude, latitude)));
        Depot saved = depotRepository.save(depot);
        return toResponse(saved);
    }

    private DepotResponse toResponse(Depot d) {
        DepotResponse r = new DepotResponse();
        r.setId(d.getId());
        r.setName(d.getName());
        r.setAddress(d.getAddress());
        if (d.getLocation() != null) {
            r.setLatitude(d.getLocation().getY());
            r.setLongitude(d.getLocation().getX());
        }
        return r;
    }
}
