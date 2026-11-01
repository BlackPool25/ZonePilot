-- V22__app_user_and_position_dedupe.sql
-- Slice 1 (auth + depot RBAC): application users with depot-scoped roles.
CREATE TABLE app_user (
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(255) NOT NULL UNIQUE,
    password_hash       TEXT NOT NULL,
    role                VARCHAR(20) NOT NULL
                        CHECK (role IN ('OWNER','DISPATCHER','DRIVER')),
    depot_id            BIGINT REFERENCES depot(id) ON DELETE RESTRICT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    refresh_token_hash  TEXT,
    refresh_expires_at  TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT app_user_depot_required_for_staff
        CHECK (role = 'OWNER' OR depot_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_app_user_depot ON app_user(depot_id);

-- Ingest dedupe key (plan §pipeline 4a): identical vehicle+timestamp accepted once.
CREATE UNIQUE INDEX IF NOT EXISTS uq_position_vehicle_recorded
    ON vehicle_position_log(vehicle_id, recorded_at);
