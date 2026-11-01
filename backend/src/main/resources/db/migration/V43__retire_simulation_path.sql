-- Wave C: retire the Slice-2 demo table. Replay is served from stored fixes
-- via ReplayService (Slice 3+); nothing reads simulation_path anymore.
-- IF EXISTS keeps this a no-op on DBs where V1 never created it.
DROP INDEX IF EXISTS idx_gist_simulation_path_waypoints;
DROP TABLE IF EXISTS simulation_path;
