-- Slice 2 risk fix: SOS without GPS must persist NULL, never POINT(0 0).
-- V24+ reserved for later slices (fuel/maintenance/billing); fix migrations start at V27.
ALTER TABLE sos_alert ALTER COLUMN location DROP NOT NULL;
