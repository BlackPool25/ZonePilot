# ZonePilot — Technical QA Report

**Date:** 2026-05-20
**System:** ZonePilot Backend (Spring Boot 3.5.14, PostgreSQL 16 + PostGIS 3.4 + pgRouting 3.x)
**Testing Method:** Black-box API testing + targeted source code inspection
**Test Environment:** Docker Compose (Linux), dev profile with simulation seeder

---

## 1. Executive Summary

### Overall Assessment

ZonePilot is a well-architected academic/demo system that demonstrates strong database design (BCNF normalization, partitioning, triggers, stored procedures, spatial indexing) and clean layered Spring Boot architecture. The core vehicle CRUD, position tracking, simulation tick engine, and breach detection trigger all function correctly under normal conditions.

However, the system has **significant production-readiness gaps** across error handling, API design consistency, input validation, and several logic bugs that would cause silent failures or incorrect behavior in real-world deployment.

### Production Readiness Estimate: **Not Production-Ready**

The system is suitable for academic demonstration and proof-of-concept. It requires substantial hardening before production use.

### Architectural Strengths

- **Database normalization** to BCNF with clear zone/rule separation
- **Partitioned position log** table with proper DEFAULT partition
- **DB-level trigger** for atomic breach detection (correct architectural choice)
- **Stored procedure** for route validation reducing round-trips
- **GIST spatial indexes** on all geometry columns
- **Clean layered architecture** (Controller → Service → Repository → DB)
- **Standardized API response envelope** across all endpoints
- **Flyway migrations** for reproducible schema

### Critical Weaknesses

- **Missing exception handlers** cause 500 errors for common client mistakes (malformed JSON, wrong HTTP method, invalid paths)
- **API design inconsistency**: Zone and Depot creation use `@RequestParam` (query params) while all other endpoints use `@RequestBody` (JSON body)
- **No input validation** on speed (negative accepted) or timestamp (future dates accepted)
- **Reports endpoint ignores path parameter**, returning all zones regardless of requested zoneId
- **Day-of-week bitmask calculation bug** in trigger and stored procedure

---

## 2. Functional Test Results

### 2.1 Vehicle Management

| Test | Result | Notes |
|---|---|---|
| List all vehicles | PASS | Returns 9 seeded vehicles correctly |
| Get vehicle by ID | PASS | Correct response envelope |
| Get non-existent vehicle | PASS | 404 with RESOURCE_NOT_FOUND |
| Create valid vehicle | PASS | Returns created vehicle with assigned ID |
| Create duplicate registration | PASS | 400 with VALIDATION_ERROR |
| Create with invalid vehicle class | PASS | 400 with INVALID_VALUE |
| Create with non-existent depot | PASS | 404 with RESOURCE_NOT_FOUND |
| Create with empty registration | PASS | 400 with VALIDATION_ERROR |
| Create with missing fields | PASS | 400 with VALIDATION_ERROR |
| Update vehicle | PASS | Returns updated data |
| Filter by vehicleClass | PASS | Returns correct subset |
| Filter by isActive | PASS | Returns correct subset |
| Very long registration (200 chars) | PASS | 409 DATA_INTEGRITY_VIOLATION |
| Update non-existent vehicle | PASS | 404 RESOURCE_NOT_FOUND |

**Reliability: HIGH** — Vehicle CRUD is the most robust module.

### 2.2 Depot Management

| Test | Result | Notes |
|---|---|---|
| List all depots | PASS | 3 seeded depots returned |
| Get depot by ID | PASS | Correct response |
| Depot detail with location | PASS | lat/lng correctly extracted |
| Create depot (valid) | FAIL | Uses @RequestParam, not @RequestBody — API design mismatch |
| Create depot with invalid coords | FAIL | Returns 500 instead of 400 |
| Create depot with missing name | FAIL | Returns 500 instead of 400 |

**Reliability: MEDIUM** — Core reads work; writes have API design issues.

### 2.3 Zone Management

