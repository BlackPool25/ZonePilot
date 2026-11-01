-- V39__vehicle_availability.sql
-- Stored availability: auto-mapped by assign/start/done, manually settable to MAINTENANCE.

ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS availability VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE';
