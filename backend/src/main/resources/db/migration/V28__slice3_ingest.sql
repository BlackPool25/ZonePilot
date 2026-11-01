-- Slice 3 (Traccar ingest): quarantine dead-letter table + TRACCAR source.
CREATE TABLE IF NOT EXISTS position_quarantine (
    id           BIGSERIAL PRIMARY KEY,
    vehicle_id   BIGINT NOT NULL,
    recorded_at  TIMESTAMPTZ,
    reason       VARCHAR(40) NOT NULL,
    payload      JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_quarantine_vehicle_created
    ON position_quarantine (vehicle_id, created_at);

ALTER TABLE vehicle_position_log DROP CONSTRAINT IF EXISTS vehicle_position_log_source_check;
ALTER TABLE vehicle_position_log ADD CONSTRAINT vehicle_position_log_source_check
    CHECK (source IN ('LIVE', 'SIMULATED', 'TRACCAR'));
