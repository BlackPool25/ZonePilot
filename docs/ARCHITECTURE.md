# ZonePilot Architecture

> How the system fits together: context, containers, key flows, domain model, and data model.
> Plain-language first; every diagram below renders natively on GitHub (flowchart/sequence/class/state/ER only — no C4 plugin blocks, no forced theme).

## 1. System context (C4 Level 1)

```mermaid
flowchart TB
    subgraph ZonePilot["ZonePilot — fleet compliance engine"]
        API["Backend API<br/>validation, tracking, breaches"]
        UI["Ops + Driver UI<br/>map, tours, PoD"]
    end
    Dispatcher["Dispatcher<br/>dispatches, approves"] --> UI
    Driver["Driver<br/>PWA: tours, PoD, SOS"] --> UI
    UI --> API
    API --> PG[("PostgreSQL + PostGIS<br/>zones, tours, positions")]
    API --> RT["pgRouting<br/>road network"]
    API --> MQ["MQTT broker<br/>Traccar ingest"]
    API --> RD[("Redis<br/>ingest state")]
```

## 2. Containers (C4 Level 2)

```mermaid
flowchart TB
    Browser["React 19 SPA<br/>Vercel"] -->|HTTPS /api + WS /ws| SB["Spring Boot 3.5 API<br/>Render"]
    PWA["Driver PWA<br/>same SPA, /driver"] -->|HTTPS /api/driver| SB
    SB -->|JDBC| PG[("Postgres 16 + PostGIS<br/>Fly.io")]
    SB -->|pgr_dijkstra| PG
    SB -->|STOMP| Browser
    TR["Traccar devices"] -->|MQTT| SB
    SB -->|Lettuce| RD[("Redis 7<br/>Render")]
    SB -.->|optional| GR["Google Routes<br/>traffic enrichment"]
```

## 3. Core flows

### 3.1 Live position → breach → reroute

```mermaid
sequenceDiagram
    participant D as Driver PWA / Traccar
    participant A as API (ingest)
    participant P as PostGIS (positions)
    participant W as BreachWorker (async)
    participant R as pgRouting
    participant M as Manager UI
    D->>A: POST fix (vehicle+driver+tour+group, lat/lng)
    A->>P: INSERT vehicle_position_log
    A->>W: PositionFix event (AFTER_COMMIT)
    W->>P: ST_Covers(position, zone)?
    alt inside restricted zone
        W->>P: extend-or-create zone_breach_log (60s window)
        W->>R: zone-avoiding reroute
        W->>P: save resolved_route_geometry
        M->>A: GET /api/breaches (polls, WS tick stays green)
    end
```

### 3.2 Tour lifecycle: dispatch → PoD → approve

```mermaid
sequenceDiagram
    participant M as Manager
    participant A as API
    participant D as Driver
    M->>A: POST /api/tours + dispatch (group, vehicle, driver, stops)
    D->>A: accept + start + ON_DUTY/DRIVING
    loop per stop
        D->>A: deliver (100m GPS fence + PIN/photo/sig policy)
    end
    D->>A: last stop complete → PENDING_APPROVAL
    M->>A: POST /api/tours/{id}/approve (+reason) → COMPLETED
```

### 3.3 Replay (accelerated verification)

```mermaid
sequenceDiagram
    participant M as Manager
    participant A as API
    participant P as PostGIS
    M->>A: POST /api/replay/start {tourId, speed 1-30}
    A->>P: read latest 2000 fixes
    loop chunks of 100 (own transactions)
        A->>P: re-insert as REPLAY (is_replay, never fires worker)
    end
    M->>A: GET /api/breaches?tourId=… (breach feed is separate)
```

## 4. State machines

### 4.1 Tour

```mermaid
stateDiagram-v2
    [*] --> PLANNED
    PLANNED --> ACTIVE : start
    ACTIVE --> PENDING_APPROVAL : last stop complete
    PENDING_APPROVAL --> COMPLETED : approve (+reason)
    PENDING_APPROVAL --> ACTIVE : request changes
    ACTIVE --> CANCELLED : cancel
    PLANNED --> CANCELLED : cancel
```

### 4.2 Breach

