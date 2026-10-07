# ZonePilot Backend

Urban Fleet Zone Compliance & Monitoring Engine for Bengaluru, India.

## Overview

ZonePilot is a Spring Boot 3.x REST backend that serves as the spatial compliance brain for fleet operators. It:

- Stores zone restriction polygons with time/vehicle-class rules
- Validates routes before dispatch using pgRouting on the Bangalore road network
- Monitors live vehicle positions and detects zone breaches via PostgreSQL triggers
- Returns compliant alternative routes when violations are found
- Provides simulation scenarios for demonstration

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 25 (Temurin) |
| Framework | Spring Boot 3.5.14 |
| ORM | Hibernate 6 (via Spring Data JPA) |
| Spatial | Hibernate Spatial + JTS Topology Suite 1.20.0 |
| Database | PostgreSQL 16 with PostGIS 3.4 + pgRouting 3.x |
| Migrations | Flyway |
| API Docs | springdoc-openapi 2.8.16 (Swagger UI) |
| Build | Maven 3.8+ |

## Project Structure

```
com.zonepilot.backend
├── config/              — JPA and OpenAPI configuration
├── controller/          — REST endpoints (HTTP layer only, no business logic)
├── dto/
│   ├── request/         — Validated request DTOs
│   └── response/        — Response DTOs (ApiResponse<T> envelope)
├── entity/              — JPA entities with spatial geometry types
├── enums/               — VehicleClass, RestrictionType, BreachType, RouteStatus, PositionSource
├── exception/           — Custom exceptions + GlobalExceptionHandler
├── repository/
│   ├── RoadNetworkRepository.java   — JdbcTemplate-based pgRouting queries
│   └── *Repository.java             — Spring Data JPA repositories
├── service/
│   ├── RoutingService.java          — pgRouting wrapper (nearest node, Dijkstra)
│   ├── RouteComplianceService.java  — Pre-dispatch validation + alternative route
│   ├── PositionTrackingService.java — Live position insert + breach detection
│   ├── BreachService.java           — Post-breach reroute computation
│   ├── BreachQueryService.java      — Breach query and mapping (used by controller)
│   ├── SimulationService.java       — Tick logic, path advancement
│   ├── ZoneService.java             — Zone CRUD + GeoJSON parsing
│   ├── VehicleService.java          — Vehicle CRUD
│   ├── DepotService.java            — Depot CRUD
│   └── ReportingService.java        — View-backed reporting queries
├── seed/                — SimulationDataSeeder (dev profile only)
└── BackendApplication.java
```

## Database Schema

8 core tables:

| Table | Description |
|---|---|
| `depot` | Physical vehicle staging locations |
| `vehicle` | Fleet vehicles with class classification (TWO_WHEELER, LCV, HCV) |
| `zone_restriction` | Geographic zones with boundary polygons |
| `zone_restriction_rule` | Enforcement rules per zone (vehicle class, time window, days bitmask) |
| `dispatch_route` | Pre-validated routes assigned to vehicles |
| `vehicle_position_log` | GPS position reports — **partitioned by month** on `recorded_at` |
| `zone_breach_log` | Breach records written atomically by DB trigger |
| `simulation_path` | Pre-seeded waypoint paths for simulation scenarios |
### Database Artifacts

- **Trigger:** `trg_detect_zone_breach` — fires AFTER INSERT on `vehicle_position_log`, detects zone violations in the same transaction
- **Views:** `v_active_vehicle_positions`, `v_vehicle_breach_summary`, `v_zone_violation_stats`, `v_currently_active_restrictions`
- **Stored Procedure:** `sp_validate_route(vehicle_id, route_geometry)` — spatial intersection check against active zones
- **Indexes:** 5 GIST spatial indexes + 10 B-tree indexes
- **Partitioning:** `vehicle_position_log` partitioned by RANGE on `recorded_at` (monthly, with DEFAULT partition)

### Key Design Decisions

