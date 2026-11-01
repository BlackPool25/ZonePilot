-- Replay inserts fixes with source='REPLAY'; the V28 check predates it.
ALTER TABLE vehicle_position_log DROP CONSTRAINT IF EXISTS vehicle_position_log_source_check;
ALTER TABLE vehicle_position_log ADD CONSTRAINT vehicle_position_log_source_check
    CHECK (source IN ('LIVE', 'SIMULATED', 'TRACCAR', 'REPLAY'));
