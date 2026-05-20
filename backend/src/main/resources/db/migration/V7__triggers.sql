-- V7__triggers.sql
-- Breach detection trigger on vehicle_position_log

CREATE OR REPLACE FUNCTION fn_detect_zone_breach()
RETURNS TRIGGER AS $$
DECLARE
    v_vehicle_class VARCHAR(20);
    v_day_bit       SMALLINT;
    v_current_time  TIME;
    r_zone          RECORD;
BEGIN
    -- Get vehicle class for the incoming position
    SELECT vehicle_class INTO v_vehicle_class
    FROM vehicle WHERE id = NEW.vehicle_id;

    -- Get current day bitmask (Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64)
    v_day_bit := (1 << (EXTRACT(DOW FROM NEW.recorded_at)::INT % 7));
    v_current_time := NEW.recorded_at::TIME;

    -- Find any active zone that contains this point AND matches vehicle class AND time window
    FOR r_zone IN
        SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type
        FROM zone_restriction zr
        JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
        WHERE zr.is_active = TRUE
          AND zrr.is_active = TRUE
          AND ST_Within(NEW.position, zr.boundary)
          AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = v_vehicle_class)
          AND (zrr.days_of_week_bitmask & v_day_bit) > 0
          AND (
               (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
               OR (v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
          )
    LOOP
        INSERT INTO zone_breach_log (
            vehicle_id, zone_id, position_log_id,
            breach_time, breach_type, rule_id
        ) VALUES (
            NEW.vehicle_id,
            r_zone.zone_id,
            NEW.id,
            NEW.recorded_at,
            CASE r_zone.restriction_type
                WHEN 'NO_ENTRY' THEN 'NO_ENTRY'
                WHEN 'TIME_RESTRICTED' THEN 'TIME_WINDOW'
                ELSE 'VEHICLE_CLASS'
            END,
            r_zone.rule_id
        );
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Attach trigger to the partitioned parent table
CREATE TRIGGER trg_detect_zone_breach
AFTER INSERT ON vehicle_position_log
FOR EACH ROW
EXECUTE FUNCTION fn_detect_zone_breach();
