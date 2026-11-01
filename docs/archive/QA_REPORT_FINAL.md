# ZonePilot — End-to-End QA Report

**Date:** 2026-05-23  
**Tester:** Kiro QA Agent  
**Environment:** Docker Compose (backend:8080, postgres:5434)  
**Build:** backend-0.0.1-SNAPSHOT.jar, 16 Flyway migrations applied  
**Test time:** 22:12 IST (16:42 UTC)

---

## 1. Executive Summary

ZonePilot is a Spring Boot fleet management backend with zone-based restriction enforcement, breach detection, simulation, and reporting. The application starts cleanly and all 92 unit tests pass. Core CRUD flows for vehicles, depots, and zones work correctly. Error handling is consistent and well-structured.

However, **three high-severity bugs remain unfixed** that directly affect production correctness: a timezone mismatch in two SQL queries (the repository and the view) that causes the active-restrictions endpoint and zones-at-location to return wrong results; a road network that is never loaded, making route validation permanently broken; and a simulation state machine that becomes inconsistent after path exhaustion. Several medium-severity API contract issues affect frontend rendering.

The system is **not production-ready** in its current state.

---

## 2. Test Coverage Summary

| Area | Tested | Method |
|---|---|---|
| Vehicle CRUD | ✅ | Black-box API |
| Depot CRUD | ✅ | Black-box API |
| Zone CRUD | ✅ | Black-box API |
| Position recording | ✅ | Black-box API |
| Breach detection (trigger) | ✅ | Black-box API + source review |
| Breach acknowledgement | ✅ | Black-box API |
| Route validation | ✅ | Black-box API |
| Route history | ✅ | Black-box API |
| Simulation start/tick/reset | ✅ | Black-box API |
| Reports: summary | ✅ | Black-box API |
| Reports: active-restrictions | ✅ | Black-box API + source review |
| Reports: zone violations | ✅ | Black-box API |
| zones-at-location | ✅ | Black-box API |
| Error handling (all error types) | ✅ | Black-box API |
| Edge cases (invalid IDs, enums, geometry, timestamps) | ✅ | Black-box API |
| Unit tests | ✅ | Surefire reports |
| Docker startup | ✅ | docker compose logs |
| Runtime logs | ✅ | docker compose logs |

---

## 3. Pass/Fail Breakdown

| Feature | Status | Notes |
|---|---|---|
| Vehicle CRUD | ✅ PASS | Create, read, update all work |
| Depot CRUD | ✅ PASS | Create, read all work |
| Zone CRUD | ✅ PASS | Create, read all work |
| Position recording | ✅ PASS | Correct schema, breach detection fires |
| Breach detection (trigger) | ✅ PASS | V16 trigger correctly uses IST |
| Breach acknowledgement | ✅ PASS | Idempotent (by design or bug — see BUG-07) |
| Route validation | ❌ FAIL | Road network not loaded — always 422 |
| Route history | ✅ PASS | Returns empty list correctly |
| Simulation start | ✅ PASS | Correct schema required |
| Simulation tick | ⚠️ PARTIAL | Works until exhaustion; broken state after |
| Simulation reset | ✅ PASS | Resets step to 0 |
| Reports: summary | ✅ PASS | Returns per-vehicle breach counts |
| Reports: active-restrictions | ❌ FAIL | Uses UTC CURRENT_TIME, wrong results |
| Reports: zone violations | ✅ PASS | Returns correct counts |
| zones-at-location | ⚠️ PARTIAL | Returns zone but rules=null |
| Error handling | ✅ PASS | Consistent error envelope |
| Unit tests | ✅ PASS | 92/92 pass |

---

## 4. Bug List

---

### BUG-01 — Timezone Mismatch in `v_currently_active_restrictions` View

**Severity:** High  
**Component:** `V8__views.sql` → `v_currently_active_restrictions`, `ReportingService`

**How to reproduce:**
```
GET /api/reports/active-restrictions
```
At 22:12 IST (16:42 UTC), Zone 1 (MG Road) has a rule active 21:00–23:00 IST. The endpoint returns only 2 restrictions and does not include Zone 1's evening rule.

**Expected:** All restrictions whose time window is currently active in IST are returned.  
**Actual:** The view uses `CURRENT_TIME` (PostgreSQL server time = UTC), so a rule active 21:00–23:00 IST is invisible at 16:42 UTC.

**Root cause:**
```sql
-- V8__views.sql (unfixed)
OR (CURRENT_TIME BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
```
The V16 migration fixed the trigger but did not update this view.

**Frontend impact:** A map or dashboard showing "currently active restrictions" will be wrong for any IST time window. Operators will miss active restrictions.