| Test | Result | Notes |
|---|---|---|
| List all zones | PASS | 5 zones with rules returned |
| Get zone by ID | PASS | Includes boundary as WKT |
| Get non-existent zone | PASS | 404 RESOURCE_NOT_FOUND |
| Active zones | PASS | Returns all 5 active zones |
| Create zone (valid GeoJSON) | FAIL | Controller uses @RequestParam, not @RequestBody |
| Create zone (invalid GeoJSON) | FAIL | Returns 500 instead of 400 |
| Create zone (self-intersecting) | FAIL | Returns 500 instead of 400 |
| Create zone (empty name) | FAIL | Returns 500 instead of 400 |
| Create zone (invalid restriction type) | FAIL | Returns 500 instead of 400 |

**Reliability: MEDIUM** — Reads are solid; create endpoint has fundamental API design flaw.

### 2.4 Position Tracking & Breach Detection

| Test | Result | Notes |
|---|---|---|
| Record position (active vehicle) | PASS | Position saved, no breach |
| Record position (HCV in MG Road zone) | PARTIAL | Position saved; breach detection depends on exact boundary/time |
| Record position (non-existent vehicle) | PASS | 404 RESOURCE_NOT_FOUND |
| Record position (out-of-range coords) | PASS | 400 VALIDATION_ERROR |
| Record position (negative speed) | FAIL | Accepted without validation |
| Record position (future timestamp) | FAIL | Accepted without validation |
| Record position (missing lat/lng) | PASS | 400 VALIDATION_ERROR |
| Record position (inactive vehicle) | PASS | 400 with correct message |
| Position history | PASS | Returns all positions |
| Latest position | PASS | Returns most recent |

**Reliability: MEDIUM** — Core tracking works; validation gaps on speed/timestamp.

### 2.5 Route Validation

| Test | Result | Notes |
|---|---|---|
| Validate route (road network missing) | FAIL | Returns 500 instead of 422/503 |
| Validate route (non-existent vehicle) | PASS | 404 RESOURCE_NOT_FOUND |
| Validate route (missing fields) | PASS | 400 VALIDATION_ERROR |
| Get route by ID | PASS | 404 when no routes exist |
| Route history | PASS | Returns empty array correctly |

**Reliability: LOW** — Requires road network; error handling for missing network is broken.

### 2.6 Simulation Engine

| Test | Result | Notes |
|---|---|---|
| Start simulation (A, B, C) | PASS | All 3 scenarios activated |
| Simulation state | PASS | Shows all paths at step 0 |
| Tick progression | PASS | Vehicles advance correctly |
| Breach detection during simulation | PARTIAL | Breaches recorded in DB but not always reflected in tick response |
| Tick with no active simulation | PASS | Returns empty array with tickNumber=0 |
| Start duplicate scenario | PASS | Idempotent (no error) |
| Start invalid scenario | PASS | 400 SIMULATION_ERROR |
| Start empty scenarios | PASS | 400 VALIDATION_ERROR |
| Reset simulation | PASS | Resets all paths |
| Path completion | PASS | Vehicles marked COMPLETED at end |

**Reliability: HIGH** — Simulation engine is the most thoroughly tested and functional module.

### 2.7 Reporting & Views

| Test | Result | Notes |
|---|---|---|
| Fleet breach summary | PASS | Correct counts per vehicle |
| Zone violation stats | PASS | Correct per-zone breakdown |
| Active restrictions | PASS | Returns currently active zones |
| Breach acknowledge | PASS | Correctly updates is_acknowledged |
| Acknowledge non-existent breach | PASS | 404 RESOURCE_NOT_FOUND |
| Double acknowledge | PASS | Idempotent (no error) |
| Zone violations by ID | FAIL | Ignores zoneId parameter, returns ALL zones |

**Reliability: MEDIUM** — Views work correctly; one endpoint has a critical bug.

---

## 3. Bugs & Defects

### BUG-001: Missing Exception Handlers Cause 500 for Common Client Errors
**Severity: HIGH**

**Description:** The `GlobalExceptionHandler` does not handle several standard Spring exceptions, causing them to fall through to the generic `Exception` handler which returns HTTP 500 with a generic "An unexpected error occurred" message.

**Affected Exceptions:**
- `HttpMessageNotReadableException` (malformed JSON) → should be 400
- `HttpMediaTypeNotSupportedException` (wrong Content-Type) → should be 415
- `HttpRequestMethodNotSupportedException` (wrong HTTP method) → should be 405
- `NoResourceFoundException` (invalid path) → should be 404
- `MissingServletRequestParameterException` (missing @RequestParam) → should be 400

