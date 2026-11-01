package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.dto.response.BreachResponse;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.VehiclePositionLog;
import com.zonepilot.backend.entity.ZoneBreachLog;
import com.zonepilot.backend.exception.ConflictException;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
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
    private final TourRepository tours;
    private final FleetGroupRepository groups;
    private final VehiclePositionLogRepository positionLogs;
    private final GroupScope scope;

    public BreachQueryService(ZoneBreachLogRepository breachLogRepository,
                              TourRepository tours,
                              FleetGroupRepository groups,
                              VehiclePositionLogRepository positionLogs,
                              GroupScope scope) {
        this.breachLogRepository = breachLogRepository;
        this.tours = tours;
        this.groups = groups;
        this.positionLogs = positionLogs;
        this.scope = scope;
    }

    /**
     * Tour/group-scoped breach list. A tour filter shows only that tour's
     * breaches (cross-group tourIds 403); a group filter shows only breaches
     * from that group's tours. With no filter, managers default to their own
     * groups — there is no findAll fallback returning the whole fleet.
     *
     * History follows tours (fixes are keyed driver+tour+group): a breach
     * belongs to the group of the tour that produced it.
     */
    public List<BreachResponse> getBreaches(Long vehicleId, Long zoneId,
                                             Instant from, Instant to,
                                             Boolean unacknowledged,
                                             Long tourId, Long groupId) {
        if (tourId != null) {
            Tour tour = tours.findById(tourId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
            scope.requireGroupAccess(tour.getGroup());
            return toResponses(breachesForTours(List.of(tourId)), vehicleId, zoneId, from, to, unacknowledged);
        }
        if (groupId != null) {
            FleetGroup group = groups.findById(groupId)
                    .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", groupId));
            scope.requireGroupAccess(group);
            return toResponses(breachesForTours(tourIdsIn(groupId)),
                    vehicleId, zoneId, from, to, unacknowledged);
        }
        if (!scope.isOwner() && scope.currentUserId().isPresent()) {
            List<Long> ownGroupIds = groups
                    .findByManager_IdOrderByCreatedAtDesc(scope.currentUserId().get())
                    .stream().map(FleetGroup::getId).toList();
            List<Long> tourIds = ownGroupIds.stream()
                    .flatMap(gid -> tourIdsIn(gid).stream()).distinct().toList();
            return toResponses(breachesForTours(tourIds),
                    vehicleId, zoneId, from, to, unacknowledged);
        }
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
        return toResponses(breaches, vehicleId, zoneId, from, to, unacknowledged);
    }

    private List<Long> tourIdsIn(Long groupId) {
        return tours.findByGroupId(groupId).stream().map(Tour::getId).toList();
    }

    private List<ZoneBreachLog> breachesForTours(List<Long> tourIds) {
        if (tourIds.isEmpty()) {
            return List.of();
        }
        List<Long> positionIds = tourIds.stream()
                .flatMap(tid -> positionLogs.findByTourIdOrderByRecordedAtAsc(tid).stream())
                .map(VehiclePositionLog::getId).toList();
        if (positionIds.isEmpty()) {
            return List.of();
        }
        return breachLogRepository.findByPositionLogIdIn(positionIds);
    }

    private List<BreachResponse> toResponses(List<ZoneBreachLog> breaches,
                                             Long vehicleId, Long zoneId,
                                             Instant from, Instant to,
                                             Boolean unacknowledged) {
        return breaches.stream()
                // HISTORIC dummy (V38 backfill target) is bookkeeping, not fleet.
                .filter(b -> b.getVehicle() == null
                        || !"HISTORIC".equals(b.getVehicle().getRegistrationNumber()))
                .filter(b -> vehicleId == null || vehicleId.equals(b.getVehicle().getId()))
                .filter(b -> zoneId == null || zoneId.equals(b.getZone().getId()))
                .filter(b -> from == null || !b.getBreachTime().isBefore(from))
                .filter(b -> to == null || !b.getBreachTime().isAfter(to))
                .filter(b -> !Boolean.TRUE.equals(unacknowledged) || !b.getIsAcknowledged())
                .map(this::toResponse).collect(Collectors.toList());
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
