# 02. Interaction Model

## 1. Overview

The Interaction Model defines how external actors and internal system components exchange messages, coordinate business logic, and handle runtime spatial events.

---

## 2. Use Case Architecture

```mermaid
flowchart LR
    Dispatcher["👤 Dispatcher"]
    Telematics["📡 GPS Telematics"]

    subgraph ZonePilotUseCases["ZonePilot Functional Use Cases"]
        UC1(["UC-1: Validate Route Pre-Dispatch"])
        UC2(["UC-2: Ingest GPS Ping & Snap Carriageway"])
        UC3(["UC-3: Detect Zone/Flyover/Speed Breach"])
        UC4(["UC-4: Compute Dynamic Reroute (1000x Penalty)"])
        UC5(["UC-5: Monitor CBD Loading Bay Dwell Time"])
        UC6(["UC-6: Acknowledge & Settle Breach Citation"])
    end

    Dispatcher --> UC1
    Dispatcher --> UC4
    Dispatcher --> UC6

    Telematics --> UC2
    UC2 --> UC3
    UC3 --> UC4
    UC2 --> UC5

    classDef actor fill:#1e3a8a,stroke:#3b82f6,stroke-width:2px,color:#ffffff;
    classDef uc fill:#1e293b,stroke:#10b981,stroke-width:2px,color:#ffffff;
    class Dispatcher,Telematics actor;
    class UC1,UC2,UC3,UC4,UC5,UC6 uc;
```

---

## 3. Sequence Diagrams

### 3.1 Pre-Dispatch Route Validation & Avoidance Pipeline

This sequence illustrates the recursive pgRouting loop: testing candidate paths against active restrictions, applying a 1,000x penalty to blocked segments, or falling back to a predictive wait-state.

```mermaid
sequenceDiagram
    autonumber
    actor Dispatcher as 👤 Dispatcher
    participant Ctrl as RouteComplianceController
    participant RComp as RouteComplianceService
    participant Router as RoutingService
    participant PG as PostgreSQL (PostGIS / pgRouting)
    participant Google as TrafficAPI (Google Routes)

    Dispatcher->>Ctrl: POST /api/routes/validate (origin, dest, vehicleId)
    Ctrl->>RComp: validateRoute(origin, dest, vehicle)
    
    loop Up to 5 Recursive Attempts
        RComp->>Router: findShortestPath(sourceNode, targetNode, penalizedZoneIds)
        Router->>PG: pgr_dijkstra with dynamic cost * 1000 clause
        PG-->>Router: Candidate route geometry + cost_time_sec
        Router-->>RComp: CandidateRoute
        
        RComp->>PG: SELECT * FROM sp_validate_route(vehicle_id, route_geom)
        PG-->>RComp: Set of Violated Zones / Corridors
        
        alt Zero Violations
            RComp->>PG: INSERT INTO dispatch_route (status = 'VALIDATED')
            PG-->>RComp: RouteEntity #ID
            RComp-->>Ctrl: Compliant RouteResponse (0 violations)
        else Violations Found
            RComp->>RComp: Add violated zone IDs to penalizedZoneIds list
        end
    end

    opt All 5 Attempts Violated (Curfew Hit)
        RComp->>Google: computeTravelDurationWithTraffic(route)
        Google-->>RComp: ETA at zone entry
        RComp->>RComp: Calculate wait_until = curfew_end_time
        RComp->>PG: INSERT INTO dispatch_route (status = 'WAIT_STATE', wait_until, wait_duration_sec)
        RComp-->>Ctrl: Wait-State RouteResponse
    end

    Ctrl-->>Dispatcher: 200 OK (Route Geometry, Milestones, Wait State)
```

---

### 3.2 In-Flight Telematics Stream & DB-Trigger Breach Ingestion

Breach detection is executed inside the transactional boundary of the position insert via PostgreSQL trigger `trg_detect_zone_breach`.

