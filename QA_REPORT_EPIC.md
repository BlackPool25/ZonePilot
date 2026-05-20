# ZonePilot — Comprehensive QA Report (Epics 1-4 + Full System)

**Date:** 2026-05-20
**System:** ZonePilot Backend (Spring Boot 3.5.14, PostgreSQL 16 + PostGIS 3.4 + pgRouting 3.x)
**Testing Method:** Black-box API testing + targeted source code inspection + database verification
**Test Environment:** Docker Compose (Linux), dev profile with simulation seeder
**Maven Build:** SUCCESS (74/74 unit tests pass)
**Flyway:** V15 current, all migrations applied

---

## 1. Executive Summary

### Overall Assessment

ZonePilot has been significantly upgraded with 4 major epics: time-based routing (Epic 1), map-snapped simulation with GPS glitch/wrong-turn injection (Epic 2), stateful journey tracking with cross-track tolerance (Epic 3), and predictive compliance with Google Routes API integration and wait-state optimization (Epic 4). The architecture is sound, the layered design is clean, and the database schema correctly supports all new features.

However, **several critical and high-severity bugs remain** that would cause incorrect behavior in production, particularly around timezone handling, time window logic consistency, and API contract violations.

### Production Readiness Estimate: **Not Production-Ready**

The system demonstrates strong engineering fundamentals but has correctness bugs that would cause silent failures in real-world deployment.

### What Was Fixed Since Previous QA

The following bugs from the previous QA report have been **confirmed fixed**:
- BUG-001: Missing exception handlers → All 5+ exception types now handled correctly (400, 404, 405, 415)
- BUG-002: Zone/Depot creation using `@RequestParam` → Both now use `@RequestBody` with DTOs
- BUG-003: Route validation returning 500 for missing road network → Returns 422 ROUTING_ERROR
- BUG-004: No validation on negative speed → `@DecimalMin("0")` added, returns 400
- BUG-005: No validation on future timestamps → 5-minute future check added, returns 400
- BUG-006: Zone violations endpoint ignoring zoneId → Now correctly filters by zoneId
- BUG-007: Day-of-week bitmask off-by-one → Fixed in V11 (trigger) and V12 (stored procedure)
- BUG-008: ST_Within vs ST_Covers → Fixed in V11 trigger
- BUG-009: sp_validate_route LIMIT 1 → Removed in V12
- BUG-010: Simulation breach detection inconsistency → Improved with fallback query

---

## 2. What Was Tested

### Epic 1: Time-Based Routing Profile
- [x] V13 migration SQL syntax and guard logic
- [x] Flyway migration applied successfully
- [x] `RoadNetworkRepository.computeDijkstraRoute()` uses `cost_time_sec`
- [x] `reverse_cost = -1` preserved for one-way streets
- [x] Error handling when road network is missing (returns 422 ROUTING_ERROR)
- [ ] Runtime cost_time_sec values (road network not loaded in test environment)
- [ ] kmh fallback speed calculation (road network not loaded)

### Epic 2: Map-Snapped Simulation
- [x] Simulation start/reset/tick lifecycle
- [x] GPS glitch injection (10% probability, ~50m offset + flipped heading)
- [x] Wrong-turn injection (5% probability, 3 consecutive ticks)
- [x] Heading computation from waypoints
- [x] Breach detection during simulation ticks
- [x] Path completion handling
- [x] Scenario name normalization (A/B/C variants)
- [ ] pgRouting-backed path generation (road network not loaded during seeding)
- [ ] ST_LineInterpolatePoints at 30m intervals (query verified correct, but paths use fallback)

### Epic 3: Stateful Journey + GPS Glitch Tolerance
- [x] `active_dispatch_route_id` column on `vehicle` table
- [x] Partial index on `active_dispatch_route_id`
- [x] Foreign key constraint (DEFERRABLE INITIALLY DEFERRED)
- [x] `applyRouteMapMatching()` logic in `PositionTrackingService`
- [x] 30m glitch snap threshold
- [x] 50m off-route threshold with 2-consecutive-ping persistence
- [x] Reversed heading detection (GPS glitch on opposite carriageway)
- [x] `computeOffRouteReroute()` in `BreachService`
- [ ] Runtime map-matching (no active routes exist without road network)

