# Iterative Zone-Avoidance Routing + Google Traffic Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace single-shot routing with a capped iterative infinite-cost retry loop, and enrich the winning route with Google Routes traffic data plus a geometric similarity score.

**Architecture:** Keep `RouteComplianceService.computeBestRoute` as orchestrator; add accumulating penalised-zone loop (max 3 Dijkstra attempts) with huge-cost edges; add origin/destination-inside-zone guard to wait-state; extend `TimePredictionService` to fetch duration/staticDuration/distance/polyline and compute Hausdorff+overlap similarity in PostGIS.

**Tech Stack:** Spring Boot 3.x, JdbcTemplate + pgr_dijkstra (directed), PostGIS (ST_HausdorffDistance, ST_Intersection buffer overlap), Google Routes API `computeRoutes` (TRAFFIC_AWARE, via:true), JTS.

**Spec:** User decisions 2026-10-25: (1) accumulate ALL violated zones per retry, (2) huge-cost constant not edge exclusion, (3) start/end-inside-zone → wait-state fallback, (4) Hausdorff+overlap not cosine, (5) enrich response+DB with google duration/static/distance/delay.

## Global Constraints

- Java 17+, Spring Boot 3.x, Jakarta namespace — no javax imports.
- pgRouting always `directed := true`; nearest-node via `<->` KNN operator only.
- NULL restriction times = 24/7 active; `ALL` vehicle class matches every class.
- Max 3 routing attempts total (1 base + 2 retries) to avoid infinite loop when start/end covered by zones.
- Google `intermediates` max 25, via:true so legs don't split; 11–25 billed higher — chunk evenly-spaced points.
- Never SELECT * new columns without updating DTO/entity/migration together.

## Review Focus

- Origin inside zone but destination outside (and vice versa) → must return wait-state, not empty route.
- All 3 attempts still violate (corridor fully blocked) → must return best-effort route + wait-state, not 500.
- Google API key missing/timeout → durations -1/null, route still returned (graceful degradation).
- Google polyline decodes to far-away geometry (different road) → similarity near 0 must not fail validation.
- Overlapping zones (point in 2 polygons) → penalised set must contain both zone IDs.

---