**Reproduction:**
```bash
# Malformed JSON → 500 (should be 400)
curl -X POST http://localhost:8080/api/vehicles -H "Content-Type: application/json" -d '{invalid}'

# Wrong HTTP method → 500 (should be 405)
curl http://localhost:8080/api/simulation/tick

# Invalid path → 500 (should be 404)
curl http://localhost:8080/api/nonexistent
```

**Expected:** Appropriate 4xx status codes with descriptive error messages.
**Actual:** All return HTTP 500 with generic "INTERNAL_ERROR" message.
**Root Cause:** `GlobalExceptionHandler.java` lines 67-78 — missing `@ExceptionHandler` methods for Spring framework exceptions.
**Fix:** Add handlers for each exception type with appropriate HTTP status codes.

---

### BUG-002: Zone & Depot Creation Use @RequestParam Instead of @RequestBody
**Severity: HIGH**

**Description:** The `ZoneController.createZone()` and `DepotController.createDepot()` methods use `@RequestParam` for all inputs, expecting query string parameters. This is inconsistent with every other POST endpoint in the system which uses `@RequestBody` with JSON. The API spec (plan.md section 6) and OpenAPI documentation describe these as accepting JSON bodies.

**Reproduction:**
```bash
# This fails (JSON body sent):
curl -X POST http://localhost:8080/api/zones -H "Content-Type: application/json" \
  -d '{"name":"Test","boundary":"...","restrictionType":"NO_ENTRY"}'
# → 500 MissingServletRequestParameterException

# This works (query params):
curl -X POST "http://localhost:8080/api/zones?name=Test&boundary=...&restrictionType=NO_ENTRY"
# → 200 OK
```

**Expected:** POST endpoints should accept JSON request bodies consistent with the rest of the API.
**Actual:** Endpoints require query parameters, breaking API consistency and Swagger UI usability.
**Root Cause:** `ZoneController.java:44-52` and `DepotController.java:38-44` use `@RequestParam` instead of `@RequestBody` with DTOs.
**Fix:** Create `CreateZoneRequest` and `CreateDepotRequest` DTOs, update controllers to use `@RequestBody`.

---

### BUG-003: Route Validation Returns 500 When Road Network Is Missing
**Severity: HIGH**

**Description:** When the `blr_2po_4pgr` road network table is not loaded, the route validation endpoint throws `BadSqlGrammarException` which is not handled, resulting in HTTP 500. The README states this should return a 422 error.

**Reproduction:**
```bash
curl -X POST http://localhost:8080/api/routes/validate \
  -H "Content-Type: application/json" \
  -d '{"vehicleId":4,"originLat":12.912,"originLng":77.593,"destLat":12.972,"destLng":77.641}'
# → 500 INTERNAL_ERROR (BadSqlGrammarException: relation "blr_2po_4pgr_vertices_pgr" does not exist)
```

**Expected:** HTTP 422 or 503 with message "Road network not loaded. Route validation unavailable."
**Actual:** HTTP 500 with generic "An unexpected error occurred."
**Root Cause:** `GlobalExceptionHandler` has no handler for `BadSqlGrammarException`. The `RoutingException` handler exists but the SQL exception is not converted to `RoutingException` before reaching the handler.
**Fix:** Add `@ExceptionHandler(BadSqlGrammarException)` returning 503, or catch at the service layer and throw `RoutingException`.

---

### BUG-004: No Validation on Negative Speed Values
**Severity: MEDIUM**

**Description:** The `RecordPositionRequest` DTO accepts negative `speedKmh` values without validation. A speed of -10 km/h is physically impossible and indicates bad GPS data.

**Reproduction:**
```bash
curl -X POST http://localhost:8080/api/vehicles/1/positions \
  -H "Content-Type: application/json" \
  -d '{"lat":12.9116,"lng":77.6389,"speedKmh":-10.0}'
# → 200 OK (accepted)
```