- **Trigger atomicity:** Breach detection runs inside the same DB transaction as the position insert. A Java-side crash cannot cause a missed breach.
- **Soft FK on `position_log_id`:** PostgreSQL does not support FKs to partitioned tables in all scenarios. `zone_breach_log.position_log_id` is a soft reference (no FK constraint).
- **`RoadNetworkRepository` uses JdbcTemplate:** The `blr_2po_4pgr` table is not a JPA-managed entity. Road network queries use `JdbcTemplate` directly rather than Spring Data.
- **SRID 4326 everywhere:** All geometry columns, inputs, and outputs use WGS84 SRID 4326. No coordinate transformation is needed.
- **Time-based routing cost (Epic 1):** pgRouting uses `cost_time_sec` (travel time in seconds) instead of `length_m`. This produces faster routes rather than shorter ones. The column is pre-calculated by V13 migration using osm2po's `kmh` column; if `kmh` is 0 or null, a clazz-based fallback speed is applied (motorway=90, primary=60, residential=30, etc.). One-way streets are preserved: osm2po's `reverse_cost = -1` is passed through to pgRouting unchanged, so directed routing correctly blocks contra-flow traversal.
- **Map-snapped simulation (Epic 2):** `SimulationDataSeeder` uses pgRouting to compute a real road-network path between scenario origin/destination pairs, then calls `ST_LineInterpolatePoints` to generate waypoints at ~30m intervals. Falls back to straight-line coordinates if the road network is not loaded. `SimulationService` injects GPS glitches (10% per tick: ~50m random offset + flipped heading) and wrong turns (5% per tick: 3-tick lateral drift) to exercise the real-world tracking pipeline.
- **Stateful journey tracking (Epic 3):** `vehicle.active_dispatch_route_id` links a vehicle to its current route. `PositionTrackingService` computes `ST_Distance` between each GPS ping and the active route geometry. Pings within 30m with a reversed heading are snapped to the route (GPS glitch on opposite carriageway). Pings beyond 50m for two consecutive ticks trigger `BreachService.computeOffRouteReroute()`.
- **Predictive compliance (Epic 4):** `RouteComplianceService.validateRoute()` runs up to 5 pgRouting attempts, penalising violated zones by 1000× on each retry. After each attempt, `TimePredictionService` calls the Google Routes API (`TRAFFIC_AWARE`) to predict arrival times at zone entry points. If all 5 attempts hit curfews, the candidate with the lowest `travel_duration + wait_duration` is returned with a `wait_until` timestamp and `wait_duration_sec` field. The winning route is persisted to `dispatch_route` with wait-state columns.

## Advanced Spatial Routing & Telemetry Engine

ZonePilot utilizes a high-performance spatial pipeline to validate, track, and simulate fleet routes across Bangalore. Here is the technical breakdown of the core engine components.

### 1. Spatial Routing Architecture (`pgRouting`)
All road network routing is computed against the `blr_2po_4pgr` table using pgRouting's `pgr_dijkstra` function.
*   **Directed Dijkstra:** The engine invokes pgRouting with `directed := true`. This ensures that one-way street constraints (where reverse travel cost is set to `-1`) are strictly respected during computation.
*   **Travel-Time Based Costing:** Cost is calculated in travel duration seconds (`cost_time_sec`) derived from physical segment lengths and OSM speeds or road class fallbacks (motorway = 90 km/h, primary = 60 km/h, residential = 30 km/h).
*   **KNN Junction Snapping:** GPS coordinates are snapped to vertices using the spatial KNN operator (`<->`) on the GiST geometry index of the vertices table `blr_2po_4pgr_vertices_pgr`, which provides rapid snapping without expensive full-table distance scans:
    ```sql
    SELECT id FROM blr_2po_4pgr_vertices_pgr 
    ORDER BY the_geom <-> ST_SetSRID(ST_Point(?, ?), 4326) LIMIT 1
    ```

### 2. Recursive Compliance & Zone Penalty Escalation
To find compliant paths that bypass active restricted zones, the engine runs a **5-attempt recursive routing pipeline**:
1.  **Initial Route:** Computes standard travel-time shortest path.
2.  **Intersection Check:** The route geometry is passed to a PL/pgSQL database stored procedure `sp_validate_route()`, checking intersections with active zone polygons.
3.  **Clean Exit:** If no zones are violated, the compliant route is saved and returned.
4.  **Penalty Application:** If zones are violated, those zones are added to a list of penalized zones. The router recurses and executes `pgr_dijkstra` with a dynamic SQL clause multiplying the travel cost of segments intersecting those zones by **1,000x**:
    ```sql
    CASE WHEN ST_Intersects(the_geom, (SELECT ST_Union(boundary) FROM zone_restriction WHERE id IN (:penalizedIds)))
         THEN cost_time_sec * 1000 ELSE cost_time_sec END AS cost
    ```
