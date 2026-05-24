package com.zonepilot.backend.service;

import com.zonepilot.backend.dto.response.BreachResponse;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.exception.ConflictException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class BreachQueryService {

    private final ZoneBreachLogRepository breachLogRepository;

    public BreachQueryService(ZoneBreachLogRepository breachLogRepository) {
        this.breachLogRepository = breachLogRepository;
    }

    public List<BreachResponse> getBreaches(Long vehicleId, Long zoneId,
                                             Instant from, Instant to,
                                             Boolean unacknowledged) {
        List<ZoneBreachLog> breaches;
        if (Boolean.TRUE.equals(unacknowledged)) {
            breaches = breachLogRepository.findByIsAcknowledgedFalseOrderByBreachTimeDesc();
        } else if (vehicleId != null && from != null && to != null) {
            breaches = breachLogRepository.findByVehicleIdAndBreachTimeBetween(vehicleId, from, to);
        } else if (vehicleId != null) {
            breaches = breachLogRepository.findByVehicleIdOrderByBreachTimeDesc(vehicleId);
        } else if (zoneId != null) {
            breaches = breachLogRepository.findByZoneIdOrderByBreachTimeDesc(zoneId);
        } else {
            breaches = breachLogRepository.findAll();
        }
        return breaches.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public BreachResponse getBreachById(Long id) {
        ZoneBreachLog breach = breachLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Breach", "id", id));
        return toResponse(breach);
    }

    @Transactional
    public BreachResponse acknowledgeBreach(Long id) {
        ZoneBreachLog breach = breachLogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Breach", "id", id));
        if (Boolean.TRUE.equals(breach.getIsAcknowledged())) {
            throw new ConflictException("Breach " + id + " has already been acknowledged");
        }
        breach.setIsAcknowledged(true);
        return toResponse(breachLogRepository.save(breach));
    }

    private BreachResponse toResponse(ZoneBreachLog b) {
        BreachResponse r = new BreachResponse();
        r.setId(b.getId());
        r.setVehicleId(b.getVehicle().getId());
        r.setRegistrationNumber(b.getVehicle().getRegistrationNumber());
        r.setZoneId(b.getZone().getId());
        r.setZoneName(b.getZone().getName());
        r.setBreachType(b.getBreachType());
        r.setBreachTime(b.getBreachTime());
        r.setEndTime(b.getEndTime());
        if (b.getBreachTime() != null && b.getEndTime() != null) {
            r.setDurationSec(java.time.Duration.between(b.getBreachTime(), b.getEndTime()).toSeconds());
        } else {
            r.setDurationSec(0L);
        }
        r.setDistanceM(b.getDistanceM() != null ? b.getDistanceM() : 0.0);
        r.setIsAcknowledged(b.getIsAcknowledged());
        if (b.getResolvedRouteGeometry() != null) {
            r.setRerouteGeoJson(b.getResolvedRouteGeometry().toText());
        }
        return r;
    }
}
