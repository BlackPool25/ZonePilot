# 04. State Transition Model

## 1. Overview

The State Transition Model captures the dynamic behavior, runtime lifecycles, and discrete state evolutions triggered by spatial events across ZonePilot.

---

## 2. Dispatch Route Lifecycle Statechart

```mermaid
stateDiagram-v2
    [*] --> DRAFT : Operator specifies Origin & Destination

    state DRAFT {
        [*] --> ParameterValidation
        ParameterValidation --> ReadyForRouting : Valid depot & vehicle
    }

    DRAFT --> COMPUTING_PATH : POST /api/routes/validate
    
    state COMPUTING_PATH {
        [*] --> Attempt1 : Standard Dijkstra
        Attempt1 --> IntersectionCheck
        IntersectionCheck --> SuccessCheck : 0 zone/corridor violations
        IntersectionCheck --> PenalizeSegments : Violations detected
        PenalizeSegments --> AttemptN : Multiply cost * 1000 (Attempt 2..5)
        AttemptN --> IntersectionCheck
        PenalizeSegments --> MaxRetriesExceeded : 5 attempts exhausted
    }

    COMPUTING_PATH --> COMPLIANT : SuccessCheck (Clean path found)
    COMPUTING_PATH --> WAIT_STATE : MaxRetriesExceeded (Traffic ETA < Curfew End)
    COMPUTING_PATH --> REJECTED : Curfew wait exceeds threshold / graph disconnect

    COMPLIANT --> DISPATCHED : Dispatcher assigns to vehicle
    WAIT_STATE --> DISPATCHED : Dispatcher accepts scheduled wait

    DISPATCHED --> IN_PROGRESS : Vehicle departs staging depot
    
    state IN_PROGRESS {
        [*] --> TrackingOnRoute
        TrackingOnRoute --> TemporaryGlitch : ST_Distance < 30m & heading flip
        TemporaryGlitch --> TrackingOnRoute : Snapped to carriageway
        
        TrackingOnRoute --> MinorDrift : 30m < ST_Distance < 50m
        MinorDrift --> TrackingOnRoute : Sensor noise cleared
        
        MinorDrift --> ConfirmedOffRoute : ST_Distance > 50m for 2 consecutive pings
    }

    ConfirmedOffRoute --> OFF_ROUTE_REROUTING : Trigger computeOffRouteReroute()
    OFF_ROUTE_REROUTING --> IN_PROGRESS : Compliant alternative route activated

    IN_PROGRESS --> COMPLETED : Vehicle arrives within 50m of destination
    IN_PROGRESS --> ABORTED : Dispatcher manual cancellation
    REJECTED --> [*]
    COMPLETED --> [*]
    ABORTED --> [*]
```

---

## 3. Vehicle Telematics Tracking Statechart

```mermaid
stateDiagram-v2
    [*] --> IDLE : Vehicle parked at depot

    IDLE --> EN_ROUTE : active_dispatch_route_id assigned
    
    state EN_ROUTE {
        [*] --> SNAPPED_TO_CORRIDOR
        
        SNAPPED_TO_CORRIDOR --> SENSOR_JITTER : Heading delta > 150° within 30m
        SENSOR_JITTER --> SNAPPED_TO_CORRIDOR : Algorithmic carriageway snap
        
        SNAPPED_TO_CORRIDOR --> DRIFT_ALERT_LEVEL1 : Ping > 50m off route (Tick 1)
        DRIFT_ALERT_LEVEL1 --> SNAPPED_TO_CORRIDOR : Return to path (Tick 2 < 50m)
        DRIFT_ALERT_LEVEL1 --> OFF_ROUTE_BREACH : Drift persists (Tick 2 > 50m)
        
        SNAPPED_TO_CORRIDOR --> SPEED_RESTRICTED : Telemetry entering School/Hospital Zone
        state SPEED_RESTRICTED {
            [*] --> SPEED_COMPLIANT : speed <= max_speed
            SPEED_COMPLIANT --> SPEED_VIOLATION : speed > max_speed
            SPEED_VIOLATION --> SPEED_COMPLIANT : Driver slows down
        }
    }

    OFF_ROUTE_BREACH --> REROUTING : pgr_dijkstra recalculation from KNN junction
    REROUTING --> EN_ROUTE : New route active

    EN_ROUTE --> IDLE : Route destination reached
```

---

## 4. Compliance Breach Citation & Audit Statechart

```mermaid
stateDiagram-v2
    [*] --> DETECTED : DB Trigger / Spatial Evaluation fires

    state DETECTED {
        [*] --> IdentifyType
        IdentifyType --> ZONE_ENTRY : Inside curfew polygon
        IdentifyType --> FLYOVER_VIOLATION : Heavy class on prohibited flyover
        IdentifyType --> SPEED_CAP_EXCEEDED : School/Hospital zone speed limit broken
        IdentifyType --> LOADING_BAY_OVERTIME : Exceeded 20-min dwell threshold
    }

    DETECTED --> ACTIVE_UNACKNOWLEDGED : INSERT INTO zone_breach_log
    
    ACTIVE_UNACKNOWLEDGED --> ACKNOWLEDGED : Dispatcher views & marks acknowledged in Ops Control
    
    state ACKNOWLEDGED {
        [*] --> ReviewCitation
        ReviewCitation --> FINE_ASSESSED : Violation confirmed (₹1,000 - ₹5,000)
        ReviewCitation --> EXEMPTION_APPLIED : Driver had emergency permit / GPS verified false-positive
    }

    FINE_ASSESSED --> RESOLVED : Fine reconciled in fleet expense ledger
    EXEMPTION_APPLIED --> RESOLVED : Closed as exempted
    RESOLVED --> [*]
```

---

## 5. CBD Loading Bay Dwell Statechart

```mermaid
stateDiagram-v2
    [*] --> BAY_ENTRY : GPS enters Loading Bay Geofence (e.g. Chickpet)

    state BAY_ENTRY {
        [*] --> InitializeTimer
        InitializeTimer --> UNLOADING_PERMITTED : Dwell < 15 minutes
    }

    UNLOADING_PERMITTED --> DWELL_WARNING_15MIN : Dwell >= 15 minutes
    note right of DWELL_WARNING_15MIN
        Dispatches audio notification to driver
        and amber alert to Dispatcher dashboard
    end note

    DWELL_WARNING_15MIN --> DWELL_BREACH_20MIN : Dwell >= 20 minutes
    note right of DWELL_BREACH_20MIN
        PostGIS trigger records breach
        Fines assessed (₹1,500)
    end note

    UNLOADING_PERMITTED --> BAY_EXIT : Vehicle exits geofence
    DWELL_WARNING_15MIN --> BAY_EXIT : Vehicle exits geofence
    DWELL_BREACH_20MIN --> BAY_EXIT : Vehicle exits geofence

    BAY_EXIT --> [*] : Session finalized and closed
```