**Fix:** Replace `CURRENT_TIME` with `(NOW() AT TIME ZONE 'Asia/Kolkata')::TIME` and add overnight window support (same pattern as V16 trigger).

---

### BUG-02 — Timezone Mismatch in `ZoneRestrictionRuleRepository.findCurrentlyActiveRulesForZone`

**Severity:** High  
**Component:** `ZoneRestrictionRuleRepository.java`

**How to reproduce:**
```
GET /api/vehicles/7/zones-at-location?lat=12.974&lng=77.620
```
At 22:12 IST, Zone 1 has an active rule (21:00–23:00 IST). The endpoint returns the zone but `rules` is `null`.

**Expected:** The zone is returned with its currently-active rules populated.  
**Actual:** `rules: null` — the repository query uses `CURRENT_TIME` (UTC), so no rules match.

**Root cause:**
```sql
-- ZoneRestrictionRuleRepository.java (unfixed)
OR CURRENT_TIME BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time
```
Also uses `EXTRACT(DOW FROM NOW())` without timezone conversion for the day bitmask.

**Frontend impact:** A vehicle detail panel showing "zones at current location with active rules" will always show `null` for rules during IST-offset hours. Any frontend null-check on `rules` will silently hide restriction data.

**Fix:** Replace `CURRENT_TIME` with `(NOW() AT TIME ZONE 'Asia/Kolkata')::TIME` and `EXTRACT(DOW FROM NOW())` with `EXTRACT(DOW FROM (NOW() AT TIME ZONE 'Asia/Kolkata'))`.

---

### BUG-03 — Road Network Not Loaded — Route Validation Always Fails

**Severity:** High  
**Component:** `RoutingService`, `SimulationDataSeeder`

**How to reproduce:**
```
POST /api/routes/validate
{"vehicleId":7,"originLat":12.9716,"originLng":77.5946,"destLat":12.974,"destLng":77.620}
```

**Expected:** Route validation returns compliance result.  
**Actual:**
```json
{"success":false,"error":{"code":"ROUTING_ERROR","message":"No road network node found near coordinates (12.9716, 77.5946)"}}
```
HTTP 422 on every call.

**Root cause:** The road network (OSM data) is never imported into the `road_network_node` / `road_network_edge` tables. The seeder skips re-seeding because simulation paths already exist, but the road network import is a separate step that requires running `osm2pgrouting` or equivalent. The `bangalore.osm.pbf` and `karnataka-latest.osm.pbf` files exist in the project root but are never loaded.

**Frontend impact:** Route planning UI is completely non-functional. Any "validate route" or "reroute" button will always show an error. Reroute suggestions in breach responses are always `null`.

**Fix:** Add an `osm2pgrouting` step to the Docker Compose setup or provide a pre-loaded road network dump. Document the import step clearly.

---

### BUG-04 — Simulation State Inconsistent After Path Exhaustion

**Severity:** Medium  
**Component:** `SimulationService`

**How to reproduce:**
1. `POST /api/simulation/start` with all 3 scenarios
2. Run 17 ticks (paths have 17 steps)
3. `GET /api/simulation/state`
4. `POST /api/simulation/tick` again

**Expected after exhaustion:**
- State shows `currentStep=17/17` and `isActive=false`
- Tick returns a meaningful response (e.g., `{"tickNumber":17,"vehicles":[],"exhausted":true}`)
- Repeated ticks do not keep logging warnings

**Actual:**
- State shows `currentStep=16/17, isActive=true` — the step counter is off by one and `isActive` is never set to false
- Tick returns `{"tickNumber":17,"vehicles":[]}` — empty vehicles with no indication of exhaustion
- Every tick after exhaustion logs 3 WARN lines: `Could not extract waypoint at step 17 for path N`

**Frontend impact:** A simulation dashboard will show all vehicles as "active" and at step 16/17 indefinitely. The UI cannot distinguish "simulation running" from "simulation exhausted". The tick counter freezes at 17 and never increments, so a progress bar would appear stuck.

**Fix:**
1. When all paths reach their last step, set `isActive=false` and `currentStep=totalSteps`
2. Return a `SimulationTickResponse` with an `exhausted: true` flag or a non-empty status field
3. Guard the waypoint extraction to not log WARN when step >= totalSteps

---

### BUG-05 — Zone Creation Ignores Rules in Request Body

**Severity:** Medium  
**Component:** `ZoneController`, `CreateZoneRequest`

