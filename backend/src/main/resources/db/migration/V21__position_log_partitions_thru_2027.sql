-- V21__position_log_partitions_thru_2027.sql
-- Slice 1 (PG boot): extend vehicle_position_log monthly partitions past the
-- V1 set (2025-01..2025-06 + DEFAULT) through end of 2027. All comparisons in
-- triggers/procedures use recorded_at (timestamptz), never server-local NOW().
DO $$
DECLARE
    m DATE;
BEGIN
    FOR m IN SELECT generate_series(DATE '2025-07-01', DATE '2027-12-01', INTERVAL '1 month')::DATE
    LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS vehicle_position_log_%s PARTITION OF vehicle_position_log FOR VALUES FROM (%L) TO (%L)',
            to_char(m, 'YYYY_MM'), m, m + INTERVAL '1 month');
    END LOOP;
END
$$;

-- shortcut: partitions older than the 13-month trip retention window are NOT
-- auto-dropped here (no pg_cron on the Fly Machine); add a scheduled DETACH
-- job before data older than 2025-01 accumulates in the DEFAULT partition.