**Expected:** HTTP 400 with validation error "Speed must be non-negative."
**Actual:** Accepted and stored in database.
**Root Cause:** `RecordPositionRequest.java` — no `@DecimalMin("0")` annotation on `speedKmh` field.
**Fix:** Add `@DecimalMin(value = "0", message = "Speed must be non-negative")` to speedKmh field.

---

### BUG-005: No Validation on Future Timestamps
**Severity: MEDIUM**

**Description:** Position records accept timestamps far in the future (year 2099). This corrupts time-series data, breaks partitioning assumptions, and could cause incorrect breach detection.

**Reproduction:**
```bash
curl -X POST http://localhost:8080/api/vehicles/1/positions \
  -H "Content-Type: application/json" \
  -d '{"lat":12.9116,"lng":77.6389,"timestamp":"2099-01-01T00:00:00Z"}'
# → 200 OK (accepted)
```

**Expected:** HTTP 400 with validation error "Timestamp cannot be in the future."
**Actual:** Accepted and stored. The position appears as the "latest" position for the vehicle.
**Root Cause:** `RecordPositionRequest.java` — no validation on timestamp field. `PositionTrackingService` does not validate timestamp recency.
**Fix:** Add validation in `PositionTrackingService.recordPosition()` to reject timestamps more than a configurable threshold in the future (e.g., 5 minutes).

---

### BUG-006: Zone Violations Endpoint Ignores zoneId Path Parameter
**Severity: HIGH**

**Description:** `GET /api/reports/zones/{zoneId}/violations` completely ignores the `{zoneId}` path parameter and returns violation stats for ALL zones. This makes the endpoint misleading and wastes resources.

**Reproduction:**
```bash
curl http://localhost:8080/api/reports/zones/999/violations
# → 200 OK with ALL 5 zones' violation stats (not just zone 999)
```

**Expected:** Return violations only for the specified zone, or 404 if zone doesn't exist.
**Actual:** Returns all zones' stats regardless of the zoneId parameter.
**Root Cause:** `ReportController.java:32-35` — the `zoneId` parameter is received but never passed to `reportingService.getZoneViolationStats()`, which queries `v_zone_violation_stats` view returning all zones.
**Fix:** Either filter the view results by zoneId in the service, or create a zone-specific query.

---

### BUG-007: Day-of-Week Bitmask Calculation Is Off-by-One
**Severity: MEDIUM**

**Description:** The trigger `fn_detect_zone_breach()` and stored procedure `sp_validate_route()` calculate the day-of-week bitmask incorrectly. The formula `(1 << (EXTRACT(DOW FROM ...)::INT % 7))` produces wrong bit values.

**Analysis:**
- PostgreSQL `EXTRACT(DOW)` returns: Sunday=0, Monday=1, ..., Saturday=6
- The plan specifies: Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64
- The formula `1 << (dow % 7)` produces: Sunday→1, Monday→2, Tuesday→4, Wednesday→8, Thursday→16, Friday→32, Saturday→64
- Correct mapping should be: Monday→1, Tuesday→2, Wednesday→4, Thursday→8, Friday→16, Saturday→32, Sunday→64

**Impact:** Sunday is mapped to bit 1 instead of bit 64, and all other days are shifted. With the current seed data (bitmask=127 = all bits set), this bug is masked. However, if a rule were created that only applies on specific days (e.g., weekdays only = bitmask 62), the trigger would fire on the wrong days.

**Root Cause:** `V7__triggers.sql:17` and `V10__stored_procedures.sql:17` — the shift formula doesn't account for PostgreSQL's DOW starting at 0 (Sunday) vs the plan's bitmask starting at 1 (Monday).
**Fix:** Use `(1 << ((EXTRACT(DOW FROM ...)::INT + 5) % 7))` or `(1 << CASE WHEN EXTRACT(DOW FROM ...) = 0 THEN 6 ELSE EXTRACT(DOW FROM ...)::INT - 1 END)`.

---

### BUG-008: Boundary Points Not Detected by ST_Within
**Severity: LOW

**Description:** The breach trigger uses `ST_Within()` which returns FALSE for points exactly on polygon boundaries. Vehicles positioned exactly on a zone edge will not trigger a breach.

**Impact:** Simulation waypoint 6 for Scenario B is at (77.617, 12.976) — exactly the northwest corner of the MG Road zone. ST_Within returns FALSE for this point.

