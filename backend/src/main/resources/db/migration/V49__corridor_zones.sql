-- V49__corridor_zones.sql
-- Corridor rule: geometry_type {POLYGON,CIRCLE,CORRIDOR} + corridor_width_m
-- (half-width tolerance in meters; doubles as circle radius) + corridor route ref.
ALTER TABLE zone_restriction ADD COLUMN IF NOT EXISTS geometry_type VARCHAR(20) NOT NULL DEFAULT 'POLYGON';
ALTER TABLE zone_restriction ADD COLUMN IF NOT EXISTS corridor_width_m DOUBLE PRECISION;
ALTER TABLE zone_restriction ADD COLUMN IF NOT EXISTS corridor_route_id BIGINT;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_zone_geometry_type') THEN
        ALTER TABLE zone_restriction ADD CONSTRAINT chk_zone_geometry_type
            CHECK (geometry_type IN ('POLYGON', 'CIRCLE', 'CORRIDOR'));
    END IF;
END $$;