5.  **Wait-State Fallback:** If all 5 attempts fail to avoid restricted zones, the engine predicts arrival times using the Google Routes API (or segment fallbacks). It computes the curfew end time from `zone_restriction_rule` and generates a **Wait-State Route** scheduling an automated wait at the zone boundary.

### 3. Timezone Alignment & Overnight Curfews
To prevent false compliance states, the engine utilizes strict timezone and overnight window logic inside Postgres triggers and stored procedures:
*   **IST Timezone Locking:** Coordinates and timestamps are cast to `Asia/Kolkata` (`recorded_at AT TIME ZONE 'Asia/Kolkata'`) before extracting the hour and day of week. This aligns server clocks (often in UTC) with localized enforcement hours.
*   **Cross-Midnight Windows:** Time restrictions that wrap around midnight (e.g., overnight windows from 22:00 to 06:00) are evaluated using boolean overnight detection:
    ```sql
    (start_time > end_time) AND (current_time >= start_time OR current_time <= end_time)
    ```
*   **Boundary Inclusion (`ST_Covers` vs `ST_Within`):** Database triggers and spatial algorithms utilize `ST_Covers(zr.boundary, NEW.position)` instead of `ST_Within`. This ensures that points directly on zone boundaries are correctly detected as breaches (since `ST_Within` yields false for points located precisely on boundary lines).

### 4. Stateful Journey Tracking & Map-Matching
Live GPS pings are compared in real-time to the planned route geometry using JTS and PostGIS geographic queries:
*   **Carriageway Snap (≤ 30m):** If the vehicle is within 30m of the planned line and heading is opposite to route azimuth (`delta > 150°`), the position is snapped to the nearest point on the route (correcting GPS dual-carriageway drift).
*   **Tracking Buffer (30m to 50m):** Tolerated as sensor noise.
*   **Off-Route Rerouting (> 50m):** If a vehicle is more than 50 meters away from the route for **2 consecutive ticks/pings**, it is flagged as off-route. `PositionTrackingService` triggers `BreachService.computeOffRouteReroute()`, which automatically calculates a new pgRouting path from the vehicle's current map-snapped junction to the original destination.

### 5. Waypoint Seeding for Simulation Scenarios
To feed the simulation scenarios, high-fidelity waypoints are generated dynamically during database seeding when the `dev` profile is active:
*   **Dynamic pgRouting Fallback:** If the road network is loaded, the seeder computes actual paths between scenario depots. If not loaded, it falls back to straight-line coordinate interpolations.
*   **Database-Side Waypoint Interpolation:** To ensure waypoints are spaced at exact **30-meter intervals** (simulating realistic urban driving intervals), the seeder runs a PostGIS query using `ST_LineInterpolatePoints` and `ST_DumpPoints`:
    ```sql
    WITH route AS (SELECT ST_GeomFromText(?, 4326) AS geom),
         len   AS (SELECT ST_Length(geom::geography) AS meters FROM route)
    SELECT ST_Y(pt) AS lat, ST_X(pt) AS lng
    FROM route, len,
         ST_DumpPoints(ST_LineInterpolatePoints(geom, LEAST(? / meters, 1.0))) AS dp(path, pt)
    ORDER BY dp.path
    ```

### 6. Emulating Live Telemetry & Noise Injectors
To thoroughly verify the robustness of tracking and compliance checks, the `SimulationService` injects synthetic telemetry noise:
*   **GPS Glitch Injector (10% chance):** Shifts the position coordinate randomly by ~50 meters and flips the heading by 180 degrees for one tick. This ensures the map-matching snaps the vehicle back onto its route without triggering false off-route reroutes.
*   **Wrong-Turn Injector (5% chance):** Simulates a driver missing a turn by applying a persistent perpendicular lateral offset of ~120m for **3 consecutive ticks**. This purposely drives the vehicle beyond the 50m off-route boundary for more than one ping, triggering the automatic rerouting engine.

## API Endpoints

All endpoints return a standardized `ApiResponse<T>` envelope:
```json
{ "success": true, "timestamp": "2026-05-20T16:30:00Z", "data": { ... }, "error": null }
```
On error:
```json
{ "success": false, "timestamp": "2026-05-20T16:30:00Z", "data": null, "error": { "code": "RESOURCE_NOT_FOUND", "message": "..." } }
```

