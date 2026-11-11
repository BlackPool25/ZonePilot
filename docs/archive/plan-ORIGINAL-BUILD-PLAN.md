# ZonePilot — Urban Fleet Zone Compliance Engine
### Full Software Implementation Plan (Solo | Spring Boot 3.x | PostGIS | pgRouting | Bangalore)

---

## BUILDER INSTRUCTIONS (READ FIRST)
> Before building any module, use **context7 MCP** to fetch the latest documentation for:
> - `spring-boot` (v3.x, Jakarta EE namespace)
> - `hibernate-spatial` (compatible with Spring Boot 3.x)
> - `postgis-jdbc` or `hibernate-spatial` geometry types
> - `springdoc-openapi` (for Swagger UI with Spring Boot 3.x)
> - `pgrouting` (pgr_dijkstra, pgr_withPoints function signatures)
>
> Do NOT assume API signatures from training data. Verify with context7 before writing any import or annotation.
> Build one module at a time. Do not skip to the next until the current one compiles and behaves as described.

---

## 1. PROJECT IDENTITY

**System Name:** ZonePilot  
**Tagline:** Geospatial Fleet Zone Compliance & Monitoring Engine for Urban Logistics  
**City Scope:** Bengaluru (Bangalore), Karnataka, India  
**Domain:** Urban Freight & Last-Mile Delivery Compliance

### Real-World Problem
Bengaluru Traffic Police (BTP) has mandated all delivery platforms and cab aggregators to integrate their geospatial Restricted Zone data into their systems. Heavy Commercial Vehicles (HCV) are banned from entering the city during peak hours (7am–11am, 9pm–11pm). Delivery companies have no backend system that:
- Validates a route against spatial restrictions *before* dispatch
- Detects in real time when a vehicle enters a restricted zone
- Accounts for *which vehicle class* the restriction applies to
- Accounts for *what time of day* the restriction is active
- Responds with a compliant alternative route when a violation is found

**Current Solution's Failure:** Manual driver awareness, paper-based route sheets, and discovery of violations on-site after fines are already issued.

**What ZonePilot Solves:** A REST backend system that serves as the spatial compliance brain for any fleet operator. It stores zone restriction polygons, validates routes before dispatch using pgRouting on the Bangalore road network, monitors live vehicle positions, fires DB-level triggers to detect breaches, and returns compliant reroutes.

---

## 2. TECHNOLOGY STACK (LOCKED — NO SUBSTITUTION)

| Layer | Technology | Version | Reason |
|---|---|---|---|
| Language | Java | 17+ | LTS, compatible with Spring Boot 3.x |
| Framework | Spring Boot | 3.x | Jakarta EE, modern, auto-configuration |
| Web Layer | Spring MVC | (bundled) | REST controllers, `@RestController` |
| ORM | Hibernate Spatial | (via Spring Data JPA) | JPA + spatial type support |
| Spatial DB | PostgreSQL + PostGIS | 15+ / 3.x | GEOMETRY type, ST_* functions |
| Routing | pgRouting | 3.x | pgr_dijkstra on OSM road network |
| Build | Maven | 3.8+ | Dependency management |
| API Docs | springdoc-openapi | latest | Swagger UI auto-generated |
| Geometry type | Hibernate Spatial / JTS Topology Suite | latest | Converts PostGIS geometry ↔ Java objects |
| Spatial type | `GEOMETRY(type, 4326)` — WGS84 SRID 4326 | — | City-scale, fast, standard |

### Key Maven Dependencies (verify versions with context7):
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `hibernate-spatial` (Hibernate 6 has spatial built-in via `hibernate-core`)
- `postgresql` (JDBC driver)
- `springdoc-openapi-starter-webmvc-ui`
- `org.locationtech.jts:jts-core` (for Geometry objects in Java)
- `lombok` (reduce boilerplate)

> **IMPORTANT for builder:** In Hibernate 6 (which ships with Spring Boot 3.x), spatial support is built into `hibernate-core`. You do NOT need a separate `hibernate-spatial` artifact. Use `@Column(columnDefinition = "geometry(Point,4326)")` on entity fields typed as `org.locationtech.jts.geom.Point`. Verify this with context7 before writing entity classes.

---

## 3. DATA SOURCES & SETUP TOOLS

### 3A — Road Network (for pgRouting)
**Tool:** `osm2po` (Java-based, handles PBF directly — simpler than osm2pgrouting for this setup)  
**Source:** OpenStreetMap France — download `karnataka-latest.osm.pbf`  
**URL:** `https://download.openstreetmap.fr/extracts/asia/india/`  
**Why osm2po over osm2pgrouting:** osm2po reads `.pbf` natively without conversion, generates a single `.sql` file importable directly into PostgreSQL, and produces the `ways` + `ways_vertices_pgr` tables that pgRouting needs. It is Java-based so no extra C dependencies.

**Steps:**
1. Download `south-india-latest.osm.pbf` from Geofabrik
2. Use Osmium to clip to Bangalore bounding box (12.83°N–13.14°N, 77.45°E–77.78°E):
   `osmium extract --bbox=77.45,12.83,77.78,13.14 south-india-latest.osm.pbf -o bangalore.osm.pbf`
3. Run osm2po: `java -Xmx512m -jar osm2po-core.jar prefix=blr bangalore.osm.pbf`
4. This outputs `blr_2po_4pgr.sql`
5. Import: `psql -d ZonePilot -f blr_2po_4pgr.sql`
6. This creates `blr_2po_4pgr` table with columns: `id, osm_id, osm_name, osm_meta, osm_source_id, osm_target_id, clazz, flags, length, length_m, name, x1, y1, x2, y2, geom_way, source, target`
7. Rename or create a view called `ways` with `source`, `target`, `length_m AS cost`, `geom_way AS the_geom` for pgRouting compatibility

