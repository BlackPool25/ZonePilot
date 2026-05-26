-- V20__fix_null_vehicle_class_in_rules.sql
--
-- BUG: V19 inserted NULL for applicable_vehicle_class when creating default rules
-- for zones with no rules. The trigger and sp_validate_route check:
--   applicable_vehicle_class = 'ALL' OR applicable_vehicle_class = v_vehicle_class
-- NULL matches neither condition, so zones with default rules never fire.
--
-- Fix 1: Backfill existing NULL values to 'ALL'.
-- Fix 2: Update sp_validate_route to treat NULL as 'ALL'.
-- Fix 3: Update trigger to treat NULL as 'ALL'.

-- Backfill NULL → 'ALL'
UPDATE zone_restriction_rule
SET applicable_vehicle_class = 'ALL'
WHERE applicable_vehicle_class IS NULL;

-- Update sp_validate_route to handle NULL (treat as 'ALL')
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
    v_current_time  TIME;
    v_day_bit       SMALLINT;
BEGIN
    SELECT vehicle_class INTO v_vehicle_class FROM vehicle WHERE id = p_vehicle_id;

    v_current_time := (NOW() AT TIME ZONE 'Asia/Kolkata')::TIME;
    v_day_bit := (1 << CASE WHEN EXTRACT(DOW FROM (NOW() AT TIME ZONE 'Asia/Kolkata'))::INT = 0
                             THEN 6
                             ELSE EXTRACT(DOW FROM (NOW() AT TIME ZONE 'Asia/Kolkata'))::INT - 1
                        END)::SMALLINT;

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
      AND (zrr.applicable_vehicle_class IS NULL
           OR zrr.applicable_vehicle_class = 'ALL'
           OR zrr.applicable_vehicle_class = v_vehicle_class)
      AND (zrr.days_of_week_bitmask & v_day_bit) > 0
      AND (
          (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
          OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
              AND zrr.restriction_start_time <= zrr.restriction_end_time
              AND v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
          OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
              AND zrr.restriction_start_time > zrr.restriction_end_time
              AND (v_current_time >= zrr.restriction_start_time OR v_current_time <= zrr.restriction_end_time))
      );
END;
$$ LANGUAGE plpgsql;

-- Update breach detection trigger to treat NULL as 'ALL'
CREATE OR REPLACE FUNCTION fn_detect_zone_breach()
RETURNS TRIGGER AS $$
DECLARE
    v_vehicle_class    VARCHAR(20);
    v_day_bit          SMALLINT;
    v_current_time     TIME;
    r_zone             RECORD;
    v_prev_breach_id   BIGINT;
    v_prev_position    GEOMETRY(Point, 4326);
    v_tick_dist        DOUBLE PRECISION := 0.0;
BEGIN
    SELECT vehicle_class INTO v_vehicle_class FROM vehicle WHERE id = NEW.vehicle_id;

    v_day_bit := (1 << CASE WHEN EXTRACT(DOW FROM (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata'))::INT = 0
                             THEN 6
                             ELSE EXTRACT(DOW FROM (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata'))::INT - 1
                        END)::SMALLINT;

    v_current_time := (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata')::TIME;

    FOR r_zone IN
        SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type
        FROM zone_restriction zr
        JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
        WHERE zr.is_active = TRUE
          AND zrr.is_active = TRUE
          AND ST_Covers(zr.boundary, NEW.position)
          AND (zrr.applicable_vehicle_class IS NULL
               OR zrr.applicable_vehicle_class = 'ALL'
               OR zrr.applicable_vehicle_class = v_vehicle_class)
          AND (zrr.days_of_week_bitmask & v_day_bit) > 0
          AND (
              (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
              OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
                  AND zrr.restriction_start_time <= zrr.restriction_end_time
                  AND v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
              OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
                  AND zrr.restriction_start_time > zrr.restriction_end_time
                  AND (v_current_time >= zrr.restriction_start_time OR v_current_time <= zrr.restriction_end_time))
          )
    LOOP
        SELECT id INTO v_prev_breach_id
        FROM zone_breach_log
        WHERE vehicle_id = NEW.vehicle_id
          AND zone_id = r_zone.zone_id
          AND end_time >= NEW.recorded_at - INTERVAL '60 seconds'
        ORDER BY end_time DESC
        LIMIT 1;

        IF v_prev_breach_id IS NOT NULL THEN
            SELECT position INTO v_prev_position
            FROM vehicle_position_log
            WHERE vehicle_id = NEW.vehicle_id
              AND recorded_at < NEW.recorded_at
            ORDER BY recorded_at DESC
            LIMIT 1;

            IF v_prev_position IS NOT NULL THEN
                v_tick_dist := ST_Distance(v_prev_position::geography, NEW.position::geography);
            END IF;

            UPDATE zone_breach_log
            SET end_time = NEW.recorded_at,
                distance_m = COALESCE(distance_m, 0.0) + COALESCE(v_tick_dist, 0.0),
                position_log_id = NEW.id
            WHERE id = v_prev_breach_id;
        ELSE
            INSERT INTO zone_breach_log (
                vehicle_id, zone_id, position_log_id,
                breach_time, end_time, breach_type, rule_id, distance_m
            ) VALUES (
                NEW.vehicle_id,
                r_zone.zone_id,
                NEW.id,
                NEW.recorded_at,
                NEW.recorded_at,
                CASE r_zone.restriction_type
                    WHEN 'NO_ENTRY' THEN 'NO_ENTRY'
                    WHEN 'TIME_RESTRICTED' THEN 'TIME_WINDOW'
                    ELSE 'VEHICLE_CLASS'
                END,
                r_zone.rule_id,
                0.0
            );
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
