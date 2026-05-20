package com.zonepilot.backend.service;

import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import com.zonepilot.backend.repository.ZoneBreachLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportingService {

    private final ZoneBreachLogRepository breachLogRepository;
    private final JdbcTemplate jdbcTemplate;

    public ReportingService(ZoneBreachLogRepository breachLogRepository,
                            JdbcTemplate jdbcTemplate) {
        this.breachLogRepository = breachLogRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> getVehicleBreachSummary() {
        List<Object[]> rows = breachLogRepository.getVehicleBreachSummary();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object[] row : rows) {
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

    public List<Map<String, Object>> getZoneViolationStats() {
        List<Object[]> rows = breachLogRepository.getZoneViolationStats();
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
                    map.put("restrictionStartTime", rs.getTime("restriction_start_time"));
                    map.put("restrictionEndTime", rs.getTime("restriction_end_time"));
                    return map;
                });
    }
}
