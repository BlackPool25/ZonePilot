-- V50__dwell_direction.sql
-- Dwell per zone + entry/exit split: dwell_threshold_min (NULL/0 = immediate)
-- + direction {ENTRY,EXIT,BOTH} as separate rule rows.
ALTER TABLE zone_restriction_rule ADD COLUMN IF NOT EXISTS dwell_threshold_min INTEGER;
ALTER TABLE zone_restriction_rule ADD COLUMN IF NOT EXISTS direction VARCHAR(10) NOT NULL DEFAULT 'BOTH';
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_zone_rule_direction') THEN
        ALTER TABLE zone_restriction_rule ADD CONSTRAINT chk_zone_rule_direction
            CHECK (direction IN ('ENTRY', 'EXIT', 'BOTH'));
    END IF;
END $$;