**Expected:** Boundary points should be treated as inside the zone for safety-critical enforcement.
**Actual:** Boundary points are not detected as breaches.
**Root Cause:** `V7__triggers.sql:27` — uses `ST_Within` instead of `ST_Covers` or `ST_Intersects`.
**Fix:** Replace `ST_Within(NEW.position, zr.boundary)` with `ST_Covers(zr.boundary, NEW.position)` which includes boundary points.

---

### BUG-009: sp_validate_route Returns Only First Violation
**Severity: LOW

**Description:** The stored procedure `sp_validate_route` uses `LIMIT 1`, meaning if a route crosses multiple restricted zones, only the first violation is reported. The caller cannot know about all violations.

**Expected:** Return all violated zones so the dispatcher can see the full picture.
**Actual:** Only the first matching zone is returned.
**Root Cause:** `V10__stored_procedures.sql:38` — `LIMIT 1` restricts results.
**Fix:** Remove `LIMIT 1` and handle multiple violations in the service layer.

---

### BUG-010: Simulation Breach Detection Inconsistent in Tick Response
**Severity: MEDIUM

**Description:** During simulation ticks, breaches are recorded in the database by the trigger, but the tick response sometimes shows `breachDetected: false` even when breaches exist. This is caused by the position log ID not being populated by Hibernate after save on partitioned tables, and the fallback query using a 1-second time window that may miss records.

**Reproduction:** Run simulation ticks for Scenario B and observe that some ticks show `breachDetected: false` but the breach log shows records were created.

**Expected:** Tick response should accurately reflect whether breaches were detected.
**Actual:** Breach detection in response is unreliable for simulation positions.
**Root Cause:** `PositionTrackingService.java:84-90` — partitioned table ID fallback and timing window issue.
**Fix:** Use a more reliable query strategy, such as querying breaches within the same transaction using a direct native query that doesn't depend on the position log ID.

---

## 4. Edge Cases Missed by Implementation

### 4.1 Overnight Time Windows
**Risk:** The time window check `v_current_time BETWEEN start_time AND end_time` fails for restrictions that span midnight (e.g., 22:00–06:00). At 03:00, the BETWEEN check returns FALSE because 03:00 is not between 22:00 and 06:00.

**Impact:** Any zone with overnight restrictions would never fire during the night hours.

### 4.2 Concurrent Position Inserts
**Risk:** High-frequency position inserts from the same vehicle could cause race conditions in the trigger, especially under load. The trigger performs a SELECT on the vehicle table and loops through zones for every insert.

### 4.3 Zone Boundary Precision
**Risk:** Floating-point coordinate precision could cause points that should be inside a zone to be classified as outside (or vice versa) due to rounding in the JTS/PostGIS conversion chain.

### 4.4 Partition Growth Without Management
**Risk:** The DEFAULT partition absorbs all records outside defined monthly partitions. Over time, this could become a performance bottleneck if new monthly partitions are not created proactively.

### 4.5 Simulation Path with Vehicle That Becomes Inactive
**Risk:** If a vehicle is deactivated while its simulation path is active, the simulation will continue recording positions for an inactive vehicle. The `recordPosition` check for `is_active` would reject these, but the simulation doesn't handle this failure gracefully.

### 4.6 Duplicate Scenario Start
**Risk:** Starting an already-active simulation resets it to step 0 silently. This could lose simulation progress without warning.

### 4.7 GeoJSON Coordinate Order Confusion
**Risk:** The system assumes GeoJSON uses [longitude, latitude] order (correct per spec), but if a user provides [latitude, longitude], the coordinates would be silently swapped, placing vehicles/zones in wrong locations (e.g., in the ocean).

### 4.8 Timezone-Dependent Restriction Evaluation
**Risk:** The trigger and stored procedure use `CURRENT_TIME` and `NEW.recorded_at::TIME` which depend on the database session timezone. If the server timezone differs from the intended local timezone (IST for Bangalore), restrictions would fire at wrong times.

---

## 5. Architectural Evaluation

### 5.1 Layering Quality: **GOOD (8/10)**
- Clean separation: Controller → Service → Repository → Database
- No layer skipping observed
- Controllers contain only HTTP handling, no business logic
- Services contain all business logic
- Minor issue: `ReportingService` uses raw `JdbcTemplate` with manual column indexing (fragile to view schema changes)