### 3B — Restricted Zone Polygons
**Tool:** [geojson.io](https://geojson.io)  
**Why not OSM for zones:** OSM does not reliably encode traffic restriction zones for Indian cities at the polygon level. We define zones manually based on documented BTP restrictions.

**Zones to define (draw on geojson.io → export as GeoJSON → embed in SQL seed):**

| Zone Name | Area | Restriction Type | Applies To | Active Hours |
|---|---|---|---|---|
| MG Road No-Entry | MG Road corridor | TIME_RESTRICTED | HCV | 07:00–11:00, 21:00–23:00 |
| Silk Board Freight Restriction | Silk Board junction radius | VEHICLE_CLASS_RESTRICTED | HCV | 08:00–22:00 |
| Majestic Bus Terminal Perimeter | KBS/Majestic area | NO_ENTRY | ALL | Always |
| Koramangala Inner Zone | Inner Koramangala residential | TIME_RESTRICTED | LCV, HCV | 07:00–10:00 |
| Electronic City Phase 1 Entry | EC Phase 1 gate area | VEHICLE_CLASS_RESTRICTED | HCV | 06:00–09:00, 18:00–21:00 |

Draw these on geojson.io as polygons, copy the GeoJSON coordinates into SQL INSERT statements for the seed script.

### 3C — Static Seed Data (SQL scripts)
- `V1__schema.sql` — all CREATE TABLE statements with constraints
- `V2__extensions.sql` — CREATE EXTENSION postgis, CREATE EXTENSION pgrouting
- `V3__seed_depots.sql` — 3 depots with real Bangalore coordinates
- `V4__seed_vehicles.sql` — 9 vehicles (3 × 2W, 3 × LCV, 3 × HCV)
- `V5__seed_zones.sql` — 5 zone polygons (from geojson.io coordinates)
- `V6__seed_zone_rules.sql` — rules per zone (vehicle class, time window)
- `V7__triggers.sql` — breach detection trigger
- `V8__views.sql` — reporting views
- `V9__indexes.sql` — all GIST + B-tree indexes
- `V10__stored_procedures.sql` — validation stored procedures

Use **Flyway** for migration management. Add `flyway-core` to Maven. All SQL files live in `src/main/resources/db/migration/`.

### 3D — Simulation Position Data (Java DataSeeder)
A `SimulationDataSeeder.java` runs on startup (annotated with `@Component` + `CommandLineRunner`, only if `spring.profiles.active=dev`).

It creates 3 simulation paths:
- **SCENARIO_A:** LCV (Vehicle 1) — compliant path from HSR Layout depot to Koramangala, all ticks safe
- **SCENARIO_B:** HCV (Vehicle 4) — route passing through MG Road at 8am (active restriction window), breach at tick 6
- **SCENARIO_C:** LCV (Vehicle 2) — starts compliant, deviates into Majestic No-Entry zone at tick 4

Each path is stored as a `LINESTRING` of 15–20 waypoints in `simulation_path` table.

---

## 4. DATABASE DESIGN

### 4A — Full Schema (All Tables)

---

#### TABLE: `depot`
Stores physical locations where vehicles are stationed.
```sql
CREATE TABLE depot (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    address     TEXT NOT NULL,
    location    GEOMETRY(POINT, 4326) NOT NULL,  -- centroid of depot
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
**Why:** Depot is an independent entity. Vehicles belong to depots. Separating it avoids repeating address data on every vehicle (2NF compliance).

---

#### TABLE: `vehicle`
Fleet vehicles with class classification.
```sql
CREATE TABLE vehicle (
    id                   BIGSERIAL PRIMARY KEY,
    registration_number  VARCHAR(20) NOT NULL UNIQUE,
    vehicle_class        VARCHAR(20) NOT NULL CHECK (vehicle_class IN ('TWO_WHEELER','LCV','HCV')),
    owner_name           VARCHAR(100) NOT NULL,
    depot_id             BIGINT NOT NULL REFERENCES depot(id) ON DELETE RESTRICT,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
**Why `vehicle_class` matters:** Zone restrictions apply differently per class. `REFERENCES depot(id) ON DELETE RESTRICT` ensures you cannot delete a depot that still has vehicles — referential integrity.

---

#### TABLE: `zone_restriction`
Geographic zone with a defined boundary polygon. Only stores the *what* and *where*, not the *rules*.
```sql
CREATE TABLE zone_restriction (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(150) NOT NULL,
    description      TEXT,
    boundary         GEOMETRY(POLYGON, 4326) NOT NULL,
    restriction_type VARCHAR(30) NOT NULL CHECK (restriction_type IN ('NO_ENTRY','TIME_RESTRICTED','VEHICLE_CLASS_RESTRICTED')),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
**Why separate from rules:** A zone can have multiple rules (e.g., restricted for HCV from 7-11am AND for all vehicles after 9pm). Storing rules inline would break normalization. Separating them achieves 3NF.

---

#### TABLE: `zone_restriction_rule`
Defines the specific enforcement rules for a zone. One zone → many rules.
```sql
CREATE TABLE zone_restriction_rule (
    id                       BIGSERIAL PRIMARY KEY,
    zone_id                  BIGINT NOT NULL REFERENCES zone_restriction(id) ON DELETE CASCADE,
    applicable_vehicle_class VARCHAR(20) CHECK (applicable_vehicle_class IN ('TWO_WHEELER','LCV','HCV','ALL')),
    restriction_start_time   TIME,      -- NULL = applies all day
    restriction_end_time     TIME,      -- NULL = applies all day
    days_of_week_bitmask     SMALLINT NOT NULL DEFAULT 127, -- 127 = all 7 days (Mon=1,Tue=2,Wed=4...)
    is_active                BOOLEAN NOT NULL DEFAULT TRUE
);
```
**Why bitmask for days:** Efficient storage, single integer column, bitwise AND to check if current day is restricted. `127 = 1111111` in binary = all 7 days active. This avoids a join table for days while remaining in 3NF because days_of_week_bitmask is a single atomic attribute of the rule.

**Null handling:** If `restriction_start_time` IS NULL AND `restriction_end_time` IS NULL, the restriction applies 24/7. Application logic must handle this: treat NULL times as always-restricted.

---

#### TABLE: `dispatch_route`
A route assigned to a vehicle, pre-validated before dispatch.
```sql
CREATE TABLE dispatch_route (
    id                      BIGSERIAL PRIMARY KEY,
    vehicle_id              BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE RESTRICT,
    origin_point            GEOMETRY(POINT, 4326) NOT NULL,
    destination_point       GEOMETRY(POINT, 4326) NOT NULL,
    planned_route_geometry  GEOMETRY(LINESTRING, 4326),   -- set after pgRouting call
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                               CHECK (status IN ('PENDING','COMPLIANT','NON_COMPLIANT','ACTIVE','COMPLETED')),
    validation_timestamp    TIMESTAMPTZ,
    dispatched_at           TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
**Why store route geometry:** To show on map, to compare against later live positions, and to demonstrate `ST_Intersects` with zone polygons during EXPLAIN ANALYZE.

---

#### TABLE: `vehicle_position_log` *(PARTITIONED)*
Every GPS position report from every vehicle. This table will be the largest and must be partitioned.
```sql
CREATE TABLE vehicle_position_log (
    id           BIGSERIAL,
    vehicle_id   BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    position     GEOMETRY(POINT, 4326) NOT NULL,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    speed_kmh    NUMERIC(5,2),
    heading_deg  SMALLINT,
    source       VARCHAR(10) NOT NULL DEFAULT 'LIVE'
                    CHECK (source IN ('LIVE','SIMULATED')),
    PRIMARY KEY (id, recorded_at)  -- compound PK required for partitioned tables
) PARTITION BY RANGE (recorded_at);

-- Create monthly partitions (create as many as needed for demo)
CREATE TABLE vehicle_position_log_2025_01
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');

CREATE TABLE vehicle_position_log_2025_02
    PARTITION OF vehicle_position_log
    FOR VALUES FROM ('2025-02-01') TO ('2025-03-01');

-- Add more months as needed. Always have a DEFAULT partition:
CREATE TABLE vehicle_position_log_default
    PARTITION OF vehicle_position_log DEFAULT;
```
**Why partition by `recorded_at`:** Time-range queries (e.g., "all positions for vehicle 1 in January") only scan the relevant partition, dramatically reducing I/O. This is a textbook DBMS optimization demonstration.

**Why compound PK:** PostgreSQL requires the partition key column to be part of the primary key in declarative partitioning.

---

#### TABLE: `zone_breach_log`
Written by the DB trigger when a breach is detected. Never written by application code directly.
```sql
CREATE TABLE zone_breach_log (
    id                      BIGSERIAL PRIMARY KEY,
    vehicle_id              BIGINT NOT NULL REFERENCES vehicle(id),
    zone_id                 BIGINT NOT NULL REFERENCES zone_restriction(id),
    position_log_id         BIGINT NOT NULL,  -- soft reference (partitioned table FK complex)
    breach_time             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    breach_type             VARCHAR(30) NOT NULL
                               CHECK (breach_type IN ('NO_ENTRY','TIME_WINDOW','VEHICLE_CLASS')),
    rule_id                 BIGINT REFERENCES zone_restriction_rule(id),
    resolved_route_geometry GEOMETRY(LINESTRING, 4326),  -- populated asynchronously by service
    is_acknowledged         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
**Why not FK on position_log_id:** PostgreSQL does not support FKs to partitioned tables in all scenarios. Use a soft reference (store the ID, no FK constraint). Document this as a deliberate trade-off for the academic submission.

---

#### TABLE: `simulation_path`
Pre-seeded waypoint paths for simulation scenarios.
```sql
CREATE TABLE simulation_path (
    id                    BIGSERIAL PRIMARY KEY,
    vehicle_id            BIGINT NOT NULL REFERENCES vehicle(id),
    scenario_name         VARCHAR(100) NOT NULL,
    waypoints             GEOMETRY(LINESTRING, 4326) NOT NULL,  -- ordered lat/lng path
    current_step_index    INTEGER NOT NULL DEFAULT 0,
    total_steps           INTEGER NOT NULL,
    is_active             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
**How waypoints work:** Each point in the LINESTRING is one tick's position. `ST_PointN(waypoints, current_step_index + 1)` extracts the current position. Service increments `current_step_index` on each tick call.

---

### 4B — Normalization Analysis (Required for Academic Submission)

**1NF:** Every attribute in every table is atomic. There are no repeating groups. `days_of_week_bitmask` is a single integer (atomic). No array columns that would violate 1NF are used.

**2NF:** All tables use surrogate BIGSERIAL primary keys, so no partial dependency on a composite PK is possible. Every non-key attribute depends on the whole key. ✓

**3NF:** No transitive dependencies exist:
- `vehicle` — `owner_name`, `vehicle_class`, `is_active` all depend directly on `vehicle.id`, not on `depot_id`. Owner is a vehicle attribute, not a depot attribute. ✓
- `zone_restriction_rule` — all columns depend on `rule.id`, not transitively through `zone_id`. Zone name/boundary live in `zone_restriction`. ✓
- `dispatch_route` — vehicle registration number is not stored here; only `vehicle_id` FK. Avoids transitive dependency vehicle_class → restrictions. ✓

**BCNF:** Every determinant is a candidate key. `vehicle.registration_number` is a UNIQUE NOT NULL column (candidate key) that determines all other vehicle attributes — this satisfies BCNF. No hidden functional dependencies exist.

**Key Normalization Decision to Explain:** The split between `zone_restriction` and `zone_restriction_rule` is the most important normalization choice. Without it, you'd have columns like `applicable_class_1`, `start_time_1`, `applicable_class_2`, `start_time_2` — violating 1NF. With the split, each rule row is fully dependent on its own PK. ✓

---

### 4C — Indexes

```sql
-- SPATIAL INDEXES (GIST) — enable fast ST_Within, ST_Intersects, ST_DWithin
CREATE INDEX idx_gist_vehicle_position_log_position ON vehicle_position_log USING GIST(position);
CREATE INDEX idx_gist_zone_restriction_boundary ON zone_restriction USING GIST(boundary);
CREATE INDEX idx_gist_dispatch_route_geometry ON dispatch_route USING GIST(planned_route_geometry);
CREATE INDEX idx_gist_depot_location ON depot USING GIST(location);
CREATE INDEX idx_gist_simulation_path_waypoints ON simulation_path USING GIST(waypoints);

-- B-TREE INDEXES — enable fast filtering and JOINs
CREATE INDEX idx_btree_position_log_vehicle_id ON vehicle_position_log(vehicle_id);
CREATE INDEX idx_btree_position_log_recorded_at ON vehicle_position_log(recorded_at DESC);
CREATE INDEX idx_btree_breach_log_vehicle_id ON zone_breach_log(vehicle_id);
CREATE INDEX idx_btree_breach_log_breach_time ON zone_breach_log(breach_time DESC);
CREATE INDEX idx_btree_breach_log_zone_id ON zone_breach_log(zone_id);
CREATE INDEX idx_btree_vehicle_class ON vehicle(vehicle_class);
CREATE INDEX idx_btree_zone_rule_zone_id ON zone_restriction_rule(zone_id);
CREATE INDEX idx_btree_zone_rule_is_active ON zone_restriction_rule(is_active);
CREATE INDEX idx_btree_dispatch_route_vehicle ON dispatch_route(vehicle_id);
CREATE INDEX idx_btree_dispatch_route_status ON dispatch_route(status);
```

**For EXPLAIN ANALYZE demo:** Run a spatial query before and after creating the GIST index. Show the difference between Sequential Scan and Index Scan (GIST). This is the clearest DBMS optimization demonstration possible.

---

### 4D — PostgreSQL Trigger (Breach Detection)

```sql
-- TRIGGER FUNCTION
CREATE OR REPLACE FUNCTION fn_detect_zone_breach()
RETURNS TRIGGER AS $$
DECLARE
    v_vehicle_class VARCHAR(20);
    v_day_bit       SMALLINT;
    v_current_time  TIME;
    r_zone          RECORD;
BEGIN
    -- Get vehicle class for the incoming position
    SELECT vehicle_class INTO v_vehicle_class
    FROM vehicle WHERE id = NEW.vehicle_id;

    -- Get current day bitmask (Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64)
    v_day_bit := (1 << (EXTRACT(DOW FROM NEW.recorded_at)::INT % 7));
    v_current_time := NEW.recorded_at::TIME;

    -- Find any active zone that contains this point AND matches vehicle class AND time window
    FOR r_zone IN
        SELECT zr.id AS zone_id, zrr.id AS rule_id, zr.restriction_type
        FROM zone_restriction zr
        JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
        WHERE zr.is_active = TRUE
          AND zrr.is_active = TRUE
          AND ST_Within(NEW.position, zr.boundary)   -- spatial check: point inside polygon
          AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = v_vehicle_class)
          AND (zrr.days_of_week_bitmask & v_day_bit) > 0  -- bitwise: is today in restriction days?
          AND (
               (zrr.restriction_start_time IS NULL AND zrr.restriction_end_time IS NULL)  -- 24/7
               OR (v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
          )
    LOOP
        -- Insert breach record for each matching zone
        INSERT INTO zone_breach_log (
            vehicle_id, zone_id, position_log_id,
            breach_time, breach_type, rule_id
        ) VALUES (
            NEW.vehicle_id,
            r_zone.zone_id,
            NEW.id,
            NEW.recorded_at,
            CASE r_zone.restriction_type
                WHEN 'NO_ENTRY' THEN 'NO_ENTRY'
                WHEN 'TIME_RESTRICTED' THEN 'TIME_WINDOW'
                ELSE 'VEHICLE_CLASS'
            END,
            r_zone.rule_id
        );
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ATTACH TRIGGER
CREATE TRIGGER trg_detect_zone_breach
AFTER INSERT ON vehicle_position_log
FOR EACH ROW
EXECUTE FUNCTION fn_detect_zone_breach();
```

**Why trigger, not application logic:** The breach detection must be atomic with the position insert. If it were in Java, a crash between the insert and the detection call would silently miss a breach. The DB trigger guarantees breach detection runs in the same transaction as the position insert. This is a textbook argument for database-layer business logic when correctness > flexibility.

**Edge case the trigger handles:**
- Vehicle class doesn't match rule → skipped
- Current time outside restriction window → skipped
- Zone is inactive → skipped
- Multiple zones matched → multiple rows inserted (correctly)
- NULL time window → treated as 24/7 restriction

---

### 4E — Views (Reporting)

```sql
-- VIEW 1: Latest position per vehicle (for map display)
CREATE OR REPLACE VIEW v_active_vehicle_positions AS
SELECT DISTINCT ON (v.id)
    v.id AS vehicle_id,
    v.registration_number,
    v.vehicle_class,
    d.name AS depot_name,
    vpl.position,
    ST_X(vpl.position) AS longitude,
    ST_Y(vpl.position) AS latitude,
    vpl.recorded_at,
    vpl.speed_kmh,
    vpl.source
FROM vehicle v
JOIN depot d ON d.id = v.depot_id
LEFT JOIN vehicle_position_log vpl ON vpl.vehicle_id = v.id
WHERE v.is_active = TRUE
ORDER BY v.id, vpl.recorded_at DESC;

-- VIEW 2: Breach summary per vehicle
CREATE OR REPLACE VIEW v_vehicle_breach_summary AS
SELECT
    v.id AS vehicle_id,
    v.registration_number,
    v.vehicle_class,
    COUNT(zbl.id) AS total_breaches,
    COUNT(CASE WHEN zbl.breach_type = 'NO_ENTRY' THEN 1 END) AS no_entry_breaches,
    COUNT(CASE WHEN zbl.breach_type = 'TIME_WINDOW' THEN 1 END) AS time_window_breaches,
    COUNT(CASE WHEN zbl.breach_type = 'VEHICLE_CLASS' THEN 1 END) AS class_breaches,
    MAX(zbl.breach_time) AS last_breach_time,
    COUNT(CASE WHEN zbl.is_acknowledged = FALSE THEN 1 END) AS unacknowledged_breaches
FROM vehicle v
LEFT JOIN zone_breach_log zbl ON zbl.vehicle_id = v.id
GROUP BY v.id, v.registration_number, v.vehicle_class;

-- VIEW 3: Violation stats per zone
CREATE OR REPLACE VIEW v_zone_violation_stats AS
SELECT
    zr.id AS zone_id,
    zr.name AS zone_name,
    zr.restriction_type,
    COUNT(zbl.id) AS total_violations,
    COUNT(CASE WHEN v.vehicle_class = 'HCV' THEN 1 END) AS hcv_violations,
    COUNT(CASE WHEN v.vehicle_class = 'LCV' THEN 1 END) AS lcv_violations,
    COUNT(CASE WHEN v.vehicle_class = 'TWO_WHEELER' THEN 1 END) AS two_wheeler_violations,
    MAX(zbl.breach_time) AS last_violation_time
FROM zone_restriction zr
LEFT JOIN zone_breach_log zbl ON zbl.zone_id = zr.id
LEFT JOIN vehicle v ON v.id = zbl.vehicle_id
GROUP BY zr.id, zr.name, zr.restriction_type;

-- VIEW 4: Currently active restrictions (zones active right now)
CREATE OR REPLACE VIEW v_currently_active_restrictions AS
SELECT
    zr.id, zr.name, zr.boundary, zr.restriction_type,
    zrr.applicable_vehicle_class,
    zrr.restriction_start_time, zrr.restriction_end_time
FROM zone_restriction zr
JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
WHERE zr.is_active = TRUE
  AND zrr.is_active = TRUE
  AND (
      (zrr.restriction_start_time IS NULL)
      OR (CURRENT_TIME BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time)
  )
  AND (zrr.days_of_week_bitmask & (1 << (EXTRACT(DOW FROM NOW())::INT % 7))) > 0;
```

---

### 4F — Stored Procedures

```sql
-- STORED PROCEDURE: Validate if a route geometry intersects any active restricted zone
CREATE OR REPLACE FUNCTION sp_validate_route(
    p_vehicle_id      BIGINT,
    p_route_geometry  GEOMETRY(LINESTRING, 4326)
)
RETURNS TABLE (
    is_compliant         BOOLEAN,
    violated_zone_id     BIGINT,
    violated_zone_name   VARCHAR,
    breach_type          VARCHAR
) AS $$
DECLARE
    v_vehicle_class VARCHAR(20);
    v_current_time  TIME := CURRENT_TIME;
    v_day_bit       SMALLINT := (1 << (EXTRACT(DOW FROM NOW())::INT % 7));
BEGIN
    SELECT vehicle_class INTO v_vehicle_class FROM vehicle WHERE id = p_vehicle_id;

    RETURN QUERY
    SELECT
        FALSE AS is_compliant,
        zr.id AS violated_zone_id,
        zr.name AS violated_zone_name,
        zr.restriction_type AS breach_type
    FROM zone_restriction zr
    JOIN zone_restriction_rule zrr ON zrr.zone_id = zr.id
    WHERE zr.is_active = TRUE
      AND zrr.is_active = TRUE
      AND ST_Intersects(p_route_geometry, zr.boundary)  -- route crosses zone
      AND (zrr.applicable_vehicle_class = 'ALL' OR zrr.applicable_vehicle_class = v_vehicle_class)
      AND (zrr.days_of_week_bitmask & v_day_bit) > 0
      AND (
          zrr.restriction_start_time IS NULL
          OR v_current_time BETWEEN zrr.restriction_start_time AND zrr.restriction_end_time
      )
    LIMIT 1;  -- Return first violation found. If no rows returned → compliant.
END;
$$ LANGUAGE plpgsql;
```

**Why stored procedures for DBMS subject:** Stored procedures encapsulate complex multi-table logic at the DB layer, reducing round trips. The `sp_validate_route` procedure is called from the Spring service layer via a native query, demonstrating the procedure and the spatial function (`ST_Intersects`) together.

---

## 5. SYSTEM ARCHITECTURE

### 5A — Layered Architecture (Strict — No Layer Skipping)

```
┌──────────────────────────────────────────────────────┐
│                   PRESENTATION LAYER                 │
│  Spring MVC @RestController — HTTP in/out only       │
│  Validates input DTOs. No business logic here.       │
│  Returns ResponseEntity<ApiResponse<T>> always.      │
└────────────────────────┬─────────────────────────────┘
                         │ calls
┌────────────────────────▼─────────────────────────────┐
│                   SERVICE LAYER                      │
│  @Service — all business logic lives here            │
│  Orchestrates repositories, spatial logic, routing   │
│  Manages @Transactional boundaries                   │
│  Never returns entities — converts to DTOs           │
└────────────────────────┬─────────────────────────────┘
                         │ calls
┌────────────────────────▼─────────────────────────────┐
│                  REPOSITORY LAYER                    │
│  Spring Data JPA @Repository                         │
│  Native @Query for all spatial SQL                   │
│  Entity ↔ DB mapping via Hibernate Spatial           │
└────────────────────────┬─────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────┐
│              POSTGRESQL + POSTGIS + PGROUTING        │
│  Triggers, Views, Stored Procedures, Partitions      │
│  GIST spatial indexes, B-tree indexes                │
└──────────────────────────────────────────────────────┘
```

### 5B — Package Structure
```
com.ZonePilot
├── config/
│   ├── SwaggerConfig.java           — OpenAPI bean, group definitions
│   └── JpaConfig.java               — datasource, Hibernate dialect config
├── controller/
│   ├── VehicleController.java
│   ├── DepotController.java
│   ├── ZoneController.java
│   ├── RouteController.java
│   ├── PositionController.java
│   ├── BreachController.java
│   ├── ReportController.java
│   └── SimulationController.java
├── service/
│   ├── VehicleService.java
│   ├── ZoneService.java
│   ├── RouteComplianceService.java  — pre-dispatch validation + pgRouting
│   ├── PositionTrackingService.java — live position insert + breach read
│   ├── BreachService.java           — breach query + reroute trigger
│   ├── RoutingService.java          — pgRouting pgr_dijkstra wrapper
│   ├── ReportingService.java        — view-backed reporting queries
│   └── SimulationService.java       — tick logic, path advancement
├── repository/
│   ├── VehicleRepository.java
│   ├── DepotRepository.java
│   ├── ZoneRestrictionRepository.java
│   ├── ZoneRestrictionRuleRepository.java
│   ├── DispatchRouteRepository.java
│   ├── VehiclePositionLogRepository.java
│   ├── ZoneBreachLogRepository.java
│   └── SimulationPathRepository.java
├── entity/
│   ├── Vehicle.java
│   ├── Depot.java
│   ├── ZoneRestriction.java
│   ├── ZoneRestrictionRule.java
│   ├── DispatchRoute.java
│   ├── VehiclePositionLog.java
│   ├── ZoneBreachLog.java
│   └── SimulationPath.java
├── dto/
│   ├── request/
│   │   ├── CreateVehicleRequest.java
│   │   ├── CreateZoneRequest.java       — accepts boundary as GeoJSON string
│   │   ├── ValidateRouteRequest.java    — vehicleId, originLat, originLng, destLat, destLng
│   │   └── RecordPositionRequest.java   — lat, lng, timestamp, speedKmh
│   └── response/
│       ├── ApiResponse.java             — wrapper: {success, data, error, timestamp}
│       ├── VehicleResponse.java
│       ├── ZoneResponse.java            — includes boundary as GeoJSON
│       ├── RouteValidationResponse.java — {compliant, route GeoJSON, violations, alternateRoute}
│       ├── PositionRecordResponse.java  — {breachDetected, breachDetails, position}
│       └── BreachResponse.java
├── exception/
│   ├── GlobalExceptionHandler.java      — @RestControllerAdvice, standardized errors
│   ├── ResourceNotFoundException.java
│   ├── ValidationException.java
│   ├── RoutingException.java            — pgRouting call failure
│   └── SimulationException.java
├── enums/
│   ├── VehicleClass.java
│   ├── RestrictionType.java
│   ├── BreachType.java
│   ├── RouteStatus.java
│   └── PositionSource.java
└── seed/
    ├── FlywayConfig.java                — explicit Flyway config if needed
    └── SimulationDataSeeder.java        — CommandLineRunner, only on 'dev' profile
```

---

## 6. API DESIGN (All Endpoints)

### Standard Response Envelope (all endpoints return this):
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

### Endpoints:

| Method | Path | Description | Key Logic |
|---|---|---|---|
| GET | `/api/vehicles` | List all vehicles | Filter by `?vehicleClass=HCV&isActive=true` |
| GET | `/api/vehicles/{id}` | Vehicle detail | 404 if not found |
| POST | `/api/vehicles` | Register vehicle | Validates depot exists |
| PUT | `/api/vehicles/{id}` | Update vehicle | Validate vehicle class enum |
| GET | `/api/depots` | List all depots | Includes location GeoJSON |
| POST | `/api/depots` | Create depot | Accepts lat/lng, stores as POINT |
| GET | `/api/zones` | List all zones | Includes boundary as GeoJSON |
| GET | `/api/zones/{id}` | Zone detail with rules | |
| POST | `/api/zones` | Create zone | Accepts boundary as GeoJSON string, parses to geometry |
| GET | `/api/zones/active` | Currently active zones | Uses `v_currently_active_restrictions` view |
| POST | `/api/routes/validate` | Validate route pre-dispatch | Calls pgRouting + `sp_validate_route` + saves DispatchRoute |
| GET | `/api/routes/{id}` | Route detail | Includes geometry as GeoJSON |
| GET | `/api/routes/vehicle/{vehicleId}` | Route history | |
| POST | `/api/vehicles/{id}/positions` | Record live position | Inserts to DB → trigger fires → reads breach → returns breach status |
| GET | `/api/vehicles/{id}/positions` | Position history | Filter by `?from=&to=` date range |
| GET | `/api/vehicles/{id}/positions/latest` | Latest position | Uses view |
| GET | `/api/breaches` | All breaches | Filter by `?vehicleId=&zoneId=&from=&to=&unacknowledged=true` |
| GET | `/api/breaches/{id}` | Breach detail | Includes reroute geometry if available |
| PUT | `/api/breaches/{id}/acknowledge` | Mark breach acknowledged | |
| GET | `/api/reports/summary` | Fleet-wide breach summary | Uses `v_vehicle_breach_summary` view |
| GET | `/api/reports/zones/{id}/violations` | Zone violation stats | Uses `v_zone_violation_stats` view |
| GET | `/api/reports/active-restrictions` | Current active zones | Uses `v_currently_active_restrictions` view |
| POST | `/api/simulation/start` | Activate simulation paths | Sets `is_active=true` for chosen scenario |
| POST | `/api/simulation/tick` | Advance all vehicles one step | Returns all new positions + any breaches |
| GET | `/api/simulation/state` | Current state of all sim vehicles | |
| POST | `/api/simulation/reset` | Reset all paths to step 0 | |

---

## 7. COMPLETE DATA FLOW

### Flow 1 — Pre-Dispatch Route Validation
```
POST /api/routes/validate
Body: { vehicleId: 4, originLat: 12.912, originLng: 77.593, destLat: 12.972, destLng: 77.641 }

1. Controller receives request → validates: vehicleId > 0, lat/lng in valid range
2. Service: load vehicle → get vehicle_class = 'HCV'
3. Service → RoutingService: find nearest pgRouting node to origin point
   SQL: SELECT id FROM blr_2po_4pgr_vertices ORDER BY the_geom <-> ST_SetSRID(ST_Point(77.593,12.912),4326) LIMIT 1
4. RoutingService: same for destination node
5. RoutingService: call pgRouting
   SQL: SELECT * FROM pgr_dijkstra('SELECT id,source,target,length_m AS cost,length_m AS reverse_cost FROM ways', sourceId, targetId, directed:=true)
6. RoutingService: reconstruct route geometry from returned edges
   SQL: SELECT ST_Collect(the_geom ORDER BY path_seq) FROM ways JOIN pgr_result ON ways.id = pgr_result.edge
7. Service → RouteComplianceService: call stored procedure
   SQL: SELECT * FROM sp_validate_route(4, <route_linestring>)
8. If procedure returns rows → NON_COMPLIANT
   a. Extract violated zone geometries
   b. Call pgRouting again using pgr_withPoints or add high cost to edges inside zone polygon
   c. Get alternative route geometry
   d. Save DispatchRoute with status=NON_COMPLIANT, planned_route_geometry=alternative route
   e. Return { compliant: false, violations: [...], alternativeRoute: {GeoJSON} }
9. If procedure returns no rows → COMPLIANT
   a. Save DispatchRoute with status=COMPLIANT, planned_route_geometry=route
   b. Return { compliant: true, route: {GeoJSON}, violations: [] }
```

### Flow 2 — Live Position Recording & Breach Detection
```
POST /api/vehicles/4/positions
Body: { lat: 12.972, lng: 77.621, timestamp: "2025-01-15T08:30:00Z", speedKmh: 45.5 }

1. Controller: validate vehicleId exists, lat/lng range valid, timestamp not in future
2. Service → PositionTrackingService.recordPosition()
3. @Transactional: build VehiclePositionLog entity, call repository.save()
4. Hibernate executes: INSERT INTO vehicle_position_log (vehicle_id, position, recorded_at, ...) VALUES (...)
5. PostgreSQL TRIGGER fires immediately (same transaction):
   - fn_detect_zone_breach() runs
   - Checks ST_Within(new_position, each zone boundary) AND vehicle class + time window match
   - If match found: INSERT INTO zone_breach_log (vehicle_id, zone_id, ...)
6. Transaction commits — both position log AND breach log (if any) committed atomically
7. Service: after save, query zone_breach_log WHERE position_log_id = new position's id
   This tells us if trigger detected any breach
8. If breach found:
   a. BreachService.computeReroute(vehicleId, currentPosition, originalDestination) called
   b. pgRouting finds path from current position avoiding zone
   c. UPDATE zone_breach_log SET resolved_route_geometry = <linestring> WHERE id = breach_id
9. Build response:
   { breachDetected: true/false, position: {lat,lng,recordedAt}, breach: {zoneId, zoneName, type, reroute: GeoJSON} }
```

### Flow 3 — Simulation Tick
```
POST /api/simulation/tick

1. SimulationService: load all SimulationPath WHERE is_active = TRUE
2. For each path:
   a. Extract next waypoint: SELECT ST_AsText(ST_PointN(waypoints, current_step_index + 1))
   b. If current_step_index >= total_steps → mark simulation complete, skip
   c. Internally call PositionTrackingService.recordPosition(vehicleId, nextWaypoint, source=SIMULATED)
   d. This triggers the full Flow 2 above
   e. Increment current_step_index: UPDATE simulation_path SET current_step_index = current_step_index + 1
3. Collect results: array of {vehicleId, newPosition, breachDetected, breachDetails}
4. Return all results in single response for map to consume
```

### Flow 4 — Reporting
```
GET /api/reports/summary

1. ReportingService queries v_vehicle_breach_summary view (no computation, pre-aggregated)
2. ReportingService queries v_zone_violation_stats view
3. Returns combined summary DTO
```

---

## 8. BUSINESS LOGIC RULES (Non-Negotiable)

1. **A zone restriction rule with NULL start/end time = 24/7 restriction.** Never treat NULL times as "inactive."
2. **Vehicle class matching is strict.** A rule for 'HCV' does NOT apply to 'LCV'. A rule for 'ALL' applies to every class.
3. **Pre-dispatch validation uses the current server time.** If dispatching at 6:55am for a route through an HCV zone active from 7am, it passes. The live tracking will catch the breach if the vehicle is delayed.
4. **pgRouting always uses directed graph** (`directed := true`) to respect one-way streets in Bangalore.
5. **Nearest-node lookup uses `<->` operator** (KNN on GIST index), not ST_Distance with ORDER BY. The `<->` operator uses the spatial index; `ST_Distance` with ORDER BY does not.
6. **The position insert and breach insert are atomic.** They both happen in the same DB transaction via the trigger. The application never manually inserts into `zone_breach_log`.
7. **Rerouting is computed after breach detection, not inside the trigger.** The trigger only detects and logs. The Spring service then computes the reroute asynchronously and updates the breach log. This keeps the trigger fast.
8. **Simulation ticks use source='SIMULATED'.** Real positions use source='LIVE'. The breach trigger does not differentiate — it fires for both. This is intentional: simulation demonstrates the same detection logic.
9. **All GeoJSON input** (zone boundaries, route display) uses SRID 4326. All DB storage uses SRID 4326. No coordinate transformation needed.
10. **Empty partition range** (positions outside any defined month partition) fall into the DEFAULT partition. This must always exist.

---

## 9. ERROR HANDLING & EDGE CASES

### GlobalExceptionHandler must handle:
- `ResourceNotFoundException` → 404 with `RESOURCE_NOT_FOUND` error code
- `ConstraintViolationException` (JPA) → 400 with field-level error messages
- `DataIntegrityViolationException` → 409 CONFLICT (e.g., duplicate registration_number)
- `RoutingException` (custom) → 503 when pgRouting returns empty result (road network gap)
- `MethodArgumentNotValidException` → 400 with validation errors per field
- All uncaught → 500 with generic message, log full stack trace

### Specific Edge Cases to Handle:
- **pgRouting returns no path** (origin/destination unreachable on road network): Return 422 with message "No road network path found between these points. Check coordinates are within Bangalore."
- **Zone boundary GeoJSON is invalid** (self-intersecting, wrong winding): Validate before insert using `ST_IsValid(geometry)`. Return 400 if invalid.
- **Vehicle not active**: Return 400 "Vehicle is inactive and cannot record positions."
- **Position coordinates outside Bangalore bounding box**: Validate lat 12.8–13.2, lng 77.4–77.8. Return 400.
- **Simulation tick called when no active path**: Return 200 with empty array and message "No active simulation paths. Call /simulation/start first."
- **Simulation path exhausted** (current_step_index >= total_steps): Set is_active=false, include in response as {status: 'COMPLETED'}.
- **Duplicate position for same vehicle within 1 second**: Accept it. Do not deduplicate. Log a WARN.
- **Zone overlap** (position inside two zones simultaneously): Trigger inserts two breach rows — one per zone. This is correct behavior. Response includes array of breaches.

---

## 10. SIMULATION DESIGN (Three Scenarios)

### Setup: Depots (3 real Bangalore locations)
- **HSR Layout Depot:** (12.9116° N, 77.6389° E)
- **Yeshwantpur Depot:** (13.0241° N, 77.5535° E)
- **Electronic City Depot:** (12.8399° N, 77.6770° E)

### Scenario A — Compliant Route (Vehicle 1, LCV, from HSR)
- 15 waypoints from HSR Layout → Indiranagar
- Path stays on outer ring road, avoids all zones
- Expected: 15 ticks, 0 breaches

### Scenario B — Time Window Violation (Vehicle 4, HCV, from Yeshwantpur)
- 15 waypoints from Yeshwantpur → Majestic area
- Waypoints 1–5: outside all zones
- Waypoint 6: enters MG Road restricted zone (active for HCV 7am–11am)
- Waypoints 7–15: continue through zone
- Expected: Breaches at ticks 6–15, first breach triggers reroute suggestion

### Scenario C — No-Entry Zone Deviation (Vehicle 7, LCV, from Electronic City)
- 15 waypoints from Electronic City → Koramangala
- Waypoints 1–3: compliant path
- Waypoints 4–8: deviates into Majestic No-Entry zone (always-on, all vehicles)
- Waypoints 9–15: exit zone
- Expected: Breaches at ticks 4–8

### How to Demonstrate in Postman:
1. `POST /api/simulation/start` — body: `{ "scenarios": ["A", "B", "C"] }`
2. `GET /api/simulation/state` — see all 3 vehicles at start positions
3. `POST /api/simulation/tick` × 5 times — all compliant
4. `POST /api/simulation/tick` — Vehicle 4 breaches. Response shows breach + reroute GeoJSON.
5. Continue ticking — observe more breaches for Vehicles 4 and 7
6. `GET /api/reports/summary` — shows breach counts per vehicle
7. `GET /api/zones/{id}/violations` — shows MG Road zone violation stats

---

## 11. UI DESIGN (Single Static HTML File)

File: `src/main/resources/static/map.html` — served by Spring Boot automatically.

### Layout:
```
┌─────────────────────────────────────────────────────┐
│ 🚛 ZonePilot — Bengaluru Zone Compliance Monitor   │
├─────────────────────────────────────────────────────┤
│ [▶ Start Simulation]  [⏭ Next Tick]  [↺ Reset]      │
│ Tick: 0/15   Active Vehicles: 3   Breaches: 0       │
├─────────────────────────────────────────────────────┤
│                                                     │
│              LEAFLET MAP (Bengaluru)                │
│                                                     │
│  🔴 Red polygon  = NO_ENTRY zone (always-on)        │
│  🟠 Orange polygon = TIME_RESTRICTED zone           │
│  🔵 Blue marker  = vehicle (compliant)              │
│  🔴 Red marker   = vehicle (currently breaching)    │
│  🟣 Purple line  = approved dispatch route          │
│  🟢 Green line   = compliant reroute after breach   │
│                                                     │
├─────────────────────────────────────────────────────┤
│ BREACH LOG                                          │
│ [08:30:06] VEH-KA04 (HCV) → MG Road Zone BREACH    │
│ [08:30:06] Reroute computed ✓                       │
│ [08:30:12] VEH-KA07 (LCV) → Majestic Zone BREACH   │
└─────────────────────────────────────────────────────┘
```

### Technology:
- Single HTML file, no build step
- Leaflet.js loaded from CDN (`unpkg.com` or `cdnjs.cloudflare.com`)
- Plain `fetch()` calls to Spring API
- On load: fetch zones → draw polygons, fetch vehicle positions → draw markers
- "Next Tick" button: calls `POST /api/simulation/tick`, updates marker positions, appends to breach log
- All map operations use Leaflet's `L.geoJSON()` — pass the GeoJSON from API directly

### What to Render from Each API Response:
- Zone boundary GeoJSON → `L.geoJSON(boundary, { color: 'red' }).addTo(map)`
- Vehicle position → `L.marker([lat, lng])` — change icon to red if breach
- Route geometry → `L.geoJSON(routeGeometry, { color: 'purple' })`
- Reroute geometry → `L.geoJSON(rerouteGeometry, { color: 'green' })`

---

## 12. BUILD ORDER FOR LLM

Build in this exact order. Do not proceed to the next step until the current one is verified.

**Step 0 — Data Setup (before any Java code)**
1. Install PostgreSQL 15+, PostGIS, pgRouting
2. Create database `ZonePilot`
3. Run: `CREATE EXTENSION postgis; CREATE EXTENSION pgrouting;`
4. Download Bangalore OSM, run osm2po, import `blr_2po_4pgr.sql`
5. Rename/alias osm2po table to work with pgRouting function calls (create a `ways` view)
6. Draw 5 zone polygons on geojson.io, save coordinates
7. Write all Flyway migration SQL files (V1–V10)

**Step 1 — Spring Boot Project Scaffold**
> Use context7 to fetch Spring Boot 3.x starter structure before generating.
- `pom.xml` with all dependencies (check context7 for correct artifact IDs and versions)
- `application.yml` with datasource, JPA dialect, Flyway config, server port
- `application-dev.yml` for dev profile with simulation seeder enabled
- Verify Flyway migrations run on startup, schema is created

**Step 2 — Entities**
> Use context7 to verify Hibernate 6 spatial geometry mapping annotations before writing.
- One entity at a time: Depot → Vehicle → ZoneRestriction → ZoneRestrictionRule → DispatchRoute → VehiclePositionLog → ZoneBreachLog → SimulationPath
- Each entity: proper `@Column(columnDefinition = "geometry(...,4326)")` on geometry fields
- Enums: use `@Enumerated(EnumType.STRING)` — never ORDINAL
- All relationships: explicit `@ManyToOne(fetch = FetchType.LAZY)` — never EAGER on collection side
- No bidirectional relationships unless explicitly needed — avoids N+1 query problems

**Step 3 — Repositories**
> Use context7 to verify `@Query` native SQL syntax for Spring Data JPA with PostGIS types.
- Each repository extends `JpaRepository`
- All spatial queries use `@Query(value = "...", nativeQuery = true)` — Hibernate JPQL cannot express `ST_Within`, `ST_Intersects`, etc.
- Example: `@Query(value = "SELECT * FROM vehicle_position_log WHERE vehicle_id = :vehicleId AND recorded_at BETWEEN :from AND :to ORDER BY recorded_at DESC", nativeQuery = true)`
- Nearest node query: `@Query(value = "SELECT id FROM blr_2po_4pgr_vertices ORDER BY the_geom <-> ST_SetSRID(ST_Point(:lng, :lat), 4326) LIMIT 1", nativeQuery = true)`

**Step 4 — DTOs & Exception Classes**
- `ApiResponse<T>` generic wrapper
- All request DTOs with `@Valid` annotations and `@NotNull`, `@DecimalMin`, `@DecimalMax` on lat/lng
- All response DTOs: geometry fields serialized as GeoJSON string (use `ST_AsGeoJSON()` in native query or convert in service)
- GlobalExceptionHandler with all cases mapped

**Step 5 — RoutingService**
> This is the most complex service. Use context7 to verify pgRouting function signatures.
- `findNearestNode(lat, lng)` → calls repository native query using `<->` KNN operator
- `computeRoute(sourceNodeId, targetNodeId)` → calls `pgr_dijkstra` via native query, returns list of edge geometries
- `buildRouteGeometry(edges)` → uses `ST_Collect` or `ST_MakeLine` to combine edge geometries into single LINESTRING
- `computeCompliantRoute(vehicleId, origin, destination, violatedZones)` → re-runs routing but increases cost for edges that intersect violated zone polygons (UPDATE ways SET cost = cost * 100 WHERE ST_Intersects(the_geom, zone_boundary) — do this in a temp fashion, not persistent)
- Error handling: if pgRouting returns empty seq → throw RoutingException

**Step 6 — RouteComplianceService**
- `validateRoute(vehicleId, origin, destination)` →
  1. Call RoutingService for initial route
  2. Call `sp_validate_route` stored procedure via native query
  3. If violations → call RoutingService for alternative route
  4. Build and save DispatchRoute entity
  5. Return RouteValidationResponse

**Step 7 — PositionTrackingService**
- `recordPosition(vehicleId, lat, lng, timestamp, speed, source)` →
  1. Validate vehicle exists and is active
  2. Build VehiclePositionLog entity, save (trigger fires automatically in DB)
  3. After save: query zone_breach_log WHERE position_log_id = savedEntity.id
  4. If breach rows found → call BreachService.computeReroute() for each
  5. Return PositionRecordResponse

**Step 8 — SimulationService**
- `startScenarios(scenarioNames)` → set is_active=true for selected paths
- `tick()` →
  1. Load all active paths
  2. For each: extract next point using `ST_PointN(waypoints, step + 1)`
  3. Call PositionTrackingService.recordPosition() with source=SIMULATED
  4. Increment step index
  5. If step >= total_steps → deactivate path
  6. Return TickResult with all vehicle updates and breaches
- `reset()` → set all current_step_index = 0, is_active = false
- `getState()` → return current step and position for all paths

**Step 9 — All Controllers**
- One controller per domain area
- Only validates input, calls service, wraps in ApiResponse
- All methods `ResponseEntity<ApiResponse<T>>`
- Swagger `@Operation`, `@ApiResponse` annotations on every method
- No business logic in controllers

**Step 10 — SimulationDataSeeder**
- `@Profile("dev")` — only runs in dev
- Creates simulation waypoints as WKT LINESTRING strings
- Inserts into simulation_path table via repository
- Includes 3 scenarios as described above

**Step 11 — UI (map.html)**
- Single file in `src/main/resources/static/`
- Load Leaflet from CDN
- On DOMContentLoaded: fetch zones, draw polygons with correct colors
- Fetch vehicle start positions, draw markers
- Wire buttons to API calls
- "Next Tick" → POST tick → update all markers, append breach log

**Step 12 — EXPLAIN ANALYZE Demo Queries**
Prepare these in a separate SQL file for academic demonstration:
```sql
-- Before GIST index: Sequential scan
EXPLAIN ANALYZE
SELECT * FROM zone_restriction
WHERE ST_Within(ST_SetSRID(ST_Point(77.621, 12.972), 4326), boundary);

-- After GIST index: Index Scan
-- Run the same query after CREATE INDEX to show difference
```

---

## 13. ACADEMIC DELIVERABLES CHECKLIST

| Requirement | Where It Is Demonstrated |
|---|---|
| ER Diagram | 9 entities, draw from schema above: vehicle, depot, zone_restriction, zone_restriction_rule, dispatch_route, vehicle_position_log, zone_breach_log, simulation_path, ways |
| Relational Schema | All 9 tables with typed columns, PKs, FKs, constraints |
| Normalization (1NF) | Atomic attributes, no repeating groups, bitmask for days |
| Normalization (2NF) | Surrogate PKs eliminate partial dependencies |
| Normalization (3NF) | zone_restriction + zone_restriction_rule split eliminates transitive deps |
| Referential Integrity | `ON DELETE RESTRICT` on vehicle→depot, `ON DELETE CASCADE` on rule→zone |
| Indexing | GIST indexes for spatial, B-tree for FK columns, composite index where needed |
| Triggers | `trg_detect_zone_breach` — AFTER INSERT on vehicle_position_log |
| Views | 4 views: active positions, breach summary, zone stats, active restrictions |
| Stored Procedures | `sp_validate_route` — spatial intersection check |
| Transactions | `@Transactional` on service methods; trigger atomicity argument |
| EXPLAIN ANALYZE | Before/after GIST index, show Sequential vs Index Scan on spatial query |
| Query Optimization | Nearest-node query: `<->` KNN operator vs naive `ST_Distance` ORDER BY |
| Table Partitioning | `vehicle_position_log` partitioned by RANGE on `recorded_at` (monthly) |
| Spatial Queries | ST_Within, ST_Intersects, ST_PointN, ST_Collect, pgr_dijkstra |

---

## 14. DEMO SCRIPT (Postman Order)

1. `GET /api/zones` — show zone polygons loaded with real Bangalore coordinates
2. `GET /api/zones/active` — show currently active restrictions at this time of day
3. `POST /api/routes/validate` — Vehicle 4 (HCV), origin Yeshwantpur, dest Majestic → show COMPLIANT response with route GeoJSON (pre-8am dispatch)
4. `POST /api/simulation/start` — start all 3 scenarios
5. `GET /api/simulation/state` — show all vehicles at start positions
6. `POST /api/simulation/tick` × 5 — all compliant so far
7. `POST /api/simulation/tick` — Vehicle 4 enters MG Road zone → breach returned with reroute GeoJSON
8. `GET /api/vehicles/4/positions` — show full position history
9. `GET /api/breaches?vehicleId=4` — show breach log with reroute geometry
10. `GET /api/reports/summary` — show aggregate stats from view
11. Open `http://localhost:8080/map.html` — visual demo on map
12. Show Swagger UI: `http://localhost:8080/swagger-ui.html`
13. Run EXPLAIN ANALYZE in psql — show index impact