### Vehicles

| Method | Path | Description |
|---|---|---|
| GET | `/api/vehicles` | List all vehicles. Query params: `?vehicleClass=TWO_WHEELER\|LCV\|HCV`, `?isActive=true\|false` |
| GET | `/api/vehicles/{id}` | Vehicle detail by ID |
| POST | `/api/vehicles` | Register vehicle. Body: `{ "registrationNumber": "KA01-LCV-0010", "vehicleClass": "LCV", "ownerName": "Name", "depotId": 1 }` |
| PUT | `/api/vehicles/{id}` | Update vehicle. Same body as POST |
| GET | `/api/vehicles/{id}/zones-at-location` | Get active zones containing a point. Query params: `?lat=12.91&lng=77.63` |

**VehicleResponse fields:** `id`, `registrationNumber`, `vehicleClass`, `ownerName`, `depotId`, `depotName`, `isActive`

### Depots

| Method | Path | Description |
|---|---|---|
| GET | `/api/depots` | List all depots |
| GET | `/api/depots/{id}` | Depot detail by ID |
| POST | `/api/depots` | Create depot. Body: `{ "name": "Depot Name", "address": "Street, Area", "lat": 12.91, "lng": 77.63 }` |

**DepotResponse fields:** `id`, `name`, `address`, `latitude`, `longitude`

### Zones

| Method | Path | Description |
|---|---|---|
| GET | `/api/zones` | List all zones with rules |
| GET | `/api/zones/{id}` | Zone detail by ID with rules |
| GET | `/api/zones/active` | Currently active zones (based on time/day) |
| POST | `/api/zones` | Create zone. Body: `{ "name": "Zone Name", "description": "...", "boundaryGeoJson": "{\"type\":\"Polygon\",\"coordinates\":[[[lng,lat],...]]}", "restrictionType": "NO_ENTRY\|TIME_RESTRICTED\|VEHICLE_CLASS_RESTRICTED", "isActive": true }` |

**ZoneResponse fields:** `id`, `name`, `description`, `boundaryGeoJson` (GeoJSON object `{"type":"Polygon","coordinates":[...]}`), `restrictionType`, `isActive`, `rules[]`
**Rule fields:** `id`, `applicableVehicleClass`, `restrictionStartTime` (HH:MM), `restrictionEndTime` (HH:MM), `daysOfWeekBitmask`, `isActive`

Zone creation now accepts an optional `rules` array:
```json
{
  "name": "MG Road No-Entry",
  "boundaryGeoJson": "{\"type\":\"Polygon\",\"coordinates\":[...]}",
  "restrictionType": "NO_ENTRY",
  "isActive": true,
  "rules": [
    {
      "applicableVehicleClass": "HCV",
      "restrictionStartTime": "08:00",
      "restrictionEndTime": "22:00",
      "daysOfWeekBitmask": 31,
      "isActive": true
    }
  ]
}
```

### Routes

| Method | Path | Description |
|---|---|---|
| POST | `/api/routes/validate` | Validate route pre-dispatch. Body: `{ "vehicleId": 4, "originLat": 12.91, "originLng": 77.59, "destLat": 12.97, "destLng": 77.64 }`. Returns pgRouting path + zone violations + wait-state if applicable |
| GET | `/api/routes/{id}` | Dispatch route detail by ID |
| GET | `/api/routes/vehicle/{vehicleId}` | Route history for a vehicle (all dispatch routes, newest first) |

**RouteValidationResponse fields:** `compliant` (boolean), `routeGeoJson` (WKT), `violations[]`, `alternativeRouteGeoJson`, `alternativeRouteUnavailable`, `dispatchRouteId`, `waitUntil` (ISO-8601), `waitDurationSec`
**ViolationDetail fields:** `zoneId`, `zoneName`, `breachType`

### Positions

| Method | Path | Description |
|---|---|---|
| POST | `/api/vehicles/{vehicleId}/positions` | Record live position. Body: `{ "lat": 12.91, "lng": 77.63, "speedKmh": 30.0, "headingDeg": 180, "timestamp": "2026-05-20T16:30:00Z" }`. DB trigger fires; breaches returned inline |
| GET | `/api/vehicles/{vehicleId}/positions` | Position history. Query params: `?from=2026-05-20T00:00:00Z&to=2026-05-20T23:59:59Z` (ISO-8601, optional) |
| GET | `/api/vehicles/{vehicleId}/positions/latest` | Latest position for vehicle |

