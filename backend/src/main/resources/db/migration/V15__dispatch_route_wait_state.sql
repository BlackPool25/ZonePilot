-- V15__dispatch_route_wait_state.sql
-- Epic 4: Add wait-state columns to dispatch_route for predictive curfew optimization.
--
-- wait_until:        ISO-8601 timestamp when the vehicle may depart (curfew end time)
-- wait_duration_sec: integer seconds the vehicle must wait before departing

ALTER TABLE dispatch_route
    ADD COLUMN IF NOT EXISTS wait_until       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS wait_duration_sec INTEGER;
