-- V17__fix_view_and_repo_timezone.sql
--
-- BUG-01: v_currently_active_restrictions used CURRENT_TIME (UTC) and
--   EXTRACT(DOW FROM NOW()) without IST conversion, causing active IST-offset
--   rules to be invisible.
--
-- BUG-02: ZoneRestrictionRuleRepository.findCurrentlyActiveRulesForZone used
--   the same UTC-based expressions. The repository query is a @Query annotation
--   fixed in Java; this migration only fixes the view.
--
-- Fix: replace CURRENT_TIME with (NOW() AT TIME ZONE 'Asia/Kolkata')::TIME
--   and EXTRACT(DOW FROM NOW()) with EXTRACT(DOW FROM (NOW() AT TIME ZONE 'Asia/Kolkata')).
--   Add overnight window support (start > end) matching the V16 trigger pattern.

CREATE OR REPLACE VIEW v_currently_active_restrictions AS
SELECT
    zr.id, zr.name, zr.boundary, zr.restriction_type,
    zrr.applicable_vehicle_class,
    zrr.restriction_start_time, zrr.restriction_end_time
FROM zone_restriction zr
JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
WHERE zr.is_active = TRUE
  AND zrr.is_active = TRUE
  AND (zrr.days_of_week_bitmask & (1 << (EXTRACT(DOW FROM (NOW() AT TIME ZONE 'Asia/Kolkata'))::INT % 7))) > 0
  AND (
      -- No time restriction: always active
      (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
      -- Normal window (start <= end, e.g. 07:00–21:00)
      OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
          AND zrr.restriction_start_time <= zrr.restriction_end_time
          AND (NOW() AT TIME ZONE 'Asia/Kolkata')::TIME
              BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
      -- Overnight window (start > end, e.g. 22:00–06:00)
      OR (zrr.restriction_start_time IS NOT NULL AND zrr.restriction_end_time IS NOT NULL
          AND zrr.restriction_start_time > zrr.restriction_end_time
          AND ((NOW() AT TIME ZONE 'Asia/Kolkata')::TIME >= zrr.restriction_start_time
               OR (NOW() AT TIME ZONE 'Asia/Kolkata')::TIME <= zrr.restriction_end_time))
  );
