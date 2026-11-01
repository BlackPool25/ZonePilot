-- V41__duty_log.sql
-- ELD-hybrid timestamped duty log: one row per duty transition.

CREATE TABLE IF NOT EXISTS duty_log (
    id BIGSERIAL PRIMARY KEY,
    tour_id BIGINT NOT NULL REFERENCES tour(id),
    driver_id BIGINT NOT NULL REFERENCES driver(id),
    duty_status VARCHAR(20) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_duty_log_tour_recorded
    ON duty_log(tour_id, recorded_at);
