-- V47__replay_isolation.sql
-- Replay isolation: replay re-inserts are stamped is_replay so live
-- reports/track read only live rows by default (source='REPLAY' alone was
-- not filterable without touching every query).
ALTER TABLE vehicle_position_log
    ADD COLUMN IF NOT EXISTS is_replay BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: rows inserted by earlier replays (source REPLAY) predate the flag.
UPDATE vehicle_position_log
   SET is_replay = TRUE
 WHERE source = 'REPLAY' AND is_replay = FALSE;
