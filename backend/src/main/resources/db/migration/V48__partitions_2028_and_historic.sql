-- V48__partitions_2028_and_historic.sql
-- 1. Partition rollover: V21 covered 2025-07..2027-12. Extend monthly
--    partitions through end of 2028. ROLLOVER JOB NOTE: add one migration
--    per year (V49 for 2029, ...) each Q4, and schedule a DETACH/DROP of
--    partitions older than the 13-month trip retention window (no pg_cron
--    on the Fly Machine — run it from the deploy pipeline or a yearly
--    ops ticket, see docs/70-OSM-REFRESH-RUNBOOK.md).
DO $$
DECLARE
    m DATE;
BEGIN
    FOR m IN SELECT generate_series(DATE '2028-01-01', DATE '2028-12-01', INTERVAL '1 month')::DATE
    LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS vehicle_position_log_%s PARTITION OF vehicle_position_log FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYY_MM'), m, m + INTERVAL '1 month');
    END LOOP;
END
$$;

-- 2. HISTORIC dummy (V38 backfill target) excluded from the vehicle breach
--    summary: the dummy vehicle/licence rows are bookkeeping, not fleet.
CREATE OR REPLACE VIEW v_vehicle_breach_summary AS
SELECT
    v.id AS vehicle_id,
    v.registration_number,
    v.vehicle_class,
    COUNT(zbl.id) AS total_breaches,
    COUNT(CASE WHEN zbl.breach_type = 'NO_ENTRY' THEN 1 END) AS no_entry_breaches,
    COUNT(CASE WHEN zbl.breach_type = 'TIME_WINDOW' THEN 1 END) AS time_window_breaches,
    COUNT(CASE WHEN zbl.breach_type = 'VEHICLE_CLASS' THEN 1 END) AS class_breaches,
    MAX(zbl.breach_time) AS last_breach_time,
    COUNT(CASE WHEN zbl.is_acknowledged = FALSE THEN 1 END) AS unacknowledged_breaches
FROM vehicle v
LEFT JOIN zone_breach_log zbl ON zbl.vehicle_id = v.id
WHERE v.registration_number <> 'HISTORIC'
GROUP BY v.id, v.registration_number, v.vehicle_class;
