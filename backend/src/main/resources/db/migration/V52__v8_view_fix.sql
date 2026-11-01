-- V52__v8_view_fix.sql
-- V8 view fixes:
-- 1. v_active_vehicle_positions no longer joins depot (single-fleet model:
--    depots are gone) and ignores replay copies (is_replay=TRUE) plus the
--    HISTORIC dummy vehicle, so live tracking never shows a replay tail or
--    bookkeeping row as "latest".
-- 2. v_vehicle_breach_summary excludes replay-tainted fixes the same way
--    (HISTORIC exclusion from V48 retained).

CREATE OR REPLACE VIEW v_active_vehicle_positions AS
SELECT DISTINCT ON (v.id)
    v.id AS vehicle_id,
    v.registration_number,
    v.vehicle_class,
    vpl.position,
    ST_X(vpl.position) AS longitude,
    ST_Y(vpl.position) AS latitude,
    vpl.recorded_at,
    vpl.speed_kmh,
    vpl.source
FROM vehicle v
LEFT JOIN vehicle_position_log vpl
    ON vpl.vehicle_id = v.id
    AND vpl.is_replay = FALSE
WHERE v.is_active = TRUE
  AND v.registration_number <> 'HISTORIC'
ORDER BY v.id, vpl.recorded_at DESC;

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
