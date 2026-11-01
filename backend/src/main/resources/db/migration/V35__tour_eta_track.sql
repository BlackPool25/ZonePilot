-- V35: per-stop ETAs + validated-leg travel time (tour ETA/track slice).
-- All nullable: unknown until a leg is planned/validated.

ALTER TABLE tour_stop
    ADD COLUMN IF NOT EXISTS eta_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS leg_duration_sec BIGINT;

ALTER TABLE dispatch_route
    ADD COLUMN IF NOT EXISTS travel_duration_sec BIGINT;
