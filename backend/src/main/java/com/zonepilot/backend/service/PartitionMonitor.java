package com.zonepilot.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Partition health for the monthly vehicle_position_log partitions:
 * which month the partitions run through, and whether the DEFAULT
 * partition is collecting rows (a routing gap that needs a rollover).
 */
@Service
public class PartitionMonitor {

    private static final Logger log = LoggerFactory.getLogger(PartitionMonitor.class);

    /** DEFAULT rows above this trip the WARN status on the ops endpoint. */
    public static final long DEFAULT_WARN_THRESHOLD = 1000;

    private final JdbcTemplate jdbc;

    public PartitionMonitor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Report(List<String> partitions, long defaultRows, String status) {}

    public Report report() {
        List<String> partitions;
        long defaultRows;
        try {
            partitions = jdbc.queryForList(
                    "SELECT inhrelid::regclass::text FROM pg_inherits "
                            + "WHERE inhparent = 'vehicle_position_log'::regclass "
                            + "ORDER BY 1",
                    String.class);
        } catch (RuntimeException e) {
            log.warn("partition.monitor failed: {}", e.toString());
            return new Report(List.of(), -1, "UNKNOWN");
        }
        try {
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM vehicle_position_log_default", Long.class);
            defaultRows = n == null ? 0 : n;
        } catch (RuntimeException e) {
            // No DEFAULT partition (or no partitions at all, e.g. H2 tests).
            defaultRows = 0;
        }
        String status = defaultRows > DEFAULT_WARN_THRESHOLD ? "WARN" : "OK";
        return new Report(partitions, defaultRows, status);
    }
}