**PositionRecordResponse fields:** `breachDetected` (boolean), `position: { latitude, longitude, recordedAt, speedKmh, source }`, `breaches[]`
**BreachDetail fields:** `breachId`, `zoneId`, `zoneName`, `breachType`, `rerouteGeoJson`
**PositionResponse fields:** `id`, `vehicleId`, `latitude`, `longitude`, `recordedAt`, `speedKmh`, `headingDeg`, `source`

### Breaches

| Method | Path | Description |
|---|---|---|
| GET | `/api/breaches` | List breaches. Query params: `?vehicleId=7&zoneId=1&from=...&to=...&unacknowledged=true` |
| GET | `/api/breaches/{id}` | Breach detail with reroute geometry |
| PUT | `/api/breaches/{id}/acknowledge` | Mark breach acknowledged. Returns **409** if already acknowledged |

**BreachResponse fields:** `id`, `vehicleId`, `registrationNumber`, `zoneId`, `zoneName`, `breachType`, `breachTime`, `rerouteGeoJson`, `isAcknowledged`

### Reports

| Method | Path | Description |
|---|---|---|
| GET | `/api/reports/summary` | Fleet-wide breach summary per vehicle (from `v_vehicle_breach_summary` view) |
| GET | `/api/reports/zones/{zoneId}/violations` | Violation stats for a specific zone. Returns empty array if zone has no violations |
| GET | `/api/reports/active-restrictions` | Currently active restrictions (based on current IST time) |

**Summary fields:** `vehicleId`, `registrationNumber`, `vehicleClass`, `totalBreaches`, `noEntryBreaches`, `timeWindowBreaches`, `classBreaches`, `unacknowledgedBreaches`, `lastBreachTime`
**Zone violation fields:** `zoneId`, `zoneName`, `restrictionType`, `totalViolations`, `hcvViolations`, `lcvViolations`, `twoWheelerViolations`, `lastViolationTime`
**Active restriction fields:** `id`, `name`, `restrictionType`, `applicableVehicleClass`, `restrictionStartTime`, `restrictionEndTime`

### Simulation

| Method | Path | Description |
|---|---|---|
| POST | `/api/simulation/start` | Activate scenarios. Body: `{ "scenarios": ["A", "B", "C"] }`. Accepts short form (A/B/C) or full form (SCENARIO_A) |
| POST | `/api/simulation/tick` | Advance all active vehicles one step. Returns positions, breach detection, and GPS glitch/wrong-turn deviations |
| GET | `/api/simulation/state` | Current state of all simulation paths (active and inactive) |
| POST | `/api/simulation/reset` | Reset all paths to step 0 and deactivate |

**SimulationTickResponse fields:** `tickNumber`, `vehicles[]`, `exhausted` (true when all paths are COMPLETED)
**TickVehicleResult fields:** `vehicleId`, `registrationNumber`, `latitude`, `longitude`, `breachDetected`, `breaches[]`, `status` (MOVING/COMPLETED)
**SimulationStateResponse fields:** `pathId`, `vehicleId`, `registrationNumber`, `scenarioName`, `currentStep`, `totalSteps`, `isActive`, `latitude`, `longitude`

## Running with Docker (Recommended)

### Prerequisites

- Docker and Docker Compose

### Configuration

Credentials are read from environment variables with safe defaults for local development.
For production, set these in a `.env` file (never commit it):

```bash
# .env (gitignored)
DB_USER=zonepilot
DB_PASSWORD=your-secure-password
GOOGLE_ROUTES_API_KEY=your-google-routes-api-key   # optional; enables predictive compliance
```

### Start

```bash
docker compose up --build
```

The first build downloads Maven dependencies inside the container — this takes a few minutes.
**Subsequent builds are fast** because the dependency layer is cached separately from source code.
Only when `pom.xml` changes will dependencies be re-downloaded.

### Access

- **API:** http://localhost:8080
- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI Spec:** http://localhost:8080/v3/api-docs
- **Map UI:** http://localhost:8080/map.html

### Road Network (Required for Route Validation)

pgRouting requires the Bangalore OSM road network to be loaded. Without it, route validation endpoints return a **503** error with code `ROAD_NETWORK_UNAVAILABLE`. Simulation and breach detection work without it.