### 5.2 Transactional Design: **GOOD (7/10)**
- `@Transactional` correctly applied to write operations
- Trigger ensures atomic breach detection with position insert
- Issue: `BreachService.computeReroute()` is called within the same transaction as position insert, which could slow down the critical path

### 5.3 Trigger Usage: **GOOD (8/10)**
- Correct choice to put breach detection in the database layer
- Handles multiple zone matches correctly (loop inserts multiple rows)
- Issue: Day-of-week calculation bug (BUG-007)
- Issue: ST_Within vs ST_Covers for boundary points (BUG-008)

### 5.4 Route Validation Design: **ADEQUATE (6/10)**
- Stored procedure approach is correct
- Alternative route computation with cost penalty is clever
- Issue: No graceful degradation when road network is missing
- Issue: Only returns first violation (BUG-009)

### 5.5 Simulation Architecture: **GOOD (8/10)**
- Clean tick-based progression
- Reuses position tracking service (DRY)
- Proper completion handling
- Issue: Breach detection response inconsistency (BUG-010)

### 5.6 Maintainability: **GOOD (7/10)**
- Consistent naming conventions
- Clear package structure
- Lombok reduces boilerplate
- Issue: Manual column indexing in reporting (fragile)
- Issue: No integration tests

### 5.7 Extensibility: **GOOD (7/10)**
- Adding new vehicle classes requires enum + DB constraint update
- Adding new zone types requires enum + DB constraint update
- The zone/rule split makes it easy to add complex rules
- Issue: No plugin/extension mechanism for custom breach handlers

### 5.8 DB Design Quality: **EXCELLENT (9/10)**
- BCNF normalization
- Proper use of partitioning for time-series data
- GIST spatial indexes on all geometry columns
- Compound PK on partitioned table (required by PostgreSQL)
- Soft FK on partitioned table reference (documented trade-off)
- Views for reporting (no duplicated aggregation logic)

---

## 6. Security & Reliability Observations

### 6.1 No Authentication/Authorization
**Risk:** ALL endpoints are publicly accessible. Any client can create vehicles, record positions, start simulations, and view all data.

**Mitigation:** Add Spring Security with JWT or OAuth2 for production.

### 6.2 SQL Injection via String Formatting
**Risk:** `BreachService.computeZoneAvoidingRoute()` and `RouteComplianceService.computeAlternativeRoute()` use `String.format()` to insert zone IDs into SQL queries. While zone IDs are Long values (not user-controlled), this pattern is fragile and could become an injection vector if the code evolves.

**Location:** `BreachService.java:107-114`, `RouteComplianceService.java:125-138`

### 6.3 No Rate Limiting
**Risk:** Position recording endpoint can be flooded with requests, causing database overload and trigger execution overhead.

### 6.4 Stack Trace Leakage Prevention
**Positive:** The generic exception handler returns a generic message without stack traces. However, the missing specific handlers mean that some error types still leak internal information through the 500 response.

### 6.5 No Input Size Limits
**Risk:** Very long strings in vehicle registration (200+ chars) are accepted and only fail at the DB constraint level. This wastes resources before rejection.

### 6.6 Trust in Client Timestamps
**Risk:** The system accepts client-provided timestamps for position records. A malicious client could backdate or forward-date positions to manipulate breach detection.

---

## 7. Performance Observations

### 7.1 Trigger Overhead
Every position insert triggers a full scan of all active zones with spatial joins. With 5 zones this is fine, but with 50+ zones the trigger would become a bottleneck. Consider:
- Pre-filtering zones by bounding box before ST_Within
- Caching active zone geometries in application memory

### 7.2 N+1 Query Pattern in Zone Response
`ZoneService.toResponse()` calls `zoneRestrictionRuleRepository.findByZoneIdAndIsActive()` for each zone in the list response. For N zones, this generates N+1 queries.

**Location:** `ZoneService.java:94`

### 7.3 Reporting View Column Indexing
`ReportingService` maps view columns by numeric index (`row[0]`, `row[1]`, etc.). If the view schema changes (column order), this silently produces wrong data.

