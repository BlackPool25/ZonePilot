# 03. Structural Model

## 1. Overview & Architectural Style

ZonePilot follows a **Modular Domain-Centric Spatial Architecture**. The core spatial computation is offloaded to native PostGIS geometry operators and pgRouting graph algorithms to ensure microsecond-level spatial queries and trigger atomicity.

---

## 2. C4 Level 2: Container Diagram

```mermaid
flowchart TD
    subgraph ClientLayer["Frontend Layer (React 18 + Vite)"]
        SPA["💻 Single Page Application (React)<br/>- 3-Zone Workspace<br/>- Leaflet / MapLibre Spatial Canvas<br/>- Fleet Telematics & Alert Drawer"]
    end

    subgraph AppLayer["Application Layer (Spring Boot 3.5.14)"]
        API["⚙ REST API Gateway / Controllers<br/>(Spring MVC, Validation, GlobalExceptionHandler)"]
        
        subgraph CoreServices["Domain Services"]
            RouteComp["RouteComplianceService<br/>(Recursive 5-attempt router)"]
            Router["RoutingService<br/>(pgRouting wrapper)"]
            Tracking["PositionTrackingService<br/>(Map-matching & drift detection)"]
            BreachSvc["BreachService & BreachQueryService<br/>(Off-route detours & audit)"]
            ElevatedSvc["ElevatedCorridorService<br/>(Flyover prohibition rules)"]
            SpeedSvc["SpeedGovernanceService<br/>(School/hospital speed caps)"]
            BaySvc["LoadingBayService<br/>(CBD dwell time manager)"]
        end
    end

    subgraph DataLayer["Persistence & Spatial Layer (PostgreSQL 16 + PostGIS 3.4)"]
        PostGIS[("🐘 Spatial Database<br/>- GIST Spatial Indexes<br/>- Monthly Partitioned Logs<br/>- pgRouting Graph Topology (blr_2po_4pgr)<br/>- Database Triggers (trg_detect_zone_breach)")]
    end

    SPA -->|"JSON over HTTP / REST"| API
    API --> CoreServices
    CoreServices -->|"Hibernate Spatial 6 + JdbcTemplate"| PostGIS

    classDef client fill:#1e3a8a,stroke:#3b82f6,stroke-width:2px,color:#ffffff;
    classDef app fill:#1e293b,stroke:#10b981,stroke-width:2px,color:#ffffff;
    classDef db fill:#064e3b,stroke:#059669,stroke-width:2px,color:#ffffff;
    class SPA client;
    class API,CoreServices,RouteComp,Router,Tracking,BreachSvc,ElevatedSvc,SpeedSvc,BaySvc app;
    class PostGIS db;
```

---

## 3. Domain Class Diagram

```mermaid
classDiagram
    class Vehicle {
        +Long id
        +String registrationNumber
        +VehicleClass vehicleClass
        +PropulsionType propulsionType
        +String ownerName
        +Long depotId
        +Long activeDispatchRouteId
        +Boolean isActive
    }

    class VehicleClass {
        <<enumeration>>
        TWO_WHEELER
        LCV
        HCV
    }

    class PropulsionType {
        <<enumeration>>
        EV
        DIESEL
        CNG
        PETROL
    }

    class ElevatedCorridor {
        +Long id
        +String corridorName
        +LineString geometry
        +Set~VehicleClass~ prohibitedClasses
        +LocalTime peakStart
        +LocalTime peakEnd
        +Boolean isActive
    }

    class SpeedZone {
        +Long id
        +String zoneName
        +Polygon boundary
        +Integer maxSpeedKmh
        +ZoneCategory category
        +LocalTime activeStart
        +LocalTime activeEnd
    }

    class ZoneCategory {
        <<enumeration>>
        SCHOOL_ZONE
        HOSPITAL_SILENT
        ACCIDENT_BLACKSPOT
    }

    class LoadingBay {
        +Long id
        +String bayName
        +Polygon boundary
        +Integer maxDwellMinutes
        +BigDecimal overtimeFineInr
        +Boolean isCommercialCurfewActive
    }

    class DispatchRoute {
        +Long id
        +Long vehicleId
        +LineString routeGeometry
        +Double totalDistanceMeters
        +Double costTimeSec
        +RouteStatus status
        +Instant waitUntil
        +Integer waitDurationSec
    }

    class VehiclePositionLog {
        +Long id
        +Long vehicleId
        +Point position
        +Double speedKmh
        +Double headingDeg
        +PositionSource source
        +Instant recordedAt
    }

    class ZoneBreachLog {
        +Long id
        +Long vehicleId
        +Long positionLogId
        +BreachType breachType
        +String referenceEntityId
        +BigDecimal estimatedFineInr
        +Instant recordedAt
        +Boolean isAcknowledged
    }

    Vehicle "1" -- "0..1" DispatchRoute : follows
    Vehicle "1" -- "*" VehiclePositionLog : streams
    Vehicle "1" -- "*" ZoneBreachLog : incurs
    ElevatedCorridor "1" .. "*" ZoneBreachLog : triggers
    SpeedZone "1" .. "*" ZoneBreachLog : triggers
    LoadingBay "1" .. "*" ZoneBreachLog : triggers
```

