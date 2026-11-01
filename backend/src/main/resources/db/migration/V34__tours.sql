-- V34__tours.sql
-- Tour engine core: tour owns ordered stops with full manifest;
-- dispatch_route links to the tour it is currently serving.

CREATE TABLE tour (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL REFERENCES fleet_group(id),
    vehicle_id BIGINT NOT NULL REFERENCES vehicle(id),
    driver_id BIGINT NOT NULL REFERENCES driver(id),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tour_stop (
    id BIGSERIAL PRIMARY KEY,
    tour_id BIGINT NOT NULL REFERENCES tour(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    point geometry(Point,4326) NOT NULL,
    address TEXT,
    quantity INT,
    weight_kg DOUBLE PRECISION,
    recipient_name TEXT,
    recipient_phone VARCHAR(20),
    window_start TIMESTAMPTZ,
    window_end TIMESTAMPTZ,
    cod_amount NUMERIC(12,2),
    handling_note TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    delivered_at TIMESTAMPTZ,
    skip_reason TEXT,
    pod_lat DOUBLE PRECISION,
    pod_lng DOUBLE PRECISION,
    CONSTRAINT uq_tour_stop_seq UNIQUE (tour_id, seq)
);
CREATE INDEX idx_tour_stop_tour ON tour_stop(tour_id, seq);

ALTER TABLE dispatch_route ADD COLUMN IF NOT EXISTS tour_id BIGINT REFERENCES tour(id);
CREATE INDEX IF NOT EXISTS idx_dispatch_route_tour ON dispatch_route(tour_id);