**Location:** `ReportingService.java:29-38`, `ReportingService.java:47-57`

### 7.4 Partition Pruning
The DEFAULT partition will grow unbounded if new monthly partitions are not created. Queries that scan the DEFAULT partition will be slow.

### 7.5 Missing Index on `simulation_path.is_active`
The `findAllActive()` query filters by `is_active = true` but there's no B-tree index on this column. With only 3 paths this is fine, but would degrade with many scenarios.

### 7.6 Reroute Computation in Hot Path
`BreachService.computeReroute()` runs pgRouting Dijkstra on every breach, which is expensive. This runs within the same transaction as the position insert, slowing down the critical path. Should be asynchronous.

---

## 8. Implementation Quality Ratings

| Category | Score (1-10) | Justification |
|---|---|---|
| **Architecture** | 7 | Clean layered design, but missing error handling consistency and API design gaps |
| **API Design** | 5 | Inconsistent input methods (@RequestParam vs @RequestBody), missing standard error responses |
| **Database Design** | 9 | Excellent normalization, partitioning, triggers, views, and indexing |
| **Spatial Logic** | 7 | Correct use of ST_Within/ST_Intersects, but boundary point handling and day-of-week bugs |
| **Simulation System** | 8 | Well-designed tick engine, proper state management, minor breach detection inconsistency |
| **Reliability** | 6 | Trigger atomicity is good, but missing validations and error handling reduce reliability |
| **Error Handling** | 4 | Critical gap — 5+ common exception types not handled, all return 500 |
| **Maintainability** | 7 | Clean code structure, but fragile column indexing and no integration tests |
| **Scalability** | 6 | Partitioning is good, but trigger overhead and sync reroute computation limit scale |
| **Production Readiness** | 5 | Strong academic foundation, but needs auth, rate limiting, error handling, and validation hardening |

---

## 9. Final Verdict

### Does the project behave correctly overall?
**Mostly.** The core functionality (vehicle CRUD, position tracking, simulation, breach detection via trigger) works correctly for the seeded data and happy-path scenarios. However, several logic bugs and validation gaps would cause incorrect behavior in edge cases.

### Does it satisfy its intended goals?
**Yes, for demonstration purposes.** The system successfully demonstrates geospatial fleet compliance with PostGIS, pgRouting, DB triggers, partitioning, and stored procedures. It achieves its academic objectives.

### Is it academically strong?
**Yes.** The BCNF normalization analysis, partitioning strategy, trigger-based atomic breach detection, and spatial indexing demonstrate strong database engineering knowledge. The layered Spring Boot architecture is clean and well-organized.

### Is it production-ready?
**No.** Critical gaps include:
1. No authentication/authorization
2. Missing error handlers causing 500 for common client errors
3. API design inconsistency (zone/depot creation)
4. Missing input validations (speed, timestamp)
5. Day-of-week calculation bug that would cause wrong-day enforcement
6. No rate limiting
7. Synchronous reroute computation in the critical path

### Highest-Priority Improvements
1. **Add missing exception handlers** (BUG-001) — immediate impact on API usability
2. **Fix Zone/Depot creation to use @RequestBody** (BUG-002) — API consistency
3. **Add validation for speed and timestamp** (BUG-004, BUG-005) — data integrity
4. **Fix day-of-week bitmask calculation** (BUG-007) — correctness of time-based restrictions
5. **Fix reports endpoint to respect zoneId** (BUG-006) — functional correctness
6. **Handle missing road network gracefully** (BUG-003) — operational robustness
7. **Replace ST_Within with ST_Covers** (BUG-008) — boundary point detection
8. **Add authentication** — security baseline

### Most Dangerous Flaws
1. **Day-of-week bitmask bug (BUG-007):** Would cause restrictions to fire on wrong days in production with non-trivial rules. This is a silent correctness bug.
2. **Missing error handlers (BUG-001):** Clients receive 500 for their own mistakes, making debugging impossible and masking real server errors.
3. **Future timestamp acceptance (BUG-005):** Could corrupt time-series data and cause incorrect breach detection by making stale positions appear as "latest."
4. **Overnight time window gap (Edge Case 4.1):** Any zone with overnight restrictions would never enforce during night hours — a silent failure.