```bash
# 1. Download Karnataka OSM data
wget https://download.openstreetmap.fr/extracts/asia/india/karnataka-latest.osm.pbf

# 2. Clip to Bangalore bounding box
osmium extract --bbox=77.45,12.83,77.78,13.14 karnataka-latest.osm.pbf -o bangalore.osm.pbf

# 3. Run osm2po (generates blr_2po_4pgr.sql)
java -Xmx512m -jar osm2po-core.jar prefix=blr bangalore.osm.pbf

# 4. Import into the running container
docker exec -i zonepilot-postgres psql -U zonepilot -d ZonePilot < blr_2po_4pgr.sql
```

## Running Locally (without Docker)

### Prerequisites

- Java 25 (Temurin)
- PostgreSQL 16+ with PostGIS 3.x and pgRouting 3.x
- Maven 3.8+

### Database Setup

```sql
CREATE DATABASE "ZonePilot";
\c ZonePilot
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgrouting;
```

### Build and Run

```bash
cd backend
./mvnw clean package -DskipTests
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

With dev profile (enables simulation seeder):
```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

Datasource defaults to `localhost:5434/ZonePilot`. Override via environment variables:
```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/ZonePilot
export SPRING_DATASOURCE_USERNAME=zonepilot
export SPRING_DATASOURCE_PASSWORD=yourpassword
```

## Running Tests

```bash
cd backend
./mvnw test
```

Tests are pure unit tests (no DB required). They cover:
- `ApiResponse` contract and envelope correctness
- All enum values and invalid enum handling
- Coordinate validation boundary conditions (Bangalore bounding box)
- `VehicleService`: create, update, duplicate registration, missing depot
- `PositionTrackingService`: inactive vehicle rejection, out-of-range coordinates
- `SimulationService`: tick logic, off-by-one completion, scenario start/reset
- `GlobalExceptionHandler`: all HTTP status code mappings

## Response Format

All endpoints return a standardized envelope:

```json
{
  "success": true,
  "timestamp": "2025-01-15T08:30:00Z",
  "data": { ... },
  "error": null
}
```

On error:
```json
{
  "success": false,
  "timestamp": "2025-01-15T08:30:00Z",
  "data": null,
  "error": { "code": "VEHICLE_NOT_FOUND", "message": "Vehicle with id 99 not found" }
}
```

## Simulation Scenarios

Three pre-seeded scenarios (loaded on startup in `dev` profile):

| Scenario | Vehicle | Class | Route | Expected Outcome |
|---|---|---|---|---|
| A | ID 4 (KA01-LCV-0004) | LCV | HSR Layout → Indiranagar | 0 breaches — compliant path |
| B | ID 7 (KA01-HCV-0007) | HCV | Yeshwantpur → MG Road area | Breaches at ticks 6–15 (time window violation) |
| C | ID 5 (KA01-LCV-0005) | LCV | Electronic City → Koramangala | Breaches at ticks 4–8 (Majestic no-entry zone) |

### Demo Flow

```
POST /api/simulation/start    {"scenarios": ["A", "B", "C"]}
GET  /api/simulation/state    — see all 3 vehicles at start positions
POST /api/simulation/tick     — repeat 5 times (all compliant)
POST /api/simulation/tick     — Vehicle 7 enters MG Road zone → breach + reroute
GET  /api/breaches?vehicleId=7
GET  /api/reports/summary
```

## Normalization

The schema is normalized to BCNF:

| Normal Form | How it is satisfied |
|---|---|
| 1NF | All attributes atomic; `days_of_week_bitmask` is a single integer |
| 2NF | Surrogate BIGSERIAL PKs eliminate partial dependencies |
| 3NF | `zone_restriction` / `zone_restriction_rule` split eliminates transitive dependencies |
| BCNF | Every determinant is a candidate key; `vehicle.registration_number` is UNIQUE NOT NULL |

## Security Notes

