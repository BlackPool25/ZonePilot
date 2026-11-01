ALTER TABLE dispatch_route
    ADD COLUMN IF NOT EXISTS google_duration_sec BIGINT,
    ADD COLUMN IF NOT EXISTS google_static_duration_sec BIGINT,
    ADD COLUMN IF NOT EXISTS google_distance_meters BIGINT,
    ADD COLUMN IF NOT EXISTS google_delay_sec BIGINT,
    ADD COLUMN IF NOT EXISTS google_similarity_score DOUBLE PRECISION;
