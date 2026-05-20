package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.PositionRecordResponse;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.exception.ValidationException;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.VehicleRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PositionTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PositionTrackingService.class);

    private static final double BANGALORE_MIN_LAT = 12.8;
    private static final double BANGALORE_MAX_LAT = 13.2;
    private static final double BANGALORE_MIN_LNG = 77.4;
    private static final double BANGALORE_MAX_LNG = 77.8;

    private final VehicleRepository vehicleRepository;
    private final VehiclePositionLogRepository positionLogRepository;
    private final ZoneBreachLogRepository breachLogRepository;
    private final BreachService breachService;
    private final GeometryFactory geometryFactory;

    public PositionTrackingService(VehicleRepository vehicleRepository,
                                   VehiclePositionLogRepository positionLogRepository,
                                   ZoneBreachLogRepository breachLogRepository,
                                   BreachService breachService) {
        this.vehicleRepository = vehicleRepository;
        this.positionLogRepository = positionLogRepository;
        this.breachLogRepository = breachLogRepository;
        this.breachService = breachService;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    }

    @Transactional
    public PositionRecordResponse recordPosition(Long vehicleId, double lat, double lng,
                                                  Instant timestamp, BigDecimal speedKmh,
                                                  Short headingDeg, PositionSource source) {
        validateBangaloreCoordinates(lat, lng);

        if (timestamp != null && timestamp.isAfter(Instant.now().plusSeconds(300))) {
            throw new ValidationException("Timestamp cannot be more than 5 minutes in the future");
        }

        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle", "id", vehicleId));

        if (!vehicle.getIsActive()) {
            throw new ValidationException("Vehicle is inactive and cannot record positions.");
        }

        Instant recordedAt = timestamp != null ? timestamp : Instant.now();

        Point position = geometryFactory.createPoint(new Coordinate(lng, lat));

        VehiclePositionLog positionLog = new VehiclePositionLog();
        positionLog.setVehicleId(vehicleId);
        positionLog.setPosition(position);
        positionLog.setRecordedAt(recordedAt);
        positionLog.setSpeedKmh(speedKmh);
        positionLog.setHeadingDeg(headingDeg);
        positionLog.setSource(source);

        VehiclePositionLog saved = positionLogRepository.save(positionLog);

        // For partitioned tables, Hibernate may not always populate the generated ID.
        // Fall back to querying by vehicleId + exact recordedAt timestamp, which is
        // reliable because the trigger inserts breach_time = NEW.recorded_at.
        List<ZoneBreachLog> breaches;
        if (saved.getId() != null) {
            breaches = breachLogRepository.findByPositionLogId(saved.getId());
        } else {
            log.warn("Position log ID was null after save (partitioned table); falling back to vehicleId+timestamp query");
            breaches = breachLogRepository.findByVehicleIdAndBreachTime(vehicleId, recordedAt);
        }

        PositionRecordResponse response = new PositionRecordResponse();
        response.setBreachDetected(!breaches.isEmpty());

        PositionRecordResponse.PositionDetail posDetail = new PositionRecordResponse.PositionDetail();
        posDetail.setLatitude(lat);
        posDetail.setLongitude(lng);
        posDetail.setRecordedAt(recordedAt);
        posDetail.setSpeedKmh(speedKmh);
        posDetail.setSource(source.name());
        response.setPosition(posDetail);

        if (!breaches.isEmpty()) {
            List<PositionRecordResponse.BreachDetail> breachDetails = new ArrayList<>();
            for (ZoneBreachLog breach : breaches) {
                breachService.computeReroute(breach, vehicleId, lat, lng);
                PositionRecordResponse.BreachDetail detail = new PositionRecordResponse.BreachDetail();
                detail.setBreachId(breach.getId());
                detail.setZoneId(breach.getZone().getId());
                detail.setZoneName(breach.getZone().getName());
                detail.setBreachType(breach.getBreachType().name());
                if (breach.getResolvedRouteGeometry() != null) {
                    detail.setRerouteGeoJson(breach.getResolvedRouteGeometry().toText());
                }
                breachDetails.add(detail);
            }
            response.setBreaches(breachDetails);
        }

        return response;
    }

    private void validateBangaloreCoordinates(double lat, double lng) {
        if (lat < BANGALORE_MIN_LAT || lat > BANGALORE_MAX_LAT
                || lng < BANGALORE_MIN_LNG || lng > BANGALORE_MAX_LNG) {
            throw new ValidationException(
                    "Coordinates (" + lat + ", " + lng + ") are outside Bangalore bounding box");
        }
    }

    public List<com.zonepilot.backend.dto.response.PositionResponse> getPositionHistory(
            Long vehicleId, Instant from, Instant to) {
        List<VehiclePositionLog> logs;
        if (from != null && to != null) {
            logs = positionLogRepository.findByVehicleIdAndRecordedAtBetween(vehicleId, from, to);
        } else {
            logs = positionLogRepository.findByVehicleIdOrderByRecordedAtDesc(vehicleId);
        }
        return logs.stream().map(this::toPositionResponse).collect(Collectors.toList());
    }

    public com.zonepilot.backend.dto.response.PositionResponse getLatestPosition(Long vehicleId) {
        VehiclePositionLog log = positionLogRepository.findLatestByVehicleId(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Position", "vehicleId", vehicleId));
        return toPositionResponse(log);
    }

    private com.zonepilot.backend.dto.response.PositionResponse toPositionResponse(VehiclePositionLog log) {
        com.zonepilot.backend.dto.response.PositionResponse r = new com.zonepilot.backend.dto.response.PositionResponse();
        r.setId(log.getId());
        r.setVehicleId(log.getVehicleId());
        if (log.getPosition() != null) {
            r.setLatitude(log.getPosition().getY());
            r.setLongitude(log.getPosition().getX());
        }
        r.setRecordedAt(log.getRecordedAt());
        r.setSpeedKmh(log.getSpeedKmh());
        r.setHeadingDeg(log.getHeadingDeg());
        r.setSource(log.getSource().name());
        return r;
    }
}
