# Ops runbook snippet: quarantine spikes + OSM refresh + partition rollover

## Quarantine-spike alert

`TraccarIngestService.quarantineCount()` (total) and
`quarantineCount(reason)` (`TELEPORT` / `BAD_TS` / `BAD_SOURCE` / `INGEST_ERROR` / ...)
are in-process counters; scrape via logs (`ingest.quarantine vehicle=.. reason=..`)
or expose on the ops dashboard.

- Alert when quarantines/min jumps > 5x the 7-day baseline: cause is almost
  always a feed regression (device clock skew → `BAD_TS`, firmware field rename
  → `MISSING_COORDS`) or a map regression (stale road geometry → `TELEPORT`).
- Triage: group the spike by reason first (`quarantineCount(reason)`), then by
  vehicle. A single-vehicle spike is a device fault; a fleet-wide spike on one
  reason is a feed/map change — check the OSM import date (below) before
  touching ingest code.

## OSM road-network refresh (Bangalore bbox)

Road geometry feeds map-matching + the breach worker's reroute. Refresh quarterly
(or after a quarantine spike points at stale roads):

```bash
./import-osm-local.sh ~/osm/southern-zone-latest.osm.pbf   # clip + load
./import-osm-local.sh                                      # default source path
SKIP_LOAD=1 ./import-osm-local.sh dummy                    # topology rebuild only
```

Bbox is `77.45,12.83,77.78,13.14` (Bangalore metro, matches `plan.md`).
After load, confirm the worker tables exist (`blr_2po_4pgr` with
`id/source/target/cost_time_sec/reverse_cost/the_geom`) — a missing table
fails the worker closed per fix, it never poisons ingest.

## Position-partition rollover (yearly)

`vehicle_position_log` is range-partitioned by month. Migrations ship about a
year ahead (V21 thru 2027, V48 thru 2028). Each Q4, add one migration creating
the next year's 12 monthly partitions (copy V48, shift the year), and open an
ops ticket to DETACH/DROP partitions older than the 13-month trip-retention
window (no pg_cron on the Fly Machine — run from the deploy pipeline).
Inserts landing in the `DEFAULT` partition mean the rollover was missed.
