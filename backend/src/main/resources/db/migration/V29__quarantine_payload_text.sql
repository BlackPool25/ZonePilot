-- Slice 3 fix: quarantine is a dead-letter store and must accept arbitrary
-- bytes (including malformed JSON), so payload is TEXT, not JSONB.
ALTER TABLE position_quarantine ALTER COLUMN payload TYPE TEXT;
