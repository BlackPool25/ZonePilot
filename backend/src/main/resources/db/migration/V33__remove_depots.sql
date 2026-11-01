-- V33__remove_depots.sql
-- Single-fleet model: depots are gone. Drops the depot partition columns,
-- the staff depot requirement, and the depot table; rebuilds the one
-- reporting view that joined it (VIEW 1 of V8).

-- Rebuild the reporting view WITHOUT the depot join first: the old
-- view depends on vehicle.depot_id and would block the column drops.
DROP VIEW IF EXISTS v_active_vehicle_positions;
CREATE VIEW v_active_vehicle_positions AS
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
LEFT JOIN vehicle_position_log vpl ON vpl.vehicle_id = v.id
WHERE v.is_active = TRUE
ORDER BY v.id, vpl.recorded_at DESC;

ALTER TABLE app_user DROP CONSTRAINT IF EXISTS app_user_depot_required_for_staff;
ALTER TABLE vehicle DROP COLUMN IF EXISTS depot_id;
ALTER TABLE driver DROP COLUMN IF EXISTS depot_id;
ALTER TABLE app_user DROP COLUMN IF EXISTS depot_id;
DROP TABLE IF EXISTS depot;
