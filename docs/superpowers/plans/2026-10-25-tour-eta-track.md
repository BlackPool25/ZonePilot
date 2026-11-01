# Tour ETAs + live track + endpoint verification

## Goal
Every tour stop carries a planned start time (ETA) derived from per-leg travel
times; advancing refreshes downstream ETAs from the validated leg actual.
A `GET /api/tours/{id}/track` endpoint joins per-leg route geometries with the
vehicle's latest position for live tracing. Tour endpoints are verified against
a real (synthetic) pgRouting network instead of stubs. Fix the known
SliceIT shared-container flake in `GroupMembershipTest`.

## Architecture
Same tour core. `TourOptimizerService.plan()` computes the time matrix ONCE and
returns ordered stops + per-leg seconds (fixes the double-matrix/two-Google-call
waste in `orderStops`+`totalTimeSec`, which delegate to it). `RouteComplianceService`
exposes the already-computed `totalTripSec` as `travelDurationSec` on the response
and persists it on `DispatchRoute` (null when unknown, e.g. no Google key —
`TourService` then falls back to the optimizer leg time). No own-network edge-cost
plumbing (deliberately out of scope).

## Tech Stack
Java 21, Spring Boot 3.5, PostGIS/pgRouting (test image), Flyway, JUnit5 + Testcontainers.

## Spec path
`docs/superpowers/specs/fleet-groups-vision.md` (tour slices) — ETAs + track are
the agreed "projections" and "route being tracked" pieces.

## Global Constraints
- Java 21, SRID 4326, append-only Flyway (V35 next), signed commits.
- `totalTripSec` semantics unchanged (Google traffic-aware total incl. wait; -1 → null).
- Synthetic IT network: grid around 77.58–77.64 / 12.930–12.935 only, committed
  as test fixture (not a migration — production loads real OSM).
- No SELECT * drift; keep `orderStops`/`totalTimeSec` signatures (tests exist).

## Review Focus
- ETAs strictly increasing along the tour; advance re-anchors downstream ETAs.
- Track returns legs in seq order with geometries + latest position (or NONE).
- Full suite green incl. real-Dijkstra tour tests; GroupMembershipTest standalone.

## Tasks

### Task 1 — optimizer plan() (single matrix)
- Files: Modify `TourOptimizerService.java`; Modify `TourOptimizerServiceTest.java`.
- Interfaces: `Plan(List<StopPoint> ordered, List<Long> legTimesSec) plan(oLat,oLng,stops,at)`; `orderStops`/`totalTimeSec` delegate.
- [ ] Step 1 (RED): test `plan_returnsLegTimes_matchingTotal` — collinear 3-stop,
  legTimes size == stops size, sum == totalTimeSec, each leg > 0.
  Run: `./mvnw -Dtest=TourOptimizerServiceTest test` Expected: FAIL (no plan).
- [ ] Step 2 (GREEN): implement `plan()` (matrix once; NN+2-opt on it; leg times
  from matrix rows along final order), delegate old methods.
  Run same. Expected: PASS (existing 4 + new 1).
- [ ] Step 3: `git commit -s`.

### Task 2 — travelDurationSec exposure + V35
- Files: Modify `RouteComplianceService.java`, `RouteValidationResponse.java`,
  `DispatchRoute.java`; Create `V35__tour_eta_track.sql`; Modify `RouteComplianceServiceTest.java`.
- Interfaces: `response.travelDurationSec` (Long, null when totalTripSec<=0);
  `dispatch_route.travel_duration_sec BIGINT NULL`;
  `tour_stop.eta_at TIMESTAMPTZ NULL, leg_duration_sec BIGINT NULL`.
- [ ] Step 1 (RED): unit test — stub TimePrediction to return 120s, assert
  `validateRoute(...).getTravelDurationSec()==120` and saved route carries it;
  stub -1 → assert null. Run: `./mvnw -Dtest=RouteComplianceServiceTest test`
  Expected: FAIL.
- [ ] Step 2 (GREEN): 3-line exposure + entity field + V35. Expected: PASS.
- [ ] Step 3: `git commit -s`.

### Task 3 — per-stop ETAs + refresh on advance
- Files: Modify `TourService.java`, `TourStop.java` (etaAt/legDurationSec),
  `TourResponse.java` (StopDto.etaAt/legDurationSec), `TourApiTest.java`.
- Interfaces: create anchors `eta_0 = departure + leg_0`, cumulative after;
  advance sets `next.etaAt = now + actualLeg` (actual = travelDurationSec>0 ?
  that : matrix single-leg haversine estimate) and re-anchors later stops by
  cumulative planned durations.
- [ ] Step 1 (RED): `etas_increaseAlongTour_and_advanceReanchors` — create 2-stop,
  assert eta[0] < eta[1], both > now; stub leg-1 actual 600s, deliver stop 0,
  assert stop1.etaAt ≈ now+600s (±60s). Expected: FAIL.
- [ ] Step 2 (GREEN): implement. Expected: PASS.
- [ ] Step 3: `git commit -s`.

### Task 4 — track endpoint (live tracing)
- Files: Modify `TourService.java` (`track(tourId)`), `TourController.java`
  (`GET /{tourId}/track`), Create `TourTrackResponse.java`
  (stops + legs[{routeId,fromSeq,toSeq,geometryWkt}] + latestPosition).
- Interfaces: `track()` loads tour (group-scoped read), legs from
  `routes.findByTourIdOrderByIdDesc` reversed to seq order with geometries,
  position via `PositionTrackingService.getLatestPosition(vehicleId)` (source
  NONE when no fix — no throw).
- [ ] Step 1 (RED): `track_returnsLegsAndPosition` — record a position, assert
  legs size 1 with geometry, position lat/lng match; fresh vehicle → source NONE.
  Expected: FAIL (404).
- [ ] Step 2 (GREEN): implement (inject PositionTrackingService into TourService —
  no cycle: it does not depend on TourService). Expected: PASS.
- [ ] Step 3: `git commit -s`.

### Task 5 — real-network tour IT + GroupMembershipTest fix
- Files: Modify `TourApiTest.java` (synthetic `blr_2po_4pgr` +
  `blr_2po_4pgr_vertices_pgr` grid fixture, drop compliance stub),
  `GroupMembershipTest.java` (standalone container).
- [ ] Step 1: fixture SQL (3x3 grid, bidirectional, cost_time_sec from length @50km/h);
  run `TourApiTest` — create/deliver/skip/track go through real Dijkstra.
  Expected: PASS un-stubbed (drop @MockitoBean).
- [ ] Step 2: GroupMembershipTest standalone (copy DriverApiTest container block).
  Full `./mvnw test`. Expected: 150+ PASS, zero SliceIT subclasses left except none.
- [ ] Step 3: `git commit -s`.

## Self-review
- Spec coverage: projections (ETAs) ✓, live tracing (track) ✓, stop-to-stop
  routing verified on real Dijkstra ✓, flake fix ✓.
- Proportion: ~150 lines prod + ~80 test + 1 migration; no new services.