**How to reproduce:**
```
POST /api/zones
{
  "name": "Test Zone",
  "boundaryGeoJson": "...",
  "restrictionType": "TIME_RESTRICTED",
  "rules": [{"applicableVehicleClass":"HCV","restrictionStartTime":"08:00","restrictionEndTime":"10:00","daysOfWeekBitmask":31,"isActive":true}]
}
```

**Expected:** Zone is created with the provided rules.  
**Actual:** Zone is created with `rules: []` — the rules array in the request is silently ignored.

**Root cause:** `CreateZoneRequest` schema (confirmed via OpenAPI) does not include a `rules` field. The controller only creates the zone entity, not its rules.

**Frontend impact:** A zone creation form that includes rule configuration will appear to succeed but the rules will not be saved. The user has no indication that rules were dropped. They would need a separate (undocumented) API call to add rules.

**Fix:** Add a `rules` field to `CreateZoneRequest` and persist them in the zone creation transaction. Or document that rules must be added via a separate endpoint (which currently does not exist).

---

### BUG-06 — `boundaryGeoJson` Returned as WKT String, Not GeoJSON Object

**Severity:** Medium  
**Component:** `ZoneResponse`, all zone endpoints

**How to reproduce:**
```
GET /api/zones/1
```

**Expected:**
```json
"boundaryGeoJson": {"type":"Polygon","coordinates":[...]}
```

**Actual:**
```json
"boundaryGeoJson": "POLYGON ((77.617 12.976, 77.623 12.976, ...))"
```

The field is named `boundaryGeoJson` but contains WKT (Well-Known Text), not GeoJSON.

**Frontend impact:** Any map library (Leaflet, Mapbox, Google Maps) expects GeoJSON to render zone boundaries. A frontend that passes `boundaryGeoJson` directly to a map renderer will fail silently or throw a parse error. The field name is actively misleading.

**Fix:** Either serialize the geometry as a proper GeoJSON object (`{"type":"Polygon","coordinates":[...]}`) or rename the field to `boundaryWkt`. GeoJSON is strongly preferred for frontend compatibility.

---

### BUG-07 — Re-acknowledging a Breach Returns 200 Instead of 409

**Severity:** Low  
**Component:** `BreachController`

**How to reproduce:**
```
PUT /api/breaches/56/acknowledge   # first call → 200
PUT /api/breaches/56/acknowledge   # second call → 200 (same response)
```

**Expected:** Second call returns 409 Conflict or 422 with a message like "Breach already acknowledged".  
**Actual:** Returns 200 with the same acknowledged breach. The operation is silently idempotent.

**Frontend impact:** A UI that shows a confirmation toast on acknowledge will show it twice if the user double-clicks. More importantly, audit logs cannot distinguish a first acknowledgement from a repeated one.

**Fix:** Check `isAcknowledged` before updating. Return 409 if already acknowledged, or at minimum return a different response body indicating no change was made.

---

### BUG-08 — `active-restrictions` Times Returned as `HH:MM:SS` Instead of `HH:MM`

**Severity:** Low  
**Component:** `ReportingService`, `v_currently_active_restrictions`

**How to reproduce:**
```
GET /api/reports/active-restrictions
```

**Actual response:**
```json
"restrictionStartTime": "08:00:00",
"restrictionEndTime": "22:00:00"
```

**Expected:** `"08:00"` — consistent with how times are returned in zone rules (`"07:00"`, `"11:00"`).

**Root cause:** `ReportingService` uses `rs.getTime("restriction_start_time")` which returns a `java.sql.Time` serialized as `HH:MM:SS`. Zone rules use `LocalTime` which serializes as `HH:MM`.

**Frontend impact:** A UI that displays restriction times will show inconsistent formats across different endpoints. A time comparison or display component that expects `HH:MM` will break on `HH:MM:SS`.

**Fix:** Convert `java.sql.Time` to `LocalTime` before putting it in the response map, or use a consistent `DateTimeFormatter` that outputs `HH:MM`.

---

### BUG-09 — Validation Errors Lack Field-Level Detail

**Severity:** Low  
**Component:** `GlobalExceptionHandler`

**How to reproduce:**
```
POST /api/vehicles
{"registrationNumber": "KA01-TEST"}
```

**Actual:**
```json
{"success":false,"error":{"code":"VALIDATION_ERROR","message":"Invalid request body"}}
```

**Expected:**
```json
{"success":false,"error":{"code":"VALIDATION_ERROR","message":"Invalid request body","fields":{"vehicleClass":"must not be blank","ownerName":"must not be blank","depotId":"must not be null"}}}
```

**Frontend impact:** A form that submits invalid data gets no field-level feedback. The UI cannot highlight which fields are wrong. The user sees a generic error and must guess what to fix.

