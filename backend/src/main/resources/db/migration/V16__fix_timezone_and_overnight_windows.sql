-- V16__fix_timezone_and_overnight_windows.sql
--
-- BUG-NEW-001 (CRITICAL): Timezone mismatch — trigger compared UTC time against IST
--   restriction times. Fix: convert recorded_at to Asia/Kolkata before extracting TIME.
--
-- BUG-NEW-005 (HIGH): Overnight time windows (e.g. 22:00–06:00) never fired because
--   BETWEEN returns FALSE when start > end. Fix: detect overnight windows and use
--   (time >= start OR time <= end) for those cases.
--
-- BUG-NEW-002 (HIGH): Stored procedure used asymmetric NULL check
--   (start IS NULL OR BETWEEN) while trigger used (start IS NULL AND end IS NULL) OR BETWEEN.
--   Fix: unify both to the same logic, including overnight window support.

-- ── Trigger function ─────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION fn_detect_zone_breach()
RETURNS TRIGGER AS $$
DECLARE
    v_vehicle_class VARCHAR(20);
    v_day_bit       SMALLINT;
    v_current_time  TIME;
    r_zone          RECORD;
BEGIN
    SELECT vehicle_class INTO v_vehicle_class
    FROM vehicle WHERE id = NEW.vehicle_id;

    -- Corrected bitmask: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64
    v_day_bit := (1 << CASE WHEN EXTRACT(DOW FROM (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata'))::INT = 0
                             THEN 6
                             ELSE EXTRACT(DOW FROM (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata'))::INT - 1
                        END)::SMALLINT;

    -- BUG-NEW-001: convert to IST before extracting time
    v_current_time := (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata')::TIME;

    FOR r_zone IN
        SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type
        FROM zone_restriction zr
        JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
        WHERE zr.is_active = TRUE
          AND zrr.is_active = TRUE
          AND ST_Covers(zr.boundary, NEW.position)
          AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = v_vehicle_class)
          AND (zrr.days_of_week_bitmask & v_day_bit) > 0
          AND (
              -- No time restriction: always active
              (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
              -- Normal window (start <= end, e.g. 07:00–21:00)
              OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
                  AND zrr.restriction_start_time <= zrr.restriction_end_time
                  AND v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
              -- Overnight window (start > end, e.g. 22:00–06:00)
              OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
                  AND zrr.restriction_start_time > zrr.restriction_end_time
                  AND (v_current_time >= zrr.restriction_start_time OR v_current_time <= zrr.restriction_end_time))
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

-- ── Stored procedure ─────────────────────────────────────────────────────────

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
    -- BUG-NEW-001: use IST for time comparison
    v_current_time  TIME;
    -- BUG-NEW-001: derive day bit from IST timestamp
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
      AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = v_vehicle_class)
      AND (zrr.days_of_week_bitmask & v_day_bit) > 0
      AND (
          -- BUG-NEW-002: match trigger's NULL check exactly
          (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
          -- Normal window (start <= end)
          OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
              AND zrr.restriction_start_time <= zrr.restriction_end_time
              AND v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
          -- BUG-NEW-005: overnight window (start > end)
          OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
              AND zrr.restriction_start_time > zrr.restriction_end_time
              AND (v_current_time >= zrr.restriction_start_time OR v_current_time <= zrr.restriction_end_time))
      );
END;
$$ LANGUAGE plpgsql;
