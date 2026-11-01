package com.zonepilot.backend.service;

import com.zonepilot.backend.auth.GroupScope;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.exception.ResourceNotFoundException;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.TourRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportingService {

    private final ZoneBreachLogRepository breachLogRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TourRepository tours;
    private final FleetGroupRepository groups;
    private final GroupScope scope;

    public ReportingService(ZoneBreachLogRepository breachLogRepository,
                            JdbcTemplate jdbcTemplate,
                            TourRepository tours,
                            FleetGroupRepository groups,
                            GroupScope scope) {
        this.breachLogRepository = breachLogRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.tours = tours;
        this.groups = groups;
        this.scope = scope;
    }

    public List<Map<String, Object>> getVehicleBreachSummary() {
        return getVehicleBreachSummary(null, null);
    }

    /**
     * Fleet breach summary, optionally scoped to one tour (its vehicle) or one
     * group (vehicles running that group's tours). Managers without a filter
     * default to their own groups — never the whole fleet.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getVehicleBreachSummary(Long tourId, Long groupId) {
        java.util.Set<Long> keep = null;
        if (tourId != null) {
            Tour tour = tours.findById(tourId)
                    .orElseThrow(() -> new ResourceNotFoundException("Tour", "id", tourId));
            scope.requireGroupAccess(tour.getGroup());
            keep = java.util.Set.of(tour.getVehicle().getId());
        } else if (groupId != null) {
            FleetGroup group = groups.findById(groupId)
                    .orElseThrow(() -> new ResourceNotFoundException("FleetGroup", "id", groupId));
            scope.requireGroupAccess(group);
            keep = vehicleIdsInToursOf(groupId);
        } else if (!scope.isOwner() && scope.currentUserId().isPresent()) {
            Long uid = scope.currentUserId().get();
            keep = groups.findByManager_IdOrderByCreatedAtDesc(uid).stream()
                    .flatMap(g -> vehicleIdsInToursOf(g.getId()).stream())
                    .collect(java.util.stream.Collectors.toSet());
        }
        final java.util.Set<Long> only = keep;
        List<Object[]> rows = breachLogRepository.getVehicleBreachSummary();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            if (only != null && (row[0] == null || !only.contains(((Number) row[0]).longValue()))) {
                continue;
            }
            Map<String, Object> map = new HashMap<>();
            map.put("vehicleId", row[0]);
            map.put("registrationNumber", row[1]);
            map.put("vehicleClass", row[2]);
            map.put("totalBreaches", row[3]);
            map.put("noEntryBreaches", row[4]);
            map.put("timeWindowBreaches", row[5]);
            map.put("classBreaches", row[6]);
            map.put("lastBreachTime", row[7]);
            map.put("unacknowledgedBreaches", row[8]);
            results.add(map);
        }
        return results;
    }

    private java.util.Set<Long> vehicleIdsInToursOf(Long groupId) {
        return tours.findByGroupId(groupId).stream()
                .map(t -> t.getVehicle().getId())
                .collect(java.util.stream.Collectors.toSet());
    }

    public List<Map<String, Object>> getZoneViolationStats() {
        List<Object[]> rows = breachLogRepository.getZoneViolationStats();
        return mapZoneViolationRows(rows);
    }

    public List<Map<String, Object>> getZoneViolationStatsByZoneId(Long zoneId) {
        List<Object[]> rows = breachLogRepository.getZoneViolationStatsByZoneId(zoneId);
        return mapZoneViolationRows(rows);
    }

    private List<Map<String, Object>> mapZoneViolationRows(List<Object[]> rows) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> map = new HashMap<>();
            map.put("zoneId", row[0]);
            map.put("zoneName", row[1]);
            map.put("restrictionType", row[2]);
            map.put("totalViolations", row[3]);
            map.put("hcvViolations", row[4]);
            map.put("lcvViolations", row[5]);
            map.put("twoWheelerViolations", row[6]);
            map.put("lastViolationTime", row[7]);
            results.add(map);
        }
        return results;
    }

    public List<Map<String, Object>> getCurrentlyActiveRestrictions() {
        return jdbcTemplate.query(
                "SELECT id, name, restriction_type, applicable_vehicle_class, "
                        + "restriction_start_time, restriction_end_time "
                        + "FROM v_currently_active_restrictions",
                (rs, rowNum) -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", rs.getLong("id"));
                    map.put("name", rs.getString("name"));
                    map.put("restrictionType", rs.getString("restriction_type"));
                    map.put("applicableVehicleClass", rs.getString("applicable_vehicle_class"));
                    java.sql.Time startTime = rs.getTime("restriction_start_time");
                    java.sql.Time endTime = rs.getTime("restriction_end_time");
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
                    map.put("restrictionStartTime", startTime != null
                            ? startTime.toLocalTime().format(fmt) : null);
                    map.put("restrictionEndTime", endTime != null
                            ? endTime.toLocalTime().format(fmt) : null);
                    return map;
                });
    }
}