### Epic 4: Predictive Compliance + Wait-State Optimization
- [x] `wait_until` and `wait_duration_sec` columns on `dispatch_route`
- [x] 5-attempt recursive routing in `RouteComplianceService`
- [x] Zone penalty escalation (1000x cost multiplier)
- [x] Wait-state computation with IST timezone
- [x] `TimePredictionService` Google Routes API integration structure
- [x] Waypoint pruning (max 25 intermediates)
- [x] `via:true` for intermediate waypoints
- [x] Duration string parsing ("123s" format)
- [x] Graceful fallback when API key not configured
- [ ] Runtime route validation with curfew detection (road network not loaded)
- [ ] Google Routes API actual call (no API key configured)

### Existing Features (Regression Testing)
- [x] Vehicle CRUD (all edge cases)
- [x] Depot CRUD (all edge cases)
- [x] Zone CRUD (all edge cases)
- [x] Position tracking (all edge cases)
- [x] Breach detection via trigger
- [x] Breach acknowledge (idempotent)
- [x] Fleet summary report
- [x] Zone violation stats (filtered by zoneId)
- [x] Active restrictions
- [x] Position history with time filters
- [x] Latest position endpoint
- [x] Route history (returns empty array correctly)

---

## 3. What Passed

| Feature | Status | Notes |
|---|---|---|
| Maven build | PASS | 74/74 unit tests |
| Docker Compose startup | PASS | Both containers healthy |
| Flyway migrations | PASS | V1-V15 all applied |
| Vehicle CRUD | PASS | All edge cases handled correctly |
| Depot CRUD | PASS | Now uses @RequestBody with validation |
| Zone CRUD | PASS | Now uses @RequestBody with GeoJSON validation |
| Position tracking | PASS | Speed/timestamp validation working |
| Breach detection (trigger) | PASS | Fires correctly for zone violations |
| Breach acknowledge | PASS | Idempotent, correct 404 for missing |
| Simulation engine | PASS | Tick progression, completion, breach detection |
| GPS glitch injection | PASS | Heading flips and coordinate offsets observed |
| Wrong-turn injection | PASS | 3-consecutive-tick drift observed in position log |
| Error handling | PASS | 400, 404, 405, 415 all return correct codes |
| Zone violations by ID | PASS | Now correctly filters by zoneId |
| Fleet summary | PASS | Correct breach counts per vehicle |
| Active restrictions | PASS | Returns currently active zones |
| Inactive vehicle rejection | PASS | Returns 400 with correct message |
| Coordinate validation | PASS | Out-of-range coords return 400 |

---

## 4. What Failed

| Feature | Status | Notes |
|---|---|---|
| Route validation | BLOCKED | Road network not loaded; returns 422 (correct behavior) |
| Map-matching (Epic 3) | UNTESTED | Requires active dispatch routes (needs road network) |
| Predictive compliance (Epic 4) | UNTESTED | Requires road network + Google API key |
| Simulation pgRouting paths | FAIL | Paths use fallback straight-line, not map-snapped |
| Route detail 404 | FAIL | Returns bare 404 without ApiResponse envelope |

---

## 5. Bugs Found

### BUG-NEW-001: Timezone Mismatch Between Restriction Times and Database Time
**Severity: CRITICAL**

**Description:** The database timezone is `Etc/UTC`, but zone restriction times are defined in IST (Asia/Kolkata). The trigger compares `NEW.recorded_at::TIME` (UTC time) against `restriction_start_time`/`restriction_end_time` (IST values like 07:00, 21:00). This means restrictions fire at completely wrong times.

**Evidence:**
- DB timezone: `Etc/UTC`
- Current UTC time: ~16:46 (= 22:16 IST)
- MG Road restriction: 21:00-23:00 IST
- At 22:16 IST, vehicle 7 should be breaching, but the trigger compares `16:46 BETWEEN 21:00 AND 23:00` which is FALSE

**Reproduction:**
1. Start simulation with Scenario B (vehicle 7, HCV, through MG Road zone)
2. Vehicle 7 enters MG Road zone at ~22:16 IST
3. No breach recorded because UTC time 16:46 is not between 21:00-23:00

**Expected:** Vehicle 7 should breach when entering MG Road zone during 21:00-23:00 IST window.
**Actual:** No breach recorded because UTC time doesn't match IST restriction window.

**Affected Components:**
- `V11__fix_trigger_bitmask_and_boundary.sql` (trigger `fn_detect_zone_breach`)
- `V12__fix_stored_procedure_bitmask_and_limit.sql` (stored procedure `sp_validate_route`)

