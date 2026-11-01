# 01. System Context Model

## 1. Domain Problem & System Boundary

In urban freight distribution across Bengaluru, logistics operations face strict and fragmented municipal regulations. Violating these regulations leads to immediate vehicle impoundment, fines exceeding ₹2,000–₹5,000 per incident, delayed customer deliveries, and damaged fleet reliability.

**ZonePilot** acts as an automated **Spatial Compliance Intelligence Engine** positioned between fleet telematics/dispatch systems and municipal road networks. It computes compliant routing, monitors live telemetry, detects breaches at the database trigger level, and provides automated rerouting recommendations.

---

## 2. C4 System Context Diagram (Level 1)

```mermaid
flowchart TD
    subgraph ExternalActors["External Actors & Stakeholders"]
        Dispatcher["👤 Logistics Fleet Dispatcher<br/>(Plans dispatch, monitors compliance, handles alerts)"]
        Driver["🚚 Commercial Vehicle Driver<br/>(Drives vehicle, receives real-time guidance)"]
        BTP["🏛 Bengaluru Traffic Police (BTP) & BBMP<br/>(Publishes gazetted restriction rules, speed zones, curfews)"]
    end

    subgraph ExternalSystems["External Systems & Data Feeds"]
        TelematicsFeed["📡 GPS Telematics Stream<br/>(Sends vehicle coordinates, speed, heading at 1-10s intervals)"]
        OSMData["🗺 OpenStreetMap (OSM) Road Network<br/>(Graph topology, one-way tags, turn restrictions)"]
        TrafficAPI["🚦 Traffic & Duration Provider (Google Routes)<br/>(Live travel time & zone curfew arrival predictions)"]
    end

    subgraph ZonePilotSystem["System Boundary: ZonePilot Engine"]
        ZonePilot["🛡 ZonePilot Compliance & Routing Engine<br/>(Spring Boot 3.5, PostGIS 3.4, pgRouting 3.x)"]
    end

    Dispatcher -->|"Creates routes, monitors fleet, acknowledges breaches"| ZonePilot
    ZonePilot -->|"Returns compliant routes, breach alerts, fine risk summaries"| Dispatcher

    TelematicsFeed -->|"Ingests real-time GPS pings (lat, lng, speed, heading)"| ZonePilot
    ZonePilot -->|"Evaluates spatial rules via DB triggers"| ZonePilot

    OSMData -->|"Supplies road graph (blr_2po_4pgr)"| ZonePilot
    TrafficAPI -->|"Supplies traffic duration & curfew wait predictions"| ZonePilot
    BTP -.->|"Gazetted restrictions digitized into zone & corridor rules"| ZonePilot
    ZonePilot -.->|"Guidance & reroute vectors dispatched to"| Driver

    classDef actor fill:#1e3a8a,stroke:#3b82f6,stroke-width:2px,color:#ffffff;
    classDef system fill:#064e3b,stroke:#10b981,stroke-width:2px,color:#ffffff;
    classDef core fill:#7c2d12,stroke:#f97316,stroke-width:3px,color:#ffffff;
    class Dispatcher,Driver,BTP actor;
    class TelematicsFeed,OSMData,TrafficAPI system;
    class ZonePilot core;
```

---

## 3. Real-World Bengaluru Compliance Scope

ZonePilot focuses on 4 real-world compliance dimensions grounded in Bengaluru municipal law:

```mermaid
mindmap
  root((ZonePilot Compliance))
    Elevated Corridors
      Peenya Elevated Highway
        24h 3-wheeler ban
        Peak-hour truck restrictions
      Electronic City Elevated Expressway
        HCV & LCV ban 06:00-22:00
      Silk Board to Ragigudda Double-Decker
        Heavy commercial bans
      Hebbal / Airport Road Flyover
        Slow-moving freight prohibited
    Sensitive Speed Zones
      School Zones
        Max 25-30 km/h (e.g., Bishop Cotton, Sophia High)
      Hospital Silent Corridors
        Strict speed cap & no-honking (Victoria, Manipal, Nimhans)
      Accident Blackspots
        Dynamic speed warnings (Hebbal junction, Tin Factory)
    CBD Loading Bays
      Chickpet / Avenue Road
        Loading restricted 08:00-20:00
        Max 20-min dwell limit
      Commercial Street & Brigade Road
        Designated freight loading windows only
      Dwell-Time Monitoring
        Flags stationary goods vehicles exceeding threshold
    Clean Air & EV Corridors
      Electric Commercial Vehicles (EV)
        Tata Ace EV / Euler HiLoad
        24/7 Green Corridor access
      Diesel HCV / ICE Curfews
        Peak bans 07:00-11:00 & 16:00-22:00
        Emissions compliance tracking
```

---

## 4. Key Operational Constraints

1. **Sub-second Spatial Ingestion:** High-frequency GPS pings must be processed without backlog; triggers execute within the transactional boundary of the position insert.
2. **Deterministic Rerouting:** When a route is blocked by curfew or corridor restriction, the pgRouting engine must either penalize blocked segments (1000x cost) to discover clean detours or compute an optimal wait-state.
3. **No False Detours for Sensor Noise:** GPS pings drifting within 30m of the active route are snapped back to carriageway; off-route rerouting only triggers after 2 consecutive verified drift pings (>50m).
