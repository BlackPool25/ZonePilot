-- V18__stateful_breaches.sql
-- Implement stateful breach tracking: 
-- A breach is started when a vehicle enters a restricted zone and is continuously updated 
-- on subsequent ticks while it remains inside the zone. If it leaves and re-enters, a new breach is logged.

-- 1. Add end_time and distance_m columns to zone_breach_log if they don't exist
ALTER TABLE zone_breach_log ADD COLUMN IF NOT EXISTS end_time TIMESTAMPTZ;
ALTER TABLE zone_breach_log ADD COLUMN IF NOT EXISTS distance_m DOUBLE PRECISION DEFAULT 0.0;

-- 2. Backfill existing data
UPDATE zone_breach_log SET end_time = breach_time WHERE end_time IS NULL;

-- 3. Update the trigger function to handle stateful breaches
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
    -- Get vehicle class
    SELECT vehicle_class INTO v_vehicle_class
    FROM vehicle WHERE id = NEW.vehicle_id;

    -- Derive day bit from IST timestamp (Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64)
    v_day_bit := (1 << CASE WHEN EXTRACT(DOW FROM (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata'))::INT = 0
                             THEN 6
                             ELSE EXTRACT(DOW FROM (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata'))::INT - 1
                        END)::SMALLINT;

    -- Derive current time in IST
    v_current_time := (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata')::TIME;

    -- Check if position matches any active restricted zone under rules
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
              -- Normal window (start <= end)
              OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
                  AND zrr.restriction_start_time <= zrr.restriction_end_time
                  AND v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
              -- Overnight window (start > end)
              OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
                  AND zrr.restriction_start_time > zrr.restriction_end_time
                  AND (v_current_time >= zrr.restriction_start_time OR v_current_time <= zrr.restriction_end_time))
          )
    LOOP
        -- Check if there is an active/open breach log for this vehicle and zone
        -- Active means the last tick's end_time is within 60 seconds of this position log
        SELECT id INTO v_prev_breach_id
        FROM zone_breach_log
        WHERE vehicle_id = NEW.vehicle_id
          AND zone_id = r_zone.zone_id
          AND end_time >= NEW.recorded_at - INTERVAL '60 seconds'
        ORDER BY end_time DESC
        LIMIT 1;

        IF v_prev_breach_id IS NOT NULL THEN
            -- Calculate distance traveled since last tick if we can find the previous position
            SELECT position INTO v_prev_position
            FROM vehicle_position_log
            WHERE vehicle_id = NEW.vehicle_id
              AND recorded_at < NEW.recorded_at
            ORDER BY recorded_at DESC
            LIMIT 1;

            IF v_prev_position IS NOT NULL THEN
                v_tick_dist := ST_Distance(v_prev_position::geography, NEW.position::geography);
            END IF;

            -- Update existing open breach record
            UPDATE zone_breach_log
            SET end_time = NEW.recorded_at,
                distance_m = COALESCE(distance_m, 0.0) + COALESCE(v_tick_dist, 0.0),
                position_log_id = NEW.id
            WHERE id = v_prev_breach_id;
        ELSE
            -- No active open breach found; create a new stateful breach entry
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
