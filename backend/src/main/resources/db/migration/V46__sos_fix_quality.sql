-- SOS fix quality: offline queue replays carry the last-known fix plus
-- accuracy + stale flag so the dispatcher UI can warn on unknown/stale.
ALTER TABLE sos_alert ADD COLUMN accuracy_m DOUBLE PRECISION;
ALTER TABLE sos_alert ADD COLUMN stale BOOLEAN NOT NULL DEFAULT FALSE;
