-- V42__breach_worker.sql
-- Task 5: the background BreachWorker owns zone_breach_log one-per-entry
-- (60s extend window) in its own txn, AFTER_COMMIT. The synchronous trigger
-- created rows inside the ingest txn while reroute ran pgRouting there too —
-- a failed road query aborted the whole Postgres txn (in-zone ingest 500
-- without road tables). Drop both so rows are never double-created.

DROP TRIGGER IF EXISTS trg_detect_zone_breach ON vehicle_position_log;
DROP FUNCTION IF EXISTS fn_detect_zone_breach();