```mermaid
stateDiagram-v2
    [*] --> OPEN : first fix inside zone
    OPEN --> OPEN : extend (fix within 60s window)
    OPEN --> RESOLVED : acknowledge
```

### 4.3 Duty (ELD hybrid: auto-suggest + manual confirm)

```mermaid
stateDiagram-v2
    [*] --> OFF_DUTY
    OFF_DUTY --> ON_DUTY : shift start
    ON_DUTY --> DRIVING : speed > 8km/h for 60s
    DRIVING --> ON_DUTY : stopped 5 min
    ON_DUTY --> BREAK : break
    BREAK --> ON_DUTY : resume
    ON_DUTY --> OFF_DUTY : shift end
```

## 5. Domain model (core classes)

```mermaid
classDiagram
    FleetGroup "1" --> "*" GroupVehicle : members
    FleetGroup "1" --> "*" GroupDriver : members
    Vehicle "1" <-- "*" GroupVehicle : vehicle
    Driver "1" <-- "*" GroupDriver : driver
    FleetGroup "1" --> "*" Tour : tours
    Tour "1" --> "*" TourStop : stops (ordered)
    Tour "1" --> "*" DutyLog : duty history
    ZoneRestriction "1" --> "*" ZoneRestrictionRule : rules
    Vehicle "1" --> "*" VehiclePositionLog : fixes
    ZoneBreachLog "*" --> "1" Vehicle : vehicle
    ZoneBreachLog "*" --> "1" ZoneRestriction : zone
    DispatchRoute "*" --> "1" Vehicle : vehicle
    DispatchRoute --> Tour : tour (optional)
    class Tour {
        +TourStatus status
        +DutyStatus dutyStatus
    }
    class TourStop {
        +int seq
        +StopStatus status
        +PodPolicy podPolicy
    }
```

## 6. Data model (Postgres ER, core tables)

Loose `Long` foreign keys (`dispatch_route_attempt`, `load_event`, breach `position_log_id`) are enforced in service code, not by the database — shown dashed in reviews; only real FKs appear below.

```mermaid
erDiagram
    app_user ||--o{ fleet_group : manages
    app_user ||--o{ driver : logs_in_as
    fleet_group ||--o{ group_vehicle : contains
    fleet_group ||--o{ group_driver : contains
    vehicle ||--o{ group_vehicle : member_of
    driver ||--o{ group_driver : member_of
    fleet_group ||--o{ tour : runs
    vehicle ||--o{ tour : assigned
    driver ||--o{ tour : drives
    tour ||--o{ tour_stop : has
    tour ||--o{ duty_log : logs
    vehicle ||--o{ vehicle_position_log : reports
    zone_restriction ||--o{ zone_restriction_rule : governed_by
    vehicle ||--o{ zone_breach_log : breaches
    zone_restriction ||--o{ zone_breach_log : violated_in
    vehicle ||--o{ dispatch_route : routed_as
    tour ||--o{ dispatch_route : planned_for
    driver ||--o{ sos_alert : raises
    driver ||--o{ trip_slip : files
    app_user {
        bigint id PK
        varchar email UK
        varchar role
    }
    tour {
        bigint id PK
        bigint group_id FK
        bigint vehicle_id FK
        bigint driver_id FK
        varchar status
        varchar duty_status
    }
    tour_stop {
        bigint id PK
        bigint tour_id FK
        int seq
        geometry point
        varchar status
        varchar pod_policy
    }
    vehicle_position_log {
        bigint id PK
        timestamptz recorded_at PK
        bigint vehicle_id FK
        bigint driver_id FK
        bigint tour_id FK
        bigint group_id FK
        geometry position
        varchar source
        boolean is_replay
    }
    zone_restriction {
        bigint id PK
        varchar name
        geometry boundary
        varchar restriction_type
        varchar geometry_type
    }
    zone_breach_log {
        bigint id PK
        bigint vehicle_id FK
        bigint zone_id FK
        bigint position_log_id
        varchar breach_type
        boolean is_acknowledged
    }
```

Full column detail lives in `backend/src/main/resources/db/migration/` (V1–V53); this diagram shows the relations that matter for reviews.
