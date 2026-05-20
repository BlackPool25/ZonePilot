-- V12__fix_stored_procedure_bitmask_and_limit.sql
-- BUG-007: Fix day-of-week bitmask in sp_validate_route (same correction as trigger).
-- BUG-009: Remove LIMIT 1 so all violated zones are returned, not just the first.

CREATE OR REPLACE FUNCTION sp_validate_route(
    p_vehicle_id      BIGINT,
    p_route_geometry  GEOMETRY(LINESTRING, 4326)
)
RETURNS TABLE (
    is_compliant         BOOLEAN,
    violated_zone_id     BIGINT,
    violated_zone_name   VARCHAR,
    breach_type          VARCHAR
) AS $$
DECLARE
    v_vehicle_class VARCHAR(20);
    v_current_time  TIME := CURRENT_TIME;
    v_day_bit       SMALLINT := (1 << CASE WHEN EXTRACT(DOW FROM NOW())::INT = 0
                                            THEN 6
                                            ELSE EXTRACT(DOW FROM NOW())::INT - 1
                                       END)::SMALLINT;
BEGIN
    SELECT vehicle_class INTO v_vehicle_class FROM vehicle WHERE id = p_vehicle_id;

    RETURN QUERY
    SELECT
        FALSE AS is_compliant,
        zr.id AS violated_zone_id,
        zr.name AS violated_zone_name,
        zr.restriction_type AS breach_type
    FROM zone_restriction zr
    JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
    WHERE zr.is_active = TRUE
      AND zrr.is_active = TRUE
      AND ST_Intersects(p_route_geometry, zr.boundary)
      AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = v_vehicle_class)
      AND (zrr.days_of_week_bitmask & v_day_bit) > 0
      AND (
          zrr.restriction_start_time IS NULL
          OR v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time
      );
END;
$$ LANGUAGE plpgsql;
