-- V8__views.sql
-- Reporting views

-- VIEW 1: Latest position per vehicle
CREATE OR REPLACE VIEW v_active_vehicle_positions AS
SELECT DISTINCT ON (v.id)
    v.id AS vehicle_id,
    v.registration_number,
    v.vehicle_class,
    d.name AS depot_name,
    vpl.position,
    ST_X(vpl.position) AS longitude,
    ST_Y(vpl.position) AS latitude,
    vpl.recorded_at,
    vpl.speed_kmh,
    vpl.source
FROM vehicle v
JOIN depot d ON d.id = v.depot_id
LEFT JOIN vehicle_position_log vpl ON vpl.vehicle_id = v.id
WHERE v.is_active = TRUE
ORDER BY v.id, vpl.recorded_at DESC;

-- VIEW 2: Breach summary per vehicle
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
GROUP BY v.id, v.registration_number, v.vehicle_class;

-- VIEW 3: Violation stats per zone
CREATE OR REPLACE VIEW v_zone_violation_stats AS
SELECT
    zr.id AS zone_id,
    zr.name AS zone_name,
    zr.restriction_type,
    COUNT(zbl.id) AS total_violations,
    COUNT(CASE WHEN v.vehicle_class = 'HCV' THEN 1 END) AS hcv_violations,
    COUNT(CASE WHEN v.vehicle_class = 'LCV' THEN 1 END) AS lcv_violations,
    COUNT(CASE WHEN v.vehicle_class = 'TWO_WHEELER' THEN 1 END) AS two_wheeler_violations,
    MAX(zbl.breach_time) AS last_violation_time
FROM zone_restriction zr
LEFT JOIN zone_breach_log zbl ON zbl.zone_id = zr.id
LEFT JOIN vehicle v ON v.id = zbl.vehicle_id
GROUP BY zr.id, zr.name, zr.restriction_type;

-- VIEW 4: Currently active restrictions
CREATE OR REPLACE VIEW v_currently_active_restrictions AS
SELECT
    zr.id, zr.name, zr.boundary, zr.restriction_type,
    zrr.applicable_vehicle_class,
    zrr.restriction_start_time, zrr.restriction_end_time
FROM zone_restriction zr
JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
WHERE zr.is_active = TRUE
  AND zrr.is_active = TRUE
  AND (
      (zrr.restriction_start_time IS NULL)
      OR (CURRENT_TIME BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
  )
  AND (zrr.days_of_week_bitmask & (1 << (EXTRACT(DOW FROM NOW())::INT % 7))) > 0;
