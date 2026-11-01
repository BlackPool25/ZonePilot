-- V51__audit_feedback.sql
-- Audit-feedback hardening columns + tables. All additive; safe to replay.

-- Per-device MQTT credential (unset = legacy open pipe).
ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS device_api_key VARCHAR(100);

-- Tour creator for approve separation-of-duties.
ALTER TABLE tour
    ADD COLUMN IF NOT EXISTS created_by_user_id BIGINT;

-- Per-stop PoD policy + server-hashed PIN + evidence refs + partial qty.
ALTER TABLE tour_stop
    ADD COLUMN IF NOT EXISTS pod_policy VARCHAR(10) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS pin_hash TEXT,
    ADD COLUMN IF NOT EXISTS qty_delivered INTEGER,
    ADD COLUMN IF NOT EXISTS pod_photo_ref TEXT,
    ADD COLUMN IF NOT EXISTS pod_sig_ref TEXT;

-- Duty annotations.
ALTER TABLE duty_log
    ADD COLUMN IF NOT EXISTS note TEXT;

-- SOS resolution.
ALTER TABLE sos_alert
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_note TEXT;

-- Audit trail for dispatch/PoD/skip/approve/SOS/ingest-reject.
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(40) NOT NULL,
    tour_id BIGINT,
    stop_id BIGINT,
    reason TEXT,
    ip VARCHAR(60),
    device VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_audit_log_tour ON audit_log (tour_id);
CREATE INDEX IF NOT EXISTS ix_audit_log_created ON audit_log (created_at DESC);

-- Client idempotency keys (PoD/duty/SOS/positions).
CREATE TABLE IF NOT EXISTS idempotency_record (
    op_id VARCHAR(100) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- OSM import bookkeeping for the ops map-version endpoint.
CREATE TABLE IF NOT EXISTS osm_import_meta (
    id BIGSERIAL PRIMARY KEY,
    extract_name VARCHAR(200) NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    nodes_count BIGINT,
    ways_count BIGINT,
    note TEXT
);