```mermaid
sequenceDiagram
    autonumber
    participant Device as 📡 Vehicle GPS
    participant PostCtrl as PositionController
    participant TrackSvc as PositionTrackingService
    participant DB as PostgreSQL (PostGIS)
    participant Trigger as trg_detect_zone_breach
    participant Dispatcher as 👤 Dispatcher UI

    Device->>PostCtrl: POST /api/telemetry/positions (lat, lng, speed, heading, vehicleId)
    PostCtrl->>TrackSvc: recordPosition(dto)
    
    TrackSvc->>DB: INSERT INTO vehicle_position_log (recorded_at, vehicle_id, position, speed, heading)
    activate DB
    DB->>Trigger: AFTER INSERT ON vehicle_position_log
    activate Trigger
    
    Trigger->>DB: Check ST_Covers(zone.boundary, NEW.position) for active curfews
    Trigger->>DB: Check Elevated Corridor prohibitions (Class/Weight/Time)
    
    alt Spatial Breach Identified
        Trigger->>DB: INSERT INTO zone_breach_log (vehicle_id, breach_type, fine_amount, recorded_at)
        Trigger-->>DB: Breach Logged (Committed in same TX)
    end
    deactivate Trigger
    DB-->>TrackSvc: Position Record Saved
    deactivate DB

    TrackSvc->>TrackSvc: Check ST_Distance(position, active_route_geom)
    alt Drift > 50m for 2 Consecutive Ticks
        TrackSvc->>DB: Update route status = 'OFF_ROUTE'
        TrackSvc->>Dispatcher: Push Notification / WebSocket Alert (Reroute Required)
    end

    TrackSvc-->>PostCtrl: PositionLoggedResponse
    PostCtrl-->>Device: 201 Created
```

---

### 3.3 CBD Loading Bay Dwell-Time Tracking

Monitors commercial delivery stops in congested retail zones (e.g. Chickpet, Commercial St).

```mermaid
sequenceDiagram
    autonumber
    participant Vehicle as 🚚 Delivery Van
    participant DB as PostGIS LoadingBay Table
    participant DwellSvc as LoadingBayComplianceService
    participant Ops as 👤 Dispatcher Control

    Vehicle->>DB: GPS Ping arrives inside Chickpet Loading Bay Polygon
    DwellSvc->>DB: Query existing active session for vehicleId in bay
    
    alt First Ping in Bay (Session Start)
        DwellSvc->>DB: INSERT INTO loading_bay_session (entry_time, max_allowed_minutes=20)
    else Subsequent Ping (Dwelling)
        DwellSvc->>DwellSvc: Compute current_dwell = now() - entry_time
        alt current_dwell > 15 mins (Warning Threshold)
            DwellSvc->>Ops: Emit WARNING: 5 mins remaining before overtime violation
        else current_dwell > 20 mins (Violation Threshold)
            DwellSvc->>DB: Record LOADING_BAY_OVERTIME breach (citation penalty ₹1,500)
            DwellSvc->>Ops: Emit CRITICAL BREACH: Bay overtime fine generated
        end
    end
```

---

### 3.4 Sensitive Area Speed Governance Sequence

```mermaid
sequenceDiagram
    autonumber
    participant Vehicle as 🚚 Delivery Fleet
    participant SpeedSvc as SpeedGovernanceService
    participant DB as PostGIS SpeedZones Table
    participant AlertCenter as 🔔 Alert Center

    Vehicle->>SpeedSvc: Ingest Telemetry (lat, lng, speed = 48 km/h)
    SpeedSvc->>DB: SELECT * FROM speed_zone WHERE ST_Contains(boundary, ST_Point(lng, lat))
    DB-->>SpeedSvc: Match: "Bishop Cotton School Zone" (max_speed = 25 km/h, active: 07:30-16:00)
    
    alt Telemetry Speed > Max Speed
        SpeedSvc->>DB: INSERT INTO zone_breach_log (breach_type = 'SPEED_VIOLATION', delta_kmh = +23)
        SpeedSvc->>AlertCenter: Dispatch Audio/Visual Alert: "School Zone Speeding Detected"
    end
```
