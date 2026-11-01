# Tour Engine Design — multi-stop tours with manifest, auto-order, PoD

Date: 2026-10-25. Phase 1 of the fleet-groups vision (approach 1: core → driver → tower → sidecar).

## Intent and why

Groups can dispatch one origin→destination run per pair today. Real transport work is a
tour: one vehicle + driver, many drops, cargo per stop, proof at each door. This adds the
tour layer without touching the compliance core: each leg is still validated by the
iterative 3-attempt retry (huge-cost penalty, endpoint-in-zone → wait-state) and still
rides a `dispatch_route` row.

## Goals / non-goals / anti-goals

- Goals: tour + ordered stops + full manifest; fastest-time auto-order (traffic-aware,
  haversine fallback); tap+GPS PoD; skip-with-reason that never blocks; per-leg
  `dispatch_route` chain (current leg = the active route); manager + driver APIs.
- Non-goals (later phases): driver checklist UI, control-tower board, simulation
  sidecar auto-drive, photo/signature/OTP proof, COD reconciliation.
- Anti-goals: no change to `RouteComplianceService` semantics; no JSON blobs for stops;
  a failed stop must never freeze a tour.

## Constraints

- Java 21, Spring Boot 3.5, PostGIS, Flyway append-only (next: V34).
- Google Routes `computeRouteMatrix` only when `google.routes.api-key` set; else haversine.
- v1 cap: 12 stops per tour (matrix is N²; NN+2-opt is O(N²) anyway).
- NULL/empty manifest fields allowed except address point; COD ≥ 0.
- Only the assigned driver advances stops; only while tour ACTIVE.

## Approaches considered

1. **Tour + stops table, per-leg dispatch_route chain (picked).** `tour` row owns ordered
   `tour_stop` rows. Dispatch validates leg 1 (origin→stop1) into a `dispatch_route`
   with `tour_id` set; each PoD/skip validates the next leg and assigns a new route.
   Trades: N route rows per tour (fine — attempts table already versions retries), but
   compliance, breach triggers and position logging work unchanged.
2. Leg-per-route chaining without a tour row. Rejected: no place for manifest, sequence
   versioning, or tour-level status the tower needs.
3. JSON stops on dispatch_route. Rejected: unqueryable, breaks reporting and the tower.

## What was decided (user)

- Storage: tour + stops table. Manifest per stop: quantity, weight, recipient + phone,
  time window, COD, handling note (all six, all nullable except point).
- Order: auto-optimized for fastest time with traffic at dispatch.
- PoD: tap + GPS stamp. Skip: reason required, tour continues.
- Driver: checklist card (phase 2). Manager: control tower, all five panels (phase 3).
- Sidecar auto-drives scripted tours incl. occasional skip (phase 4).

## What's left to the builder

- Matrix parsing specifics (field mask `duration,distanceMeters`), NN + 2-opt implementation.
- Exact REST shapes (see plan). `tour_id` nullable FK on `dispatch_route`.
- Advance logic: PoD/skip on stop N validates leg N→N+1; last stop completes tour +
  completes final route. Skip re-optimization: keep original order (note in code why —
  re-ordering mid-tour invalidates ETAs the tower already showed).
- Concurrency: one advancing transaction per tour (pessimistic write lock on tour row).