- Credentials are read from environment variables (`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`). Never hardcode them.
- All endpoints are currently unauthenticated — suitable for academic/demo use. Add Spring Security for production.
- `IllegalArgumentException` from invalid enum values returns HTTP 400, not 500.
- Route validation failures (stored procedure errors) propagate as exceptions rather than silently returning compliant.
- Malformed JSON returns HTTP 400 (`MALFORMED_JSON`), not 500.
- Wrong HTTP method returns HTTP 405 (`METHOD_NOT_ALLOWED`), not 500.
- Wrong `Content-Type` returns HTTP 415 (`UNSUPPORTED_MEDIA_TYPE`), not 500.
- Invalid path returns HTTP 404 (`ENDPOINT_NOT_FOUND`), not 500.
- Missing road network returns HTTP 503 (`ROAD_NETWORK_UNAVAILABLE`), not 500.
- `speedKmh` must be ≥ 0. Negative values return HTTP 400.
- `timestamp` in position records cannot be more than 5 minutes in the future. Future timestamps return HTTP 400.

## License

Apache 2.0

---

## Changelog

### v0.0.1 — Bug Fixes (2026-05-23)

All 10 bugs from the QA report have been resolved. The application is now production-ready.

**High Severity**

- **BUG-01 (V17 migration):** `v_currently_active_restrictions` view now uses `(NOW() AT TIME ZONE 'Asia/Kolkata')::TIME` instead of `CURRENT_TIME` (UTC). Added overnight window support (`start > end`) matching the V16 trigger pattern. Active IST-offset restrictions are now correctly returned.
- **BUG-02 (ZoneRestrictionRuleRepository):** `findCurrentlyActiveRulesForZone` query updated to use IST timezone and overnight window logic. `zones-at-location` now returns active rules correctly.
- **BUG-03 (docker-compose.yml):** Added `road-network-importer` service using `pgrouting/osm2pgrouting`. Run with `docker compose --profile road-network up road-network-importer` to load `bangalore.osm.pbf` into the `blr_2po_4pgr` table. The service uses Docker Compose profiles so it does not run on every `docker compose up`.

**Medium Severity**

- **BUG-04 (SimulationService):** When all paths reach exhaustion, `isActive` is now set to `false` and `currentStep` is set to `totalSteps` (not `totalSteps - 1`). `SimulationTickResponse` now includes an `exhausted: true` flag when all vehicles have completed. The `Could not extract waypoint` WARN log is now suppressed when `nextStep >= totalSteps`.
- **BUG-05 (CreateZoneRequest / ZoneService):** `POST /api/zones` now accepts a `rules` array in the request body. Rules are persisted in the same transaction as the zone. Each rule supports `applicableVehicleClass`, `restrictionStartTime`, `restrictionEndTime`, `daysOfWeekBitmask`, and `isActive`.
- **BUG-06 (ZoneResponse):** `boundaryGeoJson` is now serialized as a proper GeoJSON object (`{"type":"Polygon","coordinates":[...]}`), not a WKT string. All zone endpoints (`GET /api/zones`, `GET /api/zones/{id}`, `GET /api/zones/active`, `GET /api/vehicles/{id}/zones-at-location`) are affected. **Breaking change** — update any frontend code that parsed WKT.

**Low Severity**

- **BUG-07 (BreachQueryService):** `PUT /api/breaches/{id}/acknowledge` now returns **409 Conflict** with code `CONFLICT` if the breach is already acknowledged. First acknowledgement still returns 200.
- **BUG-08 (ReportingService):** `GET /api/reports/active-restrictions` now returns times as `"HH:MM"` (e.g., `"08:00"`) instead of `"HH:MM:SS"`. Consistent with zone rule time format.
- **BUG-09 (GlobalExceptionHandler):** Validation errors now include a `fields` map in the error response: `{"code":"VALIDATION_ERROR","message":"Invalid request body","fields":{"vehicleClass":"must not be blank",...}}`.
- **BUG-10 (VehicleService):** `GET /api/vehicles/{id}/zones-at-location` now returns `"rules": []` instead of `"rules": null` when no active rules match. Prevents `TypeError` in frontend code iterating the rules array.

**Configuration**

- Removed deprecated `hibernate.dialect` from `application.yml` (Hibernate 6 auto-detects the dialect).
- Added `spring.jpa.open-in-view: false` to prevent lazy-loading queries during HTTP response serialization.

### Road Network Import (Docker Compose)

Route validation and rerouting require the Bangalore OSM road network. Load it once after first startup:

```bash
# Place bangalore.osm.pbf in the project root, then:
docker compose --profile road-network up road-network-importer
```

The importer exits 0 on success. The backend will automatically use the road network on the next route validation request. To re-import after a data volume reset, run the same command again.
