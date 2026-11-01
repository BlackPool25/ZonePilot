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
        com.zonepilot.backend.enums.GeometryType geometryType = parseGeometryType(request.getGeometryType());
        org.locationtech.jts.geom.Geometry geom;
        try {
            geom = geoJsonReader.read(request.getBoundaryGeoJson());
            if (geometryType == com.zonepilot.backend.enums.GeometryType.CORRIDOR) {
                if (!(geom instanceof Polygon) && !(geom instanceof org.locationtech.jts.geom.LineString)) {
                    throw new ValidationException("Corridor boundary must be a Polygon or LineString geometry");
                }
            } else if (!(geom instanceof Polygon)) {
                throw new ValidationException("Boundary must be a Polygon geometry");
            }
            if (!geom.isValid()) {
                throw new ValidationException("Invalid boundary geometry");
            }
        } catch (ParseException e) {
            throw new ValidationException("Invalid GeoJSON: " + e.getMessage());
        }
        if (geometryType == com.zonepilot.backend.enums.GeometryType.CORRIDOR) {
            if (request.getCorridorWidthM() == null || request.getCorridorWidthM() <= 0) {
                throw new ValidationException("Corridor zones require corridorWidthM > 0");
            }
            if (request.getCorridorRouteId() == null) {
                throw new ValidationException("Corridor zones require corridorRouteId");
            }
        }
        if (geometryType == com.zonepilot.backend.enums.GeometryType.CIRCLE
                && (request.getCorridorWidthM() == null || request.getCorridorWidthM() <= 0)) {
            throw new ValidationException("Circle zones require corridorWidthM (radius) > 0");
        }

        Polygon boundary;
        if (geom instanceof Polygon) {
            boundary = (Polygon) geom;
        } else {
            // Corridor centerline: buffer the line into a thin polygon for legacy ST_Covers readers.
            boundary = (Polygon) geom.buffer(0.00001);
        }
        boundary.setSRID(4326);

        ZoneRestriction zone = new ZoneRestriction();
        zone.setName(request.getName());
        zone.setDescription(request.getDescription());
        zone.setBoundary(boundary);
        zone.setRestrictionType(com.zonepilot.backend.enums.RestrictionType.valueOf(request.getRestrictionType()));
        zone.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);
        zone.setGeometryType(geometryType);
        zone.setCorridorWidthM(request.getCorridorWidthM());
        zone.setCorridorRouteId(request.getCorridorRouteId());

        ZoneRestriction saved = zoneRestrictionRepository.save(zone);

        if (request.getRules() == null || request.getRules().isEmpty()) {
            ZoneRestrictionRule defaultRule = new ZoneRestrictionRule();
            defaultRule.setZone(saved);
            defaultRule.setApplicableVehicleClass(null); // null means ALL classes
            defaultRule.setRestrictionStartTime(null);  // null means active all day
            defaultRule.setRestrictionEndTime(null);    // null means active all day
            defaultRule.setDaysOfWeekBitmask((short) 127);      // 127 bitmask means all 7 days active
            defaultRule.setIsActive(true);
            zoneRestrictionRuleRepository.save(defaultRule);
        } else {
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
                    rule.setDaysOfWeekBitmask(ruleReq.getDaysOfWeekBitmask().shortValue());
                } else {
                    rule.setDaysOfWeekBitmask((short) 127);
                }
                rule.setIsActive(ruleReq.getIsActive() != null ? ruleReq.getIsActive() : true);
                if (ruleReq.getDwellThresholdMin() != null) {
                    if (ruleReq.getDwellThresholdMin() < 0) {
                        throw new ValidationException("dwellThresholdMin must be >= 0");
                    }
                    rule.setDwellThresholdMin(ruleReq.getDwellThresholdMin());
                }
                rule.setDirection(parseDirection(ruleReq.getDirection()));
                zoneRestrictionRuleRepository.save(rule);
            }
        }



        return toResponse(saved);
    }

    private com.zonepilot.backend.enums.GeometryType parseGeometryType(String raw) {
        if (raw == null || raw.isBlank()) {
            return com.zonepilot.backend.enums.GeometryType.POLYGON;
        }
        try {
            return com.zonepilot.backend.enums.GeometryType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown geometry type '" + raw + "'");
        }
    }

    private com.zonepilot.backend.enums.RuleDirection parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return com.zonepilot.backend.enums.RuleDirection.BOTH;
        }
        try {
            return com.zonepilot.backend.enums.RuleDirection.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown rule direction '" + raw + "'");
        }
    }

    @Transactional
    public void deleteZone(Long id) {
        ZoneRestriction zone = zoneRestrictionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ZoneRestriction", "id", id));
        zoneRestrictionRepository.deleteBreachLogsByZoneId(id);
        zoneRestrictionRepository.deleteRulesByZoneId(id);
        zoneRestrictionRepository.delete(zone);
    }

    private ZoneResponse toResponse(ZoneRestriction z) {

        ZoneResponse r = new ZoneResponse();
        r.setId(z.getId());
        r.setName(z.getName());
        r.setDescription(z.getDescription());
        r.setRestrictionType(z.getRestrictionType());
        r.setIsActive(z.getIsActive());
        r.setGeometryType(z.getGeometryType());
        r.setCorridorWidthM(z.getCorridorWidthM());
        r.setCorridorRouteId(z.getCorridorRouteId());
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
        r.setDwellThresholdMin(rule.getDwellThresholdMin());
        r.setDirection(rule.getDirection());
        return r;
    }
}
