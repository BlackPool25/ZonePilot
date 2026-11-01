CREATE TABLE IF NOT EXISTS dispatch_route_attempt (
    id BIGSERIAL PRIMARY KEY,
    dispatch_route_id BIGINT NOT NULL REFERENCES dispatch_route(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    planned_route_geometry geometry(LineString, 4326),
    penalised_zone_ids TEXT,
    violated_zone_ids TEXT,
    accepted BOOLEAN NOT NULL,
    reject_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dispatch_route_id, attempt_number)
);
CREATE INDEX IF NOT EXISTS idx_route_attempt_route ON dispatch_route_attempt(dispatch_route_id);