---

## 4. PostGIS Spatial Database Schema & ERD

```mermaid
erDiagram
    vehicle ||--o{ vehicle_position_log : "reports"
    vehicle ||--o{ zone_breach_log : "incurs"
    vehicle ||--o{ dispatch_route : "assigned"
    depot ||--o{ vehicle : "staged_at"

    depot {
        bigserial id PK
        varchar name
        geometry location "POINT(4326)"
        boolean is_active
    }

    vehicle {
        bigserial id PK
        varchar registration_number UK
        varchar vehicle_class
        varchar propulsion_type "EV | DIESEL | CNG"
        bigint depot_id FK
        bigint active_dispatch_route_id
        boolean is_active
    }

    zone_restriction {
        bigserial id PK
        varchar name
        geometry boundary "POLYGON(4326) [GiST]"
        varchar restriction_type
        boolean is_active
    }

    elevated_corridor {
        bigserial id PK
        varchar corridor_name
        geometry center_line "LINESTRING(4326) [GiST]"
        varchar prohibited_classes
        time peak_start
        time peak_end
        boolean is_active
    }

    speed_zone {
        bigserial id PK
        varchar zone_name
        varchar category "SCHOOL | HOSPITAL | BLACKSPOT"
        geometry boundary "POLYGON(4326) [GiST]"
        integer max_speed_kmh
        time active_start
        time active_end
    }

    loading_bay {
        bigserial id PK
        varchar bay_name
        geometry boundary "POLYGON(4326) [GiST]"
        integer max_dwell_minutes
        numeric overtime_fine_inr
    }

    dispatch_route {
        bigserial id PK
        bigint vehicle_id FK
        geometry route_geometry "LINESTRING(4326) [GiST]"
        double_precision total_distance_m
        double_precision cost_time_sec
        varchar status
        timestamptz wait_until
        integer wait_duration_sec
    }

    vehicle_position_log {
        bigserial id PK
        bigint vehicle_id FK
        geometry position "POINT(4326) [GiST]"
        double_precision speed_kmh
        double_precision heading_deg
        varchar source
        timestamptz recorded_at "PARTITION KEY (RANGE)"
    }

    zone_breach_log {
        bigserial id PK
        bigint vehicle_id
        varchar breach_type "ZONE | FLYOVER | SPEED | DWELL"
        bigint reference_id
        numeric fine_amount
        timestamptz recorded_at
        boolean is_acknowledged
    }
```

---

## 5. Partitioning & Spatial Indexing Strategy

1. **Monthly Range Partitioning:** `vehicle_position_log` is partitioned by `recorded_at` (`PARTITION BY RANGE (recorded_at)`) with a `DEFAULT` partition to prevent write degradation as telemetry millions accumulate.
2. **GiST Spatial Indexes:** High-speed bounding box intersection tests (`&&`) and distance calculations (`<->`, `ST_DWithin`, `ST_Covers`) are enabled via GiST indexes across `zone_restriction(boundary)`, `elevated_corridor(center_line)`, `speed_zone(boundary)`, and `loading_bay(boundary)`.
3. **Directed Road Network Graph:** `blr_2po_4pgr` stores the topology with pre-computed `cost_time_sec` and directed one-way indicators (`reverse_cost = -1`), queried via JDBC Template and `pgr_dijkstra`.