**Recommended Fix:** Convert `NEW.recorded_at` to IST before extracting time:
```sql
v_current_time := (NEW.recorded_at AT TIME ZONE 'Asia/Kolkata')::TIME;
```
And for the stored procedure:
```sql
v_current_time := (NOW() AT TIME ZONE 'Asia/Kolkata')::TIME;
```

---

### BUG-NEW-002: Stored Procedure Time Window Logic Inconsistent with Trigger
**Severity: HIGH**

**Description:** The stored procedure `sp_validate_route` (V12) and the trigger `fn_detect_zone_breach` (V11) have different time window logic for handling NULL restriction times.

**Trigger (V11):**
```sql
AND (
     (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
     OR (v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
)
```

**Stored Procedure (V12):**
```sql
AND (
    zrr.restriction_start_time IS NULL
    OR v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time
)
```

**Impact:** For a NO_ENTRY zone (zone 3) with both `restriction_start_time` and `restriction_end_time` as NULL, both work correctly. But if a rule has `start_time IS NULL` and `end_time IS NOT NULL`, the stored procedure would match (always restricted) while the trigger would not match (because the AND condition fails). This creates inconsistency between pre-dispatch validation and live breach detection.

**Evidence:** Zone 3 (Majestic Bus Terminal Perimeter) has `restriction_start_time = NULL` and `restriction_end_time = NULL` — works for both. But any future rule with asymmetric NULLs would behave differently.

**Affected Components:** `V12__fix_stored_procedure_bitmask_and_limit.sql`

**Recommended Fix:** Make the stored procedure match the trigger:
```sql
AND (
    (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
    OR (v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
)
```

---

### BUG-NEW-003: Simulation Paths Use Fallback Straight-Line Instead of pgRouting
**Severity: HIGH**

**Description:** The simulation paths have exactly 17 waypoints each, matching the fallback coordinate count. The pgRouting interpolation at 30m intervals should produce significantly more points (e.g., Scenario B is ~10km which should produce ~333 points at 30m intervals). This means the `SimulationDataSeeder` fell back to straight-line coordinates because the road network was not loaded during the first container startup.

**Evidence:**
```
SCENARIO_A: 17 points, 5.9km  (fallback: 16 coords)
SCENARIO_B: 17 points, 10.3km (fallback: 16 coords)
SCENARIO_C: 17 points, 28.9km (fallback: 16 coords)
```

The `ST_LineInterpolatePoints` query was verified to work correctly (produces 186 points for a 1.2km route). The issue is that the seeder ran before the road network was imported, and the paths were cached.

**Impact:** GPS glitches and wrong turns are being applied to unrealistic straight-line paths that don't follow actual road geometry. The simulation does not exercise the real-world tracking pipeline as intended.

**Affected Components:** `SimulationDataSeeder.java`, `RoadNetworkRepository.interpolateWaypoints()`

**Recommended Fix:** 
1. Delete existing simulation paths and re-seed after road network is loaded, OR
2. Add a re-seeding endpoint that regenerates paths using pgRouting when the road network becomes available.

---

### BUG-NEW-004: Route Detail Endpoint Returns Bare 404 Without ApiResponse Envelope
**Severity: MEDIUM**

**Description:** `GET /api/routes/{id}` returns a bare HTTP 404 with no response body when the route doesn't exist. Every other endpoint returns the standardized `ApiResponse<T>` envelope. This breaks API consistency and makes error handling inconsistent for clients.

**Reproduction:**
```bash
curl -s http://localhost:8080/api/routes/999
# → HTTP 404, empty body (no JSON)
```

**Expected:** `{"success": false, "error": {"code": "RESOURCE_NOT_FOUND", "message": "Route not found with id: 999"}}`
**Actual:** Empty body, HTTP 404.

**Affected Components:** `RouteController.java:44-50`

**Recommended Fix:**
```java
@GetMapping("/{id}")
public ResponseEntity<ApiResponse<DispatchRoute>> getRoute(@PathVariable Long id) {
    return dispatchRouteRepository.findById(id)
            .map(route -> ResponseEntity.ok(ApiResponse.success(route)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("RESOURCE_NOT_FOUND", 
                            "Route not found with id: " + id)));
}
```

---

### BUG-NEW-005: Overnight Time Windows Not Supported
**Severity: HIGH**

