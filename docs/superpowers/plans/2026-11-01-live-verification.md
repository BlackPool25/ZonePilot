# ZonePilot Live Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace seeds with live Traccar/MQTT tours, fix reroute/deploy/wiring blockers first.

**Architecture:** Bugs-first to green main, then live vertical: strict-FK positions, background breach worker, per-group JWT WS, PWA tour+duty, manager timeline approve, REPLAY batch speed.

**Tech Stack:** Spring Boot 3.5, PostGIS 16-3.6/pgRouting osm2pgrouting blr_, Redis 7, React 19 + Leaflet, STOMP /topic/group/{id}, PWA GPS.

**Spec:** docs/superpowers/specs/2026-11-01-live-verification-design.md

## Global Constraints
- SRID 4326 everywhere, IST windows with overnight support, ST_Covers boundary.
- Partitioned vehicle_position_log, Flyway V38+ only, no edits to V1-V37.
- Redis required for ingest, JWT on WS handshake + per-group subscribe.
- Tap PoD within 100m + order; manager approve closes tour.
- REPLAY rows re-trigger breach, ordered by recorded_at.

## Review Focus
- Burst upload with same ms timestamp from two devices — expect one kept, one ignored, no 500.
- 30x replay flood — expect manager-only cap + batch insert, DB stays up.
- Driver swaps mid-tour — expect new keys accepted same tour, old rows intact.
- Offline 10min gap then burst — expect ordered insert + single extended breach.
- HTML error page from backend — expect parsed error toast, never raw SyntaxError.

---

### Task 1: Bugs-first green main

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/BreachService.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/service/PositionTrackingService.java`
- Modify: `render.yaml`, `backend/src/main/resources/application-prod.yml`, `fly/import-osm.sh`
- Modify: `frontend/src/api/client.js`, `frontend/src/pages/Driver.jsx`, `frontend/src/components/drawers/RouteDrawer.jsx`

**Interfaces:**
- Consumes: existing trigger fn_detect_zone_breach, sp_validate_route
- Produces: `BreachService.computeReroute(vehicleId, tourId)` joined-txn; `POST /groups/dispatch-solo` client fix

- [ ] **Step 1: Write failing test for reroute join-txn**

```java
void computeReroute_persistsResolvedGeometry_inSameTxn() {
  // insert position in breach zone, call computeReroute, assert resolved_route_geometry != null
}
```

- [ ] **Step 2: Run to verify fail**

Run: `cd backend && mvn -Dtest=BreachServiceTest test`
Expected: FAIL — timeout or null geometry

- [ ] **Step 3: Implement join-txn fix in `BreachService.java` — remove REQUIRES_NEW, join outer txn**

- [ ] **Step 4: Fix tick guards — per-vehicle try/catch, tolerate duplicate key, validate bbox per-fix not per-tick**

- [ ] **Step 5: Fix deploy — add Redis to render.yaml, default CORS, backfill cost_time_sec in fly/import-osm.sh, fix client dispatch-solo path, Driver toast arg order, RouteDrawer variant, res.ok check**

- [ ] **Step 6: Verify**

Run: `cd backend && mvn test -Dtest=BreachServiceTest,PositionTrackingServiceTest`
Expected: PASS
Run: `cd frontend && npm run build`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend frontend render.yaml
git commit -m "fix: join-txn reroute, deploy, wiring blockers"
```

### Task 2: Strict position keys

**Files:**
- Create: `backend/src/main/resources/db/migration/V38__position_keys.sql`
- Modify: `backend/src/main/java/com/zonepilot/backend/entity/VehiclePositionLog.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/repository/VehiclePositionLogRepository.java`

**Interfaces:**
- Consumes: Tour, Driver, FleetGroup ids
- Produces: `recordPosition(vehicleId, driverId, tourId, groupId, lat, lng, recordedAt, source)`

- [ ] **Step 1: Write failing migration test — insert without tourId rejects, old rows migrate to dummy tour**

- [ ] **Step 2: Implement V38 — add nullable cols, backfill dummy tour, set NOT NULL + FK, drop old UNIQUE, add UNIQUE(vehicle_id,tour_id,recorded_at)**
- [ ] **Step 2b: Burst + swap tests — same ms duplicate ignored not 500; mid-tour driver swap accepted same tour

- [ ] **Step 3: Update entity + repository + service signature**

- [ ] **Step 4: Verify**