**Fix:** In the `MethodArgumentNotValidException` handler, extract `BindingResult.getFieldErrors()` and include them as a `fields` map in the error response.

---

### BUG-10 — `zones-at-location` Returns `rules: null` Instead of `rules: []`

**Severity:** Low  
**Component:** `VehicleController`, `ZoneResponse`

**How to reproduce:**
```
GET /api/vehicles/7/zones-at-location?lat=12.974&lng=77.620
```

**Actual:**
```json
[{"id":1,"name":"MG Road No-Entry","rules":null}]
```

**Expected:** `"rules": []` when no rules are currently active, or the full rules list.

**Frontend impact:** Any frontend code that iterates `zone.rules.forEach(...)` will throw a null pointer / TypeError. Defensive null checks are required everywhere this field is used.

**Fix:** Return an empty array `[]` instead of `null` when no rules match. This is a standard API contract expectation.

---

## 5. Edge Cases and Failure Modes

| Test | Result |
|---|---|
| Invalid vehicle ID (99999) | ✅ 404 with clear message |
| Non-numeric ID (`/vehicles/abc`) | ✅ 400 with type error |
| Malformed JSON | ✅ 400 MALFORMED_JSON |
| Missing required fields | ✅ 400 VALIDATION_ERROR (but no field detail — BUG-09) |
| Wrong Content-Type | ✅ 415 UNSUPPORTED_MEDIA_TYPE |
| Wrong HTTP method (DELETE) | ✅ 405 METHOD_NOT_ALLOWED |
| Invalid enum value | ✅ 400 INVALID_VALUE with enum class name |
| Coordinates out of Bangalore bounds | ✅ 400 VALIDATION_ERROR |
| Negative speed | ✅ 400 VALIDATION_ERROR |
| Duplicate registration number | ✅ 400 with clear message |
| Invalid depot ID for vehicle | ✅ 404 RESOURCE_NOT_FOUND |
| Future timestamp (>5 min) | ✅ 400 with clear message |
| Invalid breach ID | ✅ 404 RESOURCE_NOT_FOUND |
| Invalid zone boundary GeoJSON | ✅ 400 with parse error |
| Unclosed polygon | ✅ 400 with parse error |
| Invalid restriction type enum | ✅ 400 INVALID_VALUE |
| Position for inactive vehicle | ✅ 400 with clear message |
| Position for non-existent vehicle | ✅ 404 RESOURCE_NOT_FOUND |
| Position outside Bangalore bounds | ✅ 400 VALIDATION_ERROR |
| Zone boundary point (exactly on edge) | ✅ Breach detected (inclusive) |
| Simulation with invalid scenario | ✅ 400 SIMULATION_ERROR |
| Simulation with empty scenarios array | ✅ 400 VALIDATION_ERROR |
| Tick without start | ✅ Returns tick=0, vehicles=[] |
| Zone violations for non-existent zone | ⚠️ Returns 200 with empty array (not 404) |
| Zones-at-location missing params | ✅ 400 MISSING_PARAMETER |
| Zones-at-location invalid params | ✅ 400 INVALID_PARAMETER_TYPE |

**Notable:** `GET /api/reports/zones/99999/violations` returns `200 []` instead of `404`. This is a minor inconsistency — the zone doesn't exist but the endpoint returns success with empty data.

---

## 6. Frontend Impact Assessment

| Issue | Impact |
|---|---|
| `boundaryGeoJson` is WKT not GeoJSON | **Map rendering breaks.** Zone polygons cannot be drawn on any standard map library without manual WKT parsing. |
| `rules: null` in zones-at-location | **Runtime errors.** Any `rules.forEach()` or `rules.map()` call throws TypeError. |
| `active-restrictions` times as `HH:MM:SS` | **Display inconsistency.** Time formatting differs from zone rules (`HH:MM`). |
| No field-level validation errors | **Poor UX.** Forms cannot highlight specific invalid fields. |
| Simulation `isActive=true` after exhaustion | **Incorrect state display.** Vehicle status indicators show "active" when simulation is done. |
| `tickNumber` frozen at 17 | **Broken progress bar.** A simulation progress indicator would appear stuck. |
| Route validation always fails | **Feature completely broken.** Route planning UI is non-functional. |
| `active-restrictions` missing IST-offset rules | **Wrong data.** Operators see incorrect restriction status. |
| `zones-at-location` missing active rules | **Wrong data.** Vehicle detail panels show no active restrictions. |
| Re-acknowledge returns 200 | **Misleading feedback.** Double-click shows success toast twice. |

---

## 7. Runtime and Log Issues

