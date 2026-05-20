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

## API Endpoints

### Vehicles
| Method | Path | Description |
|---|---|---|
| GET | `/api/vehicles` | List all vehicles (`?vehicleClass=HCV&isActive=true`) |
| GET | `/api/vehicles/{id}` | Vehicle detail |
| POST | `/api/vehicles` | Register vehicle |
| PUT | `/api/vehicles/{id}` | Update vehicle |

### Depots
| Method | Path | Description |
|---|---|---|
| GET | `/api/depots` | List all depots |
| GET | `/api/depots/{id}` | Depot detail |
| POST | `/api/depots` | Create depot (JSON body: `name`, `address`, `lat`, `lng`) |

### Zones
| Method | Path | Description |
|---|---|---|
| GET | `/api/zones` | List all zones |
| GET | `/api/zones/{id}` | Zone detail with rules |
| GET | `/api/zones/active` | Currently active zones |
| POST | `/api/zones` | Create zone (JSON body: `name`, `description`, `boundaryGeoJson`, `restrictionType`, `isActive`) |

### Routes
| Method | Path | Description |
|---|---|---|
| POST | `/api/routes/validate` | Validate route pre-dispatch via pgRouting + `sp_validate_route` |
| GET | `/api/routes/{id}` | Route detail |
| GET | `/api/routes/vehicle/{vehicleId}` | Route history |

### Positions
| Method | Path | Description |
|---|---|---|
| POST | `/api/vehicles/{id}/positions` | Record live position — trigger fires, breach returned in response |
| GET | `/api/vehicles/{id}/positions` | Position history (`?from=&to=` as ISO-8601) |
| GET | `/api/vehicles/{id}/positions/latest` | Latest position |

### Breaches
| Method | Path | Description |
|---|---|---|
| GET | `/api/breaches` | All breaches (`?vehicleId=&zoneId=&from=&to=&unacknowledged=true`) |
| GET | `/api/breaches/{id}` | Breach detail with reroute geometry |
| PUT | `/api/breaches/{id}/acknowledge` | Mark breach acknowledged |

### Reports
| Method | Path | Description |
|---|---|---|
| GET | `/api/reports/summary` | Fleet-wide breach summary (from `v_vehicle_breach_summary`) |
| GET | `/api/reports/zones/{id}/violations` | Zone violation stats filtered by zone ID (empty array if zone has no violations) |
| GET | `/api/reports/active-restrictions` | Currently active restrictions |

### Simulation
| Method | Path | Description |
|---|---|---|
| POST | `/api/simulation/start` | Activate scenarios (`{"scenarios": ["A", "B", "C"]}`) |
| POST | `/api/simulation/tick` | Advance all active vehicles one step |
| GET | `/api/simulation/state` | Current state of all simulation paths |
| POST | `/api/simulation/reset` | Reset all paths to step 0 |

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
