package com.zonepilot.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Partition rollover job: ensures monthly vehicle_position_log partitions
 * exist {@code monthsAhead} into the future (CREATE TABLE IF NOT EXISTS —
 * safe to run on a schedule). Run it monthly; without it, new fixes land in
 * DEFAULT (or fail) and the partition monitor trips WARN.
 */
@Service
public class PartitionRolloverService {

    private static final Logger log = LoggerFactory.getLogger(PartitionRolloverService.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcTemplate jdbc;

    public PartitionRolloverService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates missing monthly partitions from now through monthsAhead. */
    public List<String> ensureFuturePartitions(int monthsAhead) {
        List<String> created = new ArrayList<>();
        LocalDate cursor = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i <= monthsAhead; i++) {
            LocalDate month = cursor.plusMonths(i);
            String name = "vehicle_position_log_" + month.format(SUFFIX);
            LocalDate next = month.plusMonths(1);
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + name
                    + " PARTITION OF vehicle_position_log FOR VALUES FROM ('"
                    + month + "') TO ('" + next + "')");
            created.add(name);
        }
        log.info("partition.rollover ensured={}", created.size());
        return created;
    }
}