**Description:** The time window check `v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time` fails for restrictions that span midnight (e.g., 22:00–06:00). At 03:00, the BETWEEN check returns FALSE because 03:00 is not between 22:00 and 06:00.

**Impact:** Any zone with overnight restrictions would never fire during the night hours. This is a silent correctness bug.

**Affected Components:**
- `V11__fix_trigger_bitmask_and_boundary.sql` (trigger)
- `V12__fix_stored_procedure_bitmask_and_limit.sql` (stored procedure)

**Recommended Fix:**
```sql
AND (
    (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)
    OR (zrr.restriction_start_time <= zrr.restriction_end_time 
        AND v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
    OR (zrr.restriction_start_time > zrr.restriction_end_time 
        AND (v_current_time >= zrr.restriction_start_time OR v_current_time <= zrr.restriction_end_time))
)
```

---

### BUG-NEW-006: String.format SQL Injection Risk in Zone-Avoiding Routes
**Severity: MEDIUM**

**Description:** `BreachService.computeZoneAvoidingRoute()` and `RouteComplianceService.computeRouteAvoidingZones()` use `String.format()` to insert zone IDs into SQL queries. While zone IDs are Long values (not user-controlled), this pattern is fragile and could become an injection vector if the code evolves.

**Location:** 
- `BreachService.java:148-155`
- `RouteComplianceService.java:273-283`

**Recommended Fix:** Use parameterized queries or a safe SQL builder pattern for the zone IDs in the subquery.

---

### BUG-NEW-007: TimePredictionService Uses Immutable Map.of() for Request Body
**Severity: LOW**

**Description:** `TimePredictionService` uses `Map.of()` to build the Google Routes API request body. `Map.of()` creates immutable maps that don't allow null values. If any field is null, this would throw `NullPointerException`.

**Location:** `TimePredictionService.java:71-78`, `TimePredictionService.java:121-128`

**Impact:** Currently not triggered because all fields are populated. But if `zoneEntryPoints` is empty and `buildIntermediates` returns an empty list, the `Map.of()` call would still work. The risk is low but present.

**Recommended Fix:** Use `new HashMap<>()` or `Map.ofEntries()` for safety.

---

### BUG-NEW-008: RouteComplianceService Sets active_dispatch_route_id Even on Failed Validation
**Severity: MEDIUM**

**Description:** `RouteComplianceService.validateRoute()` always sets `vehicle.activeDispatchRouteId` to the newly created dispatch route, even when the route is non-compliant. This means a vehicle could be dispatched on a route that violates zone restrictions, and the map-matching logic (Epic 3) would then try to match positions against a non-compliant route.

**Location:** `RouteComplianceService.java:109-111`

**Evidence:**
```java
vehicle.setActiveDispatchRouteId(saved.getId());
vehicleRepository.save(vehicle);
```
This runs unconditionally after saving the dispatch route, regardless of `response.getCompliant()`.

**Recommended Fix:** Only set `activeDispatchRouteId` when the route is compliant, or add a status check:
```java
if (response.getCompliant() || response.getWaitDurationSec() > 0) {
    vehicle.setActiveDispatchRouteId(saved.getId());
    vehicleRepository.save(vehicle);
}
```

---

### BUG-NEW-009: PositionTrackingService Off-Route Counter Not Reset When Route Changes
**Severity: LOW**

**Description:** The `offRoutePingCount` ConcurrentHashMap tracks consecutive off-route pings per vehicle ID. If a vehicle's active route changes (e.g., reroute computed), the counter is not reset, which could cause a false off-route trigger on the next ping.

**Location:** `PositionTrackingService.java:56`, `applyRouteMapMatching()`

**Recommended Fix:** Reset the counter when the active route ID changes, or include the route ID in the counter key.

---

### BUG-NEW-010: Simulation Path Seeder is Not Idempotent After Road Network Load
**Severity: LOW**

**Description:** `SimulationDataSeeder` checks `simulationPathRepository.count() > 0` and skips seeding if any paths exist. This means if the seeder ran before the road network was loaded (creating fallback paths), it will never regenerate pgRouting-backed paths even after the road network is available.

**Location:** `SimulationDataSeeder.java:69-72`

**Recommended Fix:** Check if existing paths are pgRouting-backed (e.g., by comparing waypoint count to fallback count) and regenerate if they're fallback paths.

---

## 6. Edge Cases That Remain Risky

