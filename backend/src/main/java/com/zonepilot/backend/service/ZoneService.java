package com.zonepilot.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zonepilot.backend.dto.response.ZoneResponse;
import com.zonepilot.backend.entity.ZoneRestriction;
import com.zonepilot.backend.entity.ZoneRestrictionRule;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.ZoneRestrictionRepository;
import com.zonepilot.backend.repository.ZoneRestrictionRuleRepository;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ZoneService {

    private final ZoneRestrictionRepository zoneRestrictionRepository;
    private final ZoneRestrictionRuleRepository zoneRestrictionRuleRepository;
    private final GeometryFactory geometryFactory;
    private final GeoJsonReader geoJsonReader;
    private final GeoJsonWriter geoJsonWriter;
    private final ObjectMapper objectMapper;

    public ZoneService(ZoneRestrictionRepository zoneRestrictionRepository,
                       ZoneRestrictionRuleRepository zoneRestrictionRuleRepository) {
        this.zoneRestrictionRepository = zoneRestrictionRepository;
        this.zoneRestrictionRuleRepository = zoneRestrictionRuleRepository;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
        this.geoJsonReader = new GeoJsonReader(geometryFactory);
        this.geoJsonWriter = new GeoJsonWriter();
        this.objectMapper = new ObjectMapper();
    }

    public List<ZoneResponse> getAllZones() {
        return zoneRestrictionRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public ZoneResponse getZoneById(Long id) {
        ZoneRestriction zone = zoneRestrictionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ZoneRestriction", "id", id));
        return toResponse(zone);
    }

    public List<ZoneResponse> getActiveZones() {
        return zoneRestrictionRepository.findByIsActive(true).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ZoneResponse createZone(com.zonepilot.backend.dto.request.CreateZoneRequest request) {
        Polygon boundary;
        try {
            org.locationtech.jts.geom.Geometry geom = geoJsonReader.read(request.getBoundaryGeoJson());
            if (!(geom instanceof Polygon)) {
                throw new ValidationException("Boundary must be a Polygon geometry");
            }
            boundary = (Polygon) geom;
            boundary.setSRID(4326);
            if (!boundary.isValid()) {
                throw new ValidationException("Invalid polygon geometry");
            }
        } catch (ParseException e) {
            throw new ValidationException("Invalid GeoJSON: " + e.getMessage());
        }

        ZoneRestriction zone = new ZoneRestriction();
        zone.setName(request.getName());
        zone.setDescription(request.getDescription());
        zone.setBoundary(boundary);
        zone.setRestrictionType(com.zonepilot.backend.enums.RestrictionType.valueOf(request.getRestrictionType()));
        zone.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);

        ZoneRestriction saved = zoneRestrictionRepository.save(zone);

        if (request.getRules() != null) {
            for (com.zonepilot.backend.dto.request.CreateZoneRequest.CreateZoneRuleRequest ruleReq : request.getRules()) {
                ZoneRestrictionRule rule = new ZoneRestrictionRule();
                rule.setZone(saved);
                if (ruleReq.getApplicableVehicleClass() != null && !ruleReq.getApplicableVehicleClass().equalsIgnoreCase("ALL")) {
                    rule.setApplicableVehicleClass(com.zonepilot.backend.enums.VehicleClass.valueOf(ruleReq.getApplicableVehicleClass()));
                }
                if (ruleReq.getRestrictionStartTime() != null) {
                    rule.setRestrictionStartTime(java.time.LocalTime.parse(ruleReq.getRestrictionStartTime()));
                }
                if (ruleReq.getRestrictionEndTime() != null) {
                    rule.setRestrictionEndTime(java.time.LocalTime.parse(ruleReq.getRestrictionEndTime()));
                }
                if (ruleReq.getDaysOfWeekBitmask() != null) {
                    rule.setDaysOfWeekBitmask(ruleReq.getDaysOfWeekBitmask());
                }
                rule.setIsActive(ruleReq.getIsActive() != null ? ruleReq.getIsActive() : true);
                zoneRestrictionRuleRepository.save(rule);
            }
        }

        return toResponse(saved);
    }

    private ZoneResponse toResponse(ZoneRestriction z) {
        ZoneResponse r = new ZoneResponse();
        r.setId(z.getId());
        r.setName(z.getName());
        r.setDescription(z.getDescription());
        r.setRestrictionType(z.getRestrictionType());
        r.setIsActive(z.getIsActive());
        if (z.getBoundary() != null) {
            try {
                String geoJsonStr = geoJsonWriter.write(z.getBoundary());
                r.setBoundaryGeoJson(objectMapper.readValue(geoJsonStr, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                r.setBoundaryGeoJson(z.getBoundary().toText());
            }
        }
        List<ZoneRestrictionRule> rules = zoneRestrictionRuleRepository.findByZoneIdAndIsActive(z.getId(), true);
        r.setRules(rules.stream().map(this::toRuleResponse).collect(Collectors.toList()));
        return r;
    }

    private ZoneResponse.ZoneRuleResponse toRuleResponse(ZoneRestrictionRule rule) {
        ZoneResponse.ZoneRuleResponse r = new ZoneResponse.ZoneRuleResponse();
        r.setId(rule.getId());
        r.setApplicableVehicleClass(rule.getApplicableVehicleClass() != null
                ? rule.getApplicableVehicleClass().name() : "ALL");
        r.setRestrictionStartTime(rule.getRestrictionStartTime() != null
                ? rule.getRestrictionStartTime().toString() : null);
        r.setRestrictionEndTime(rule.getRestrictionEndTime() != null
                ? rule.getRestrictionEndTime().toString() : null);
        r.setDaysOfWeekBitmask(rule.getDaysOfWeekBitmask());
        r.setIsActive(rule.getIsActive());
        return r;
    }
}