| Issue | Severity |
|---|---|
| `WARN: PostgreSQLDialect does not need to be specified explicitly` | Low — deprecated config, harmless |
| `WARN: spring.jpa.open-in-view is enabled by default` | Low — performance risk in production, should be disabled |
| `WARN: Could not extract waypoint at step 17 for path N` — fires on every tick after exhaustion | Medium — log spam, indicates unguarded code path |
| `WARN: Cannot compute reroute for breach N: no road node near (lat, lng)` — fires on every breach | Medium — expected given no road network, but every breach triggers this |
| Simulation seeder skips on restart: `Simulation paths already exist and road network is not loaded — skipping re-seed` | Info — correct behavior, but road network never loads |

The log volume from repeated `Could not extract waypoint` warnings will make production log monitoring noisy and obscure real errors.

---

## 8. Severity Ranking

| # | Bug | Severity |
|---|---|---|
| BUG-03 | Route validation always fails (no road network) | **High** |
| BUG-01 | `active-restrictions` view uses UTC CURRENT_TIME | **High** |
| BUG-02 | `findCurrentlyActiveRulesForZone` uses UTC CURRENT_TIME | **High** |
| BUG-04 | Simulation state inconsistent after exhaustion | **Medium** |
| BUG-05 | Zone creation ignores rules in request body | **Medium** |
| BUG-06 | `boundaryGeoJson` returned as WKT not GeoJSON | **Medium** |
| BUG-07 | Re-acknowledge breach returns 200 | **Low** |
| BUG-08 | `active-restrictions` times as `HH:MM:SS` | **Low** |
| BUG-09 | Validation errors lack field-level detail | **Low** |
| BUG-10 | `zones-at-location` returns `rules: null` | **Low** |

---

## 9. Recommended Next Actions

**Immediate (block release):**

1. **Fix BUG-01 and BUG-02** — Add a new Flyway migration (V17) that:
   - Recreates `v_currently_active_restrictions` using `(NOW() AT TIME ZONE 'Asia/Kolkata')::TIME` and adds overnight window support
   - Updates `ZoneRestrictionRuleRepository.findCurrentlyActiveRulesForZone` to use the same IST-aware time and day extraction

2. **Fix BUG-03** — Document and automate the road network import. Add an `osm2pgrouting` container or init script to Docker Compose, or provide a pre-loaded SQL dump. Without this, route validation and rerouting are permanently broken.

3. **Fix BUG-04** — In `SimulationService.tick()`, after advancing all paths to their last step, set `isActive=false` and `currentStep=totalSteps`. Add an `exhausted` flag to `SimulationTickResponse`. Guard the waypoint extraction to avoid logging WARN when step >= totalSteps.

**Before next sprint:**

4. **Fix BUG-05** — Add `rules` to `CreateZoneRequest` and persist them in the zone creation transaction.

5. **Fix BUG-06** — Serialize `boundaryGeoJson` as a proper GeoJSON object. This is a breaking change to the API contract — coordinate with frontend.

6. **Fix BUG-09** — Extract field errors from `MethodArgumentNotValidException` and include them in the error response.

7. **Fix BUG-10** — Return `[]` instead of `null` for `rules` in `zones-at-location`.

**Housekeeping:**

8. **Fix BUG-08** — Normalize time serialization to `HH:MM` across all endpoints.

9. **Fix BUG-07** — Return 409 on re-acknowledge.

10. **Disable `spring.jpa.open-in-view`** in `application.yml` to prevent lazy-loading queries during serialization.

11. **Remove explicit `hibernate.dialect`** from `application.yml` to clear the deprecation warning.

---

## Appendix: API Contract Issues Summary

| Endpoint | Field | Actual Type | Expected Type |
|---|---|---|---|
| `GET /api/zones/*` | `boundaryGeoJson` | WKT string | GeoJSON object |
| `GET /api/vehicles/{id}/zones-at-location` | `rules` | `null` | `[]` or populated array |
| `GET /api/reports/active-restrictions` | `restrictionStartTime` | `"HH:MM:SS"` | `"HH:MM"` |
| `GET /api/reports/active-restrictions` | `restrictionEndTime` | `"HH:MM:SS"` | `"HH:MM"` |
| `POST /api/simulation/tick` (exhausted) | `vehicles` | `[]` | `[]` + `exhausted: true` |
| `GET /api/simulation/state` (exhausted) | `isActive` | `true` | `false` |
| `GET /api/simulation/state` (exhausted) | `currentStep` | `16` | `17` |
| `POST /api/zones` | `rules` | ignored | persisted |
| `POST /api/vehicles` (validation fail) | `error.fields` | absent | field-level map |