### 6.1 Concurrent Position Inserts Under Load
The trigger performs a SELECT on the vehicle table and loops through all active zones for every position insert. Under high-frequency position reporting (e.g., 100 vehicles × 1Hz), this could become a bottleneck.

### 6.2 GeoJSON Coordinate Order Confusion
The system assumes GeoJSON uses [longitude, latitude] order. If a user provides [latitude, longitude], coordinates would be silently swapped, placing zones in wrong locations.

### 6.3 Partition Growth Without Management
The DEFAULT partition for `vehicle_position_log` will grow unbounded if new monthly partitions are not created. Queries scanning the DEFAULT partition will degrade over time.

### 6.4 Simulation Path with Deactivated Vehicle
If a vehicle is deactivated while its simulation path is active, the simulation will continue attempting to record positions. The `recordPosition` check would reject these, but the simulation doesn't handle this gracefully.

### 6.5 Google Routes API Timeout/Rate Limit
`TimePredictionService` catches exceptions and returns -1, but there's no retry logic, timeout configuration, or rate limit handling. A transient API failure would silently skip time prediction.

### 6.6 5-Attempt Routing Loop Performance
`RouteComplianceService.computeBestRoute()` can run up to 5 pgRouting queries with zone penalties. Each query scans the entire `blr_2po_4pgr` table. With a large road network, this could take significant time.

### 6.7 TimePredictionService Field Mask Parsing
The Google Routes API returns duration as a protobuf Duration string (e.g., "123s"). The parser handles this format, but if Google changes the format (e.g., to JSON object with seconds/nanos fields), parsing would silently return 0.

---

## 7. Fixes Needed (Prioritized)

| Priority | Bug | Severity | Effort | Description |
|---|---|---|---|---|
| 1 | BUG-NEW-001 | CRITICAL | Low | Fix timezone mismatch: convert recorded_at to IST before time comparison |
| 2 | BUG-NEW-005 | HIGH | Low | Fix overnight time window support in trigger and stored procedure |
| 3 | BUG-NEW-002 | HIGH | Low | Fix stored procedure time window logic to match trigger |
| 4 | BUG-NEW-003 | HIGH | Low | Re-seed simulation paths after road network is loaded |
| 5 | BUG-NEW-008 | MEDIUM | Low | Only set active_dispatch_route_id on compliant/wait-state routes |
| 6 | BUG-NEW-004 | MEDIUM | Low | Fix route detail 404 to return ApiResponse envelope |
| 7 | BUG-NEW-006 | MEDIUM | Medium | Replace String.format with parameterized SQL in zone-avoiding routes |
| 8 | BUG-NEW-010 | LOW | Low | Make simulation seeder idempotent after road network load |
| 9 | BUG-NEW-007 | LOW | Low | Replace Map.of() with mutable map in TimePredictionService |
| 10 | BUG-NEW-009 | LOW | Low | Reset off-route counter when active route changes |

---

## 8. Severity Ranking

### Critical (1)
- **BUG-NEW-001:** Timezone mismatch causes all time-based restrictions to fire at wrong times. This is a silent correctness bug that would go unnoticed until a real violation occurs.

### High (3)
- **BUG-NEW-005:** Overnight time windows never fire — silent failure for any zone with overnight restrictions.
- **BUG-NEW-002:** Inconsistent time window logic between pre-dispatch validation and live breach detection could cause different outcomes for the same scenario.
- **BUG-NEW-003:** Simulation uses straight-line paths instead of map-snapped pgRouting paths, defeating the purpose of Epic 2's realistic tracking pipeline.

### Medium (3)
- **BUG-NEW-008:** Non-compliant routes are set as active dispatch routes, potentially causing incorrect map-matching.
- **BUG-NEW-004:** API contract inconsistency — route detail 404 doesn't return ApiResponse envelope.
- **BUG-NEW-006:** String.format SQL pattern is fragile and could become an injection vector.

### Low (3)
- **BUG-NEW-010:** Simulation seeder not idempotent after road network load.
- **BUG-NEW-007:** Immutable Map.of() could throw NPE with null values.
- **BUG-NEW-009:** Off-route counter not reset on route change.

---

## 9. Recommended Next Steps

