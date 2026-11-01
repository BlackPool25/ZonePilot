# ZonePilot Live Verification — Design (2026-11-01)

## Intent
Replace seeded SCENARIO_A/B/C simulation with real live fleet verification.
Manager allots group tours to vehicle+driver. Driver PWA streams GPS via
Traccar/MQTT pipe. Backend detects breaches atomically, computes reroutes,
tracks ELD duty, availability, PoD. Manager verifies via timeline + approve.
Add speed-up as batched replay of real queued fixes (1x-30x, managers only).

## Why
Seeded paths prove nothing about real cars. Current tick shows
breachDetected:true with rerouteGeoJson:null always, map-matching dead,
reroute deadlocked. Demo cannot verify a real shipment.

## Goals
- Live positions from Traccar/MQTT + Driver PWA GPS, source=LIVE/REPLAY.
- Group tour assign: ordered stops, manifest qty/weight, vehicle+driver.
- Positions keyed vehicle+driver+tour+group (strict FK, tour-scoped unique).
- Per-group WS topics with JWT, frontend live map + timeline approve.
- Manual ELD duty (OFF/ON/DRIVING/BREAK), stored availability enum auto-flipped.
- Per-stop tap PoD with GPS stamp; manager auto + human verification.
- Speed slider 1-30x = batch replay of queued fixes, capped + batched inserts.
- Offline PWA queue + burst upload ordered by timestamp.
- Bugs-first: fix reroute deadlock, deploy blockers, 404s, tick aborts.

## Non-goals
- Photo/signature PoD (tap only this slice).
- Auto duty from speed (manual only).
- Native apps (PWA only).
- Sidecar simulator (deleted).

## Anti-goals
- Fake data presented as live (demo fallback with no banner).
- Re-introducing seeds or ways-table docs.

## Constraints
- PostGIS 16-3.6 + pgRouting, osm2pgrouting blr_ prefix, cost_time_sec backfill.
- Partitioned vehicle_position_log, Flyway V1-V37, strict FK needs dummy-tour backfill.
- Redis required for ingest; CORS var required; HSTORE extension order.
- IST time windows, overnight support, ST_Covers boundary.

## Approaches considered
A. Bugs-first then live vertical (picked). Fix deadlock (join txn), Redis/CORS/cost_time_sec, solo-dispatch 404, per-vehicle tick guards. Then live slice above. Safest, slower start.
B. Live-only skip fixes. Faster map but carries deadlock + 500s into first trip. Rejected.
C. Keep tick as replay harness. Keeps speed simple but keeps dead code, contradicts delete-all. Rejected.

## Decided (user)
- Source: Traccar/MQTT live. Speed: slider 1-30x batched replay. Seeds: delete all.
- Flow: group tour assign. Verification: auto + human. Driver: PWA.
- Keys: strict FK full keys. Dedupe: tour-scoped unique. Ingest guard: cap+batch.
- PWA: tour+duty+SOS. Manager UI: timeline+approve. WS: JWT+scoped.
- Reroute: join outer txn. Breach: one per entry. Duty: manual only.
- Availability: stored enum + auto flip. Order: bugs first.

## Left to builder
- Exact MQTT topic strings, WS JWT handshake code, PWA background-GPS lib.
- Batch size / interval numbers for 30x cap.
- Dummy tour migration SQL for old position rows.
- Timeline UI layout details.

## Builder check answers (2026-11-01)
- Breach/reroute: background worker (ingest fast, worker detects async).
- Fleet: provision all (Traccar + broker + test drive in plan).
- Breach: deviation 50m off-route OR zone entry (ST_Covers); reroute best-effort, null only if no road.
- Duty->availability: Assign=RESERVED, start DRIVING=IN_TRANSIT, done=AVAILABLE, maint manual.
- PoD: tap within 100m + order => auto complete stop, manager approve closes tour.
- Replay: re-insert real trip rows as REPLAY ordered by recorded_at, re-triggers breach.
- Swap: allow mid-tour swap, new positions carry new driver/vehicle same tour.
