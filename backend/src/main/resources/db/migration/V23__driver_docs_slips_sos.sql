-- V23 crew/docs (Slice 2): driver roster, vehicle documents, trip slips, SOS.
-- One live row per (vehicle_id, type): renewal overwrites via upsert.

CREATE TABLE driver (
    id BIGSERIAL PRIMARY KEY,
    depot_id BIGINT NOT NULL REFERENCES depot(id),
    user_id BIGINT REFERENCES app_user(id),
    name TEXT NOT NULL,
    phone TEXT,
    licence_no TEXT NOT NULL UNIQUE,
    licence_expiry DATE,
    salary_mode TEXT NOT NULL DEFAULT 'PER_TRIP'
        CHECK (salary_mode IN ('PER_TRIP', 'MONTHLY')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_driver_depot ON driver (depot_id);
CREATE INDEX idx_driver_user ON driver (user_id);

CREATE TABLE vehicle_document (
    id BIGSERIAL PRIMARY KEY,
    vehicle_id BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('FITNESS', 'PERMIT', 'INSURANCE', 'PUC')),
    doc_no TEXT NOT NULL,
    expiry_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (vehicle_id, type)
);
CREATE INDEX idx_vehicle_document_expiry ON vehicle_document (expiry_date);

CREATE TABLE trip_slip (
    id BIGSERIAL PRIMARY KEY,
    route_id BIGINT NOT NULL REFERENCES dispatch_route(id),
    driver_id BIGINT NOT NULL REFERENCES driver(id),
    kind TEXT NOT NULL CHECK (kind IN ('FUEL', 'TOLL')),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_trip_slip_route ON trip_slip (route_id);

CREATE TABLE sos_alert (
    id BIGSERIAL PRIMARY KEY,
    driver_id BIGINT NOT NULL REFERENCES driver(id),
    route_id BIGINT REFERENCES dispatch_route(id),
    location GEOMETRY(Point, 4326) NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACKED', 'RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sos_alert_driver ON sos_alert (driver_id);

ALTER TABLE dispatch_route
    ADD COLUMN driver_id BIGINT REFERENCES driver(id),
    ADD COLUMN assignment_status TEXT NOT NULL DEFAULT 'UNASSIGNED'
        CHECK (assignment_status IN ('UNASSIGNED', 'ASSIGNED', 'ACCEPTED', 'IN_TRANSIT', 'DELIVERED')),
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN started_at TIMESTAMPTZ;
CREATE INDEX idx_dispatch_route_driver ON dispatch_route (driver_id);
