-- V13__add_cost_time_sec.sql
-- Epic 1: Pre-calculated time-based routing cost profile.
--
-- Adds cost_time_sec to blr_2po_4pgr (osm2po road network table).
-- This migration is a no-op if blr_2po_4pgr does not yet exist (road network
-- not loaded). The DO block guards against that case so Flyway does not fail
-- on a fresh container that has not had the OSM data imported yet.

DO $$
BEGIN
    -- Guard: only run if the road network table has been imported.
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'blr_2po_4pgr'
    ) THEN
        RAISE NOTICE 'blr_2po_4pgr not found — skipping cost_time_sec population. Re-run after OSM import.';
        RETURN;
    END IF;

    -- Add column if not already present (idempotent).
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'blr_2po_4pgr' AND column_name = 'cost_time_sec'
    ) THEN
        ALTER TABLE blr_2po_4pgr ADD COLUMN cost_time_sec DOUBLE PRECISION;
    END IF;

    -- Populate cost_time_sec.
    --
    -- Formula: length_m / (speed_kmh / 3.6)  →  seconds
    --
    -- Speed resolution order:
    --   1. osm2po kmh column (derived from OSM maxspeed tags by osm2po)
    --   2. clazz-based fallback when kmh is 0 or NULL
    --
    -- osm2po clazz values (highway type):
    --   11 = motorway        → 90 km/h
    --   12 = motorway_link   → 60 km/h
    --   13 = trunk           → 80 km/h
    --   14 = trunk_link      → 50 km/h
    --   21 = primary         → 60 km/h
    --   22 = primary_link    → 40 km/h
    --   31 = secondary       → 50 km/h
    --   32 = secondary_link  → 30 km/h
    --   41 = tertiary        → 40 km/h
    --   51 = unclassified    → 30 km/h
    --   61 = residential     → 30 km/h
    --   62 = living_street   → 10 km/h
    --   63 = service         → 20 km/h
    --   default              → 30 km/h
    UPDATE blr_2po_4pgr
    SET cost_time_sec = length_m / (
        CASE
            WHEN kmh IS NOT NULL AND kmh > 0 THEN kmh
            ELSE CASE clazz
                WHEN 11 THEN 90.0
                WHEN 12 THEN 60.0
                WHEN 13 THEN 80.0
                WHEN 14 THEN 50.0
                WHEN 21 THEN 60.0
                WHEN 22 THEN 40.0
                WHEN 31 THEN 50.0
                WHEN 32 THEN 30.0
                WHEN 41 THEN 40.0
                WHEN 51 THEN 30.0
                WHEN 61 THEN 30.0
                WHEN 62 THEN 10.0
                WHEN 63 THEN 20.0
                ELSE        30.0
            END
        END / 3.6
    );

    -- Index for query planner — pgr_dijkstra scans this column on every call.
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'blr_2po_4pgr' AND indexname = 'idx_blr_2po_4pgr_cost_time_sec'
    ) THEN
        CREATE INDEX idx_blr_2po_4pgr_cost_time_sec ON blr_2po_4pgr (cost_time_sec);
    END IF;

END $$;