### Task 1: Iterative infinite-cost retry loop in RouteComplianceService

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/RouteComplianceService.java:156-208`
- Test: `backend/src/test/java/com/zonepilot/backend/service/RouteComplianceServiceTest.java`

**Interfaces:**
- Consumes: `RoutingService.computeRoute(a,b)`, `RoadNetworkRepository` via existing `computeRouteAvoidingZones`, `sp_validate_route(vehicleId, wkt)`, `TimePredictionService`.
- Produces: `RouteCandidate(geometry, violations, waitDurationSec, waitUntil, totalTripSec)` unchanged; new private `computeRouteAvoidingZones(source, target, Set<Long>)` with huge-cost constant.

- [ ] **Step 1: Write failing test for accumulation + cap**

```java
@Test void retryAccumulatesZonesAndCapsAtThreeAttempts() {
    // mock: attempt1 violates zone 1, attempt2 violates zone 2, attempt3 still violates
    // assert: pgr_dijkstra edge-SQL called <=3 times, 3rd call penalises zones {1,2}
    // assert: result carries wait-state (no infinite loop)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl backend test -Dtest=RouteComplianceServiceTest#retryAccumulatesZonesAndCapsAtThreeAttempts`
Expected: FAIL (loop does not accumulate; only single waypoint attempt)

- [ ] **Step 3: Implement iterative loop in `RouteComplianceService.computeBestRoute`**

Replace Phase2/Phase3 with: `penalised=LinkedHashSet; route=direct; for (i=0;i<3;i++){ violations=validate(route); if empty return; penalised.addAll(zoneIds); if endpoint-in-zone break→wait-state; route=computeRouteAvoidingZones(src,tgt,penalised); } return wait-state fallback`. Endpoint check via `ST_Within(origin/dest, boundary)` single query. Huge cost = `cost_time_sec * 1e9` (keeps one-way `reverse_cost=-1` intact).

- [ ] **Step 4: Change `computeRouteAvoidingZones` multiplier 1000 → 1e9**

In edge SQL `CASE WHEN ST_Intersects(...) THEN cost_time_sec * 1e9 ELSE cost_time_sec END` (both cost and reverse_cost branches). Large enough Dijkstra never prefers it unless disconnected.

- [ ] **Step 5: Run tests to verify pass**

Run: `mvn -pl backend test -Dtest=RouteComplianceServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/service/RouteComplianceService.java backend/src/test/java/com/zonepilot/backend/service/RouteComplianceServiceTest.java
git commit -m "feat: iterative infinite-cost zone-avoidance retry loop capped at 3"
```

### Task 2: Google traffic enrichment + polyline fetch

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/TimePredictionService.java:34-97`
- Test: `backend/src/test/java/com/zonepilot/backend/service/TimePredictionServiceTest.java` (create if absent)

**Interfaces:**
- Consumes: `LineString routeGeometry`, `List<double[]> zoneEntryPoints`, `Instant departureTime`.
- Produces: `TrafficEnrichment(durationSec, staticDurationSec, distanceMeters, delaySec, googlePolylineWkt, similarityScore)` — new record consumed by Task 3.

- [ ] **Step 1: Write failing test for enriched field mask**

```java
@Test void fetchReturnsStaticDurationDistanceAndPolyline() {
    // mock HTTP: routes[0] = {duration:"600s", staticDuration:"480s", distanceMeters:5200, polyline:{encodedPolyline:"..."}}
    // assert: enrichment.delaySec == 120 && distanceMeters == 5200 && polyline non-null
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl backend test -Dtest=TimePredictionServiceTest#fetchReturnsStaticDurationDistanceAndPolyline`
Expected: FAIL (current FIELD_MASK only requests legs.duration + duration)

- [ ] **Step 3: Implement `fetchTrafficEnrichment` in `TimePredictionService.java`**

Single POST to `https://routes.googleapis.com/directions/v2:computeRoutes` with body `{origin, destination, intermediates: buildIntermediates(capped 25, zone-entry first + evenly-spaced, via:true), travelMode:DRIVE, routingPreference:TRAFFIC_AWARE, departureTime:RFC3339}` and header `X-Goog-FieldMask: routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.travelAdvisory`. Parse `duration/staticDuration` ("123s"→long), `distanceMeters`, `delay = duration-static`. Return nulls/-1 on missing key/timeout (no throw).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl backend test -Dtest=TimePredictionServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/service/TimePredictionService.java backend/src/test/java/com/zonepilot/backend/service/TimePredictionServiceTest.java
git commit -m "feat: fetch google duration, static duration, distance and polyline"
```

### Task 3: Geometric similarity (Hausdorff + overlap) — no cosine

**Files:**
- Create: `backend/src/main/java/com/zonepilot/backend/service/RouteSimilarityService.java`
- Test: `backend/src/test/java/com/zonepilot/backend/service/RouteSimilarityServiceTest.java`

**Interfaces:**
- Consumes: `String ourWkt, String googleWkt` (both SRID 4326).
- Produces: `double similarityScore()` in [0,1] used in `RouteValidationResponse.googleSimilarityScore`.

- [ ] **Step 1: Write failing test for similarity bounds**

```java
@Test void identicalRoutesScoreNearOne_andFarRoutesNearZero() {
    assertTrue(similarity.scorelineIdenticalWkts) > 0.95);
    assertTrue(similarity.scoreFarApartWkts) < 0.2);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl backend test -Dtest=RouteSimilarityServiceTest`
Expected: FAIL (class does not exist)

- [ ] **Step 3: Implement `RouteSimilarityService.score(ourWkt, googleWkt)` via JdbcTemplate**

One query: `SELECT ST_HausdorffDistance(a::geography,b::geography) AS h, ST_Length(ST_Intersection(ST_Buffer(a::geography,50), b::geography))/NULLIF(ST_Length(b::geography),0) AS overlap`. Score = `overlap * exp(-h/500.0)` (h in meters; 500m decay). No cosine — cosine ignores point order and map projection and is meaningless on lat/lng polylines. Decode Google polyline to WKT in Java (no new dep, ~20-line decoder) before calling.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -pl backend test -Dtest=RouteSimilarityServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/service/RouteSimilarityService.java backend/src/test/java/com/zonepilot/backend/service/RouteSimilarityServiceTest.java
git commit -m "feat: hausdorff plus overlap route similarity scoring"
```

### Task 4: Wire enrichment + similarity into validateRoute response and DB

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/dto/response/RouteValidationResponse.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/entity/DispatchRoute.java`
- Create: `backend/src/main/resources/db/migration/V30__dispatch_route_google_fields.sql`
- Modify: `backend/src/main/java/com/zonepilot/backend/service/RouteComplianceService.java:59-136`

**Interfaces:**
- Consumes: `TrafficEnrichment` (Task 2) + `similarityScore` (Task 3).
- Produces: Extended JSON `{googleDurationSec, googleStaticDurationSec, googleDistanceMeters, googleDelaySec, googleSimilarityScore}` persisted on `dispatch_route`.

- [ ] **Step 1: Write failing test for enriched response**

```java
@Test void validateRouteReturnsGoogleFields() {
    RouteValidationResponse r = compliance.validateRoute(veh, oLat, oLng, dLat, dLng);
    assertNotNull(r.getGoogleDurationSec());
    assertNotNull(r.getGoogleSimilarityScore());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -pl backend test -Dtest=RouteComplianceServiceTest#validateRouteReturnsGoogleFields`
Expected: FAIL (getters do not exist)

- [ ] **Step 3: Add 5 nullable fields + getters/setters to `RouteValidationResponse` and `DispatchRoute`, plus `V30__dispatch_route_google_fields.sql` adding matching nullable columns**

Keep all nullable so missing API key leaves them null without breaking existing rows.

- [ ] **Step 4: Call enrichment once on winner geometry in `validateRoute`, set response + entity fields, save**

Best-effort try/catch — Google failure never fails validation.

- [ ] **Step 5: Run tests to verify pass**

Run: `mvn -pl backend test -Dtest=RouteComplianceServiceTest,TimePredictionServiceTest,RouteSimilarityServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/dto/response/RouteValidationResponse.java backend/src/main/java/com/zonepilot/backend/entity/DispatchRoute.java backend/src/main/resources/db/migration/V30__dispatch_route_google_fields.sql backend/src/main/java/com/zonepilot/backend/service/RouteComplianceService.java
git commit -m "feat: surface google traffic and similarity on route validation"
```
