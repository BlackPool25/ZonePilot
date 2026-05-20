-- V14__vehicle_active_route.sql
-- Epic 3: Add active_dispatch_route_id to vehicle for stateful journey tracking.
-- Nullable FK — a vehicle without an active route has NULL here.
-- Deferrable to avoid circular FK issues during dispatch_route insert.

ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS active_dispatch_route_id BIGINT
        REFERENCES dispatch_route(id) ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX IF NOT EXISTS idx_vehicle_active_route
    ON vehicle (active_dispatch_route_id)
    WHERE active_dispatch_route_id IS NOT NULL;