### Immediate (Before Any Production Use)
1. **Fix timezone mismatch (BUG-NEW-001):** This is the most critical bug. All time-based zone restrictions are evaluated against UTC time instead of IST, causing them to fire at completely wrong times.
2. **Fix overnight time windows (BUG-NEW-005):** Any zone with restrictions spanning midnight would never enforce during night hours.
3. **Fix stored procedure consistency (BUG-NEW-002):** Ensure pre-dispatch validation and live breach detection use identical time window logic.

### Short-Term
4. **Load road network and re-seed simulation paths (BUG-NEW-003):** The simulation is currently using straight-line fallback paths. Load the Bangalore OSM network and regenerate simulation paths to exercise the real tracking pipeline.
5. **Fix route detail 404 (BUG-NEW-004):** Return ApiResponse envelope for consistency.
6. **Fix active route assignment (BUG-NEW-008):** Only set active_dispatch_route_id on compliant or wait-state routes.

### Medium-Term
7. **Replace String.format with parameterized SQL (BUG-NEW-006):** Hardening against future injection risks.
8. **Add Google Routes API timeout/retry logic:** The current implementation has no timeout configuration.
9. **Add integration tests:** The project has 74 unit tests but zero integration tests. Add tests that exercise the full request→service→database→trigger chain.
10. **Add monthly partition management:** Create a scheduled job or cron to create new monthly partitions for `vehicle_position_log` before the DEFAULT partition absorbs records.

### Long-Term
11. **Add authentication/authorization:** All endpoints are currently unauthenticated.
12. **Add rate limiting:** Position recording endpoint can be flooded.
13. **Async reroute computation:** Move `BreachService.computeReroute()` out of the critical path.
14. **Performance testing:** Test trigger overhead with 50+ zones and high-frequency position inserts.

---

## 10. Architecture Quality Ratings (Updated)

| Category | Score (1-10) | Justification |
|---|---|---|
| **Architecture** | 8 | Clean layered design, Epic 3/4 additions well-integrated |
| **API Design** | 7 | Consistent @RequestBody usage, but route detail 404 is inconsistent |
| **Database Design** | 9 | Excellent normalization, partitioning, triggers, views, indexing |
| **Spatial Logic** | 7 | ST_Covers fix correct, but timezone bug undermines time-based spatial checks |
| **Epic 1 (Time-based routing)** | 8 | Migration correct, cost_time_sec formula sound, one-way preservation correct |
| **Epic 2 (Map-snapped simulation)** | 6 | Deviation injector works, but paths are fallback not pgRouting |
| **Epic 3 (Stateful journey)** | 8 | Cross-track logic well-designed, thresholds reasonable, glitch tolerance correct |
| **Epic 4 (Predictive compliance)** | 7 | 5-attempt recursion correct, wait-state logic sound, but untested at runtime |
| **Error Handling** | 8 | Vastly improved — all common exceptions handled with correct status codes |
| **Reliability** | 6 | Timezone bug and overnight window gap reduce reliability significantly |
| **Production Readiness** | 5 | Strong foundation, but timezone bug is a showstopper for production |

---

## 11. Final Verdict

### Does the project behave correctly overall?
**Mostly, with one critical exception.** The core functionality works correctly, and the 4 epics are well-implemented architecturally. However, the timezone mismatch (BUG-NEW-001) causes all time-based zone restrictions to evaluate at wrong times, which is a silent correctness bug that would cause real-world enforcement failures.

### Does it satisfy its intended goals?
**Yes, for demonstration purposes with caveats.** The system successfully demonstrates time-based routing, map-snapped simulation, stateful journey tracking, and predictive compliance. The timezone bug means time-based enforcement would not work correctly in a real deployment.

### Is it academically strong?
**Yes.** The BCNF normalization, partitioning strategy, trigger-based atomic breach detection, pgRouting integration, and the 4-epic architecture demonstrate strong database and backend engineering knowledge.

### Is it production-ready?
**No.** The timezone bug alone is a showstopper. Additionally, overnight time window support is missing, simulation paths use fallback data, and there are no integration tests.

### Most Dangerous Flaws
1. **Timezone mismatch (BUG-NEW-001):** All time-based restrictions fire at wrong times. Silent correctness bug.
2. **Overnight time window gap (BUG-NEW-005):** Any overnight restriction would never enforce during night hours.
3. **Simulation using fallback paths (BUG-NEW-003):** The simulation does not exercise the real-world tracking pipeline as designed.
4. **Inconsistent time window logic (BUG-NEW-002):** Pre-dispatch validation and live breach detection could produce different outcomes.