Run: `cd backend && mvn test -Dtest=PositionKeysTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V38__position_keys.sql backend/src/main/java
git commit -m "feat: strict position keys driver+tour+group"
```

### Task 3: Live pipe + scoped WS

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/TraccarIngestService.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/config/WsConfig.java`
- Modify: `frontend/src/api/live.js`, `frontend/src/context/AppContext.jsx`

**Interfaces:**
- Consumes: `recordPosition(...)` from Task 2
- Produces: `POST /api/ingest/traccar`, `WS /topic/group/{id}` JWT-scoped

- [ ] **Step 1: Write failing test — anon WS subscribe rejected, group JWT accepted**

- [ ] **Step 2: Implement MQTT/Traccar ingest with source LIVE/REPLAY, Redis IngestState, per-group broadcast**

- [ ] **Step 3: Frontend JWT WS + per-group subscribe, remove absolute fallback, add offline banner**

- [ ] **Step 4: Verify**

Run: `cd backend && mvn test -Dtest=IngestWsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend frontend
git commit -m "feat: live traccar pipe scoped ws"
```

### Task 4: Tour assign + PWA + availability

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/GroupDispatchService.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/entity/Vehicle.java`
- Modify: `frontend/src/pages/Driver.jsx`, `frontend/src/pages/Groups.jsx`

**Interfaces:**
- Consumes: FleetGroup, Tour, TourStop
- Produces: `POST /groups/{id}/dispatch`, `POST /driver/tours/{id}/duty`, availability enum

- [ ] **Step 1: Write failing test — assign flips RESERVED, start DRIVING flips IN_TRANSIT, done flips AVAILABLE**

- [ ] **Step 2: Implement stored availability + auto map + manual MAINTENANCE**

- [ ] **Step 3: Implement PWA tour list + stop tap + duty buttons + SOS + offline queue burst**

- [ ] **Step 4: Verify**

Run: `cd backend && mvn test -Dtest=DispatchAvailabilityTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend frontend
git commit -m "feat: tour assign pwa duty availability"
```

### Task 5: Background breach worker

**Files:**
- Create: `backend/src/main/java/com/zonepilot/backend/service/BreachWorker.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/service/BreachService.java`

**Interfaces:**
- Consumes: position insert events
- Produces: `zone_breach_log` one-per-entry extended, `rerouteGeoJson` best-effort

- [ ] **Step 1: Write failing test — 10min inside zone yields 1 row extended, re-entry yields 2nd row**

- [ ] **Step 2: Implement worker — ST_Covers OR 50m off-route, 60s+ extend window, zone-avoiding pgRouting**

- [ ] **Step 3: Verify**

Run: `cd backend && mvn test -Dtest=BreachWorkerTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend
git commit -m "feat: background breach worker one-per-entry"
```

### Task 6: PoD + manager timeline

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/TourService.java`
- Modify: `frontend/src/pages/Routes.jsx`, `frontend/src/components/drawers/RouteDrawer.jsx`

**Interfaces:**
- Consumes: TourStop, breach list, PoD stamps
- Produces: `POST /tours/{id}/stops/{seq}/complete`, `POST /tours/{id}/approve`

- [ ] **Step 1: Write failing test — tap outside 100m rejects, correct order auto-completes, approve closes tour + AVAILABLE**

- [ ] **Step 2: Implement PoD + timeline API + approve UI**

- [ ] **Step 3: Verify**

Run: `cd backend && mvn test -Dtest=PodApprovalTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend frontend
git commit -m "feat: pod tap timeline approve"
```

### Task 7: Replay speed + delete sim

**Files:**
- Create: `backend/src/main/java/com/zonepilot/backend/service/ReplayService.java`
- Delete: `backend/src/main/java/com/zonepilot/backend/seed/SimulationDataSeeder.java`, `SimulationController tick/state/reset`, `SimulationPath` usage
- Modify: `frontend/src/pages/Simulation.jsx` -> `Replay.jsx`

**Interfaces:**
- Consumes: vehicle_position_log history
- Produces: `POST /replay/start {tourId, speed:1-30}`, batched REPLAY inserts

- [ ] **Step 1: Write failing test — 30x replay re-inserts as REPLAY ordered, non-manager rejected**

- [ ] **Step 2: Implement ReplayService batch insert + cap, delete seeder + old endpoints**

- [ ] **Step 3: Verify**

Run: `cd backend && mvn test -Dtest=ReplayServiceTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend frontend
git commit -m "feat: replay speed, delete seeded sim"
```
