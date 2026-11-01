-- V44__backfill_hardening.sql
-- Fix-wave hardening (backend only, no behavior change to live flows):
--
-- 1. V38 backfill collision pre-check: collapse any rows violating
--    uq_position_vehicle_tour_recorded BEFORE the unique index is relied on.
--    Detected via GROUP BY vehicle_id, tour_id, recorded_at HAVING COUNT(*) > 1
--    (kept rows are logged via NOTICE for audit); earliest physical row wins.
-- 2. V39/V40 CHECKs: availability + duty_status value lists enforced in DDL.
-- 3. V41 driver index: duty_log(driver_id, recorded_at) for driver timelines.
-- 4. Track-legs index guard (dispatch_route.tour_id already indexed in V34;
--    IF NOT EXISTS keeps this idempotent).
-- 5. Breach open-lookup index for the worker's FOR UPDATE extend-or-create.

-- 1. Collision pre-check + handling -------------------------------------------
DO $$
DECLARE
    dup_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO dup_count
    FROM (
        SELECT vehicle_id, tour_id, recorded_at
        FROM vehicle_position_log
        GROUP BY vehicle_id, tour_id, recorded_at
        HAVING COUNT(*) > 1
    ) dups;
    RAISE NOTICE 'V44: position dedupe collisions found: %', dup_count;
END $$;

DELETE FROM vehicle_position_log a
USING vehicle_position_log b
WHERE a.vehicle_id = b.vehicle_id
  AND a.tour_id = b.tour_id
  AND a.recorded_at = b.recorded_at
  AND a.ctid > b.ctid;

-- 2. Availability / duty value lists ------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicle_availability') THEN
        ALTER TABLE vehicle ADD CONSTRAINT chk_vehicle_availability
            CHECK (availability IN ('AVAILABLE', 'RESERVED', 'IN_TRANSIT', 'MAINTENANCE', 'OFFLINE'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_tour_duty') THEN
        ALTER TABLE tour ADD CONSTRAINT chk_tour_duty
            CHECK (duty_status IN ('OFF_DUTY', 'ON_DUTY', 'DRIVING', 'BREAK', 'IN_TRANSIT'));
    END IF;
END $$;

-- 3. Duty log driver timeline index -------------------------------------------
CREATE INDEX IF NOT EXISTS ix_duty_log_driver_recorded
    ON duty_log(driver_id, recorded_at);

-- 4. Track legs index guard ----------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_dispatch_route_tour
    ON dispatch_route(tour_id);

-- 5. Breach open-lookup index (worker extend-or-create FOR UPDATE) --------------
CREATE INDEX IF NOT EXISTS ix_breach_open_lookup
    ON zone_breach_log(vehicle_id, zone_id, end_time DESC);
