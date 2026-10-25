# ZonePilot — End-to-End Data Flow & Component Internals

> How every piece moves data, what happens *inside* each component, and how they
> connect. Diagrams are Mermaid; code refs are `backend/...` and `frontend/...`.
> Naming convention follows the existing docs (`10-`, `20-`, `25-`, `30-`, `40-`).

---

## 1. System overview — who talks to whom

```mermaid
flowchart TB
    subgraph EDGE["Edge / field"]
        TRAC[Traccar server<br/>laptop · real GPS or replay] -->|forward.type = mqtt<br/>QoS 1| EMQX
        SIM[Simulation seeder<br/>SimulationDataSeeder<br/>SCENARIO_A/B/C straight-line waypoints] -->|fallback when<br/>road net missing| DB
    end

    subgraph BROKER["EMQX Serverless"]
        EMQX{topics<br/>zonepilot/+/vehicles/+/position<br/>zonepilot/+/vehicles/+/event<br/>zonepilot/+/vehicles/+/status}
    end

    subgraph BACKEND["Spring Boot 3.5 · zonepilot-backend :8080"]
        ADAPT[MqttPahoMessageDrivenChannelAdapter<br/>MqttConfig · ExecutorChannel q=500<br/>CallerRuns on overflow<br/>only when mqtt.url set] -->|topic + raw JSON| INGEST
        INGEST[TraccarIngestService.handle]
        INGEST --> QF[QualityFilter]
        QF -->|accepted| POS[PositionTrackingService.recordPosition]
        QF -->|rejected| QUAR[position_quarantine<br/>V28 · reason + raw payload]
        INGEST -->|QoS1 redelivery already stored| DEDUP[existsByVehicleIdAndRecordedAt<br/>UNIQUE vehicle_id,recorded_at V22<br/>→ ack, no double score]
        DEDUP --> POS
        POS -->|accepted fix| TICK[TickBroadcaster<br/>SimpMessagingTemplate]
        TICK -->|tick per depot topic| WS[STOMP endpoint at /ws<br/>WsConfig · simple broker]
        POS -->|breach rows| BREACH[ZoneBreachLog<br/>trigger per insert V18<br/>stateful end_time/distance_m]
        POS -->|2nd consecutive >50m ping| REROUTE[BreachService.computeReroute<br/>pgr_dijkstra · 1000× detour penalty]
    end

    EMQX -->|subscribe| ADAPT

    subgraph DATA["State"]
        DB[(Postgres 16 + PostGIS 3.4 + pgRouting<br/>Flyway V1–V29 · GEOMETRY 4326<br/>comparisons on recorded_at IST)]
        REDIS[(Redis 7<br/>zp:ingest VID fix/streak/route<br/>TTL · survives restarts)]
    end

    POS <--> DB
    POS <--> REDIS
    INGEST <--> REDIS
    INGEST --> QUAR

    subgraph UI["Frontend · Vite + React :3000"]
        NGINX[nginx · dist/ · SPA fallback /index.html<br/>VITE_API_BASE_URL baked at build]
        PAGES[Dashboard LiveMap · Vehicles · Zones<br/>Routes · Breaches · Reports<br/>Simulation · /driver PWA]
        LIVE[live.js connectTicks<br/>@stomp/stompjs 7.5.0 · /ws<br/>5s reconnect · 10s HTTP poll fallback]
    end

    PAGES -->|REST /api · JWT Bearer<br/>BASE = VITE_API_BASE_URL + /api| BACKEND
    WS -->|"tick frames {lat,lng,speed,recordedAt}"| LIVE
    LIVE -->|merge into positions map| PAGES
```

**Connection cheat-sheet**

| From → To | Protocol | Addr (local docker) | Addr (prod) |
|---|---|---|---|
| Traccar → EMQX | MQTT QoS1 | EMQX Cloud | EMQX Serverless |
| Backend → EMQX | MQTT subscribe | `mqtt.url` empty = adapter off | `mqtt.url` + `EMQX_USER/PASSWORD` |
| Browser → Backend REST | HTTPS + Bearer | `http://localhost:8081/api` | `VITE_API_BASE_URL` |
| Browser → Backend ticks | STOMP over WS | `ws://localhost:8081/ws` | derived from `VITE_API_BASE_URL` (ws-swap) |
| Backend → Postgres | JDBC + Flyway | `postgres:5432/ZonePilot` | managed PG + `?sslmode=require` |
| Backend → Redis | Lettuce (lazy) | `redis:6379` | managed Redis |
| UI static | nginx :80 → host :3000 | `zonepilot-frontend` | Vercel (`vercel.json`) |

---

## 2. Auth + depot scoping — inside the trust boundary

```mermaid
sequenceDiagram
    autonumber
    participant UI as Browser
    participant AC as AuthController<br/>/api/auth/*
    participant AS as AuthService
    participant JWT as JwtService<br/>jjwt 0.12.6 · HS256
    participant DB as app_user<br/>V22
    participant F as JwtAuthFilter<br/>OncePerRequestFilter
    participant DS as DepotScope
    participant HB as Hibernate<br/>@FilterDef depotFilter

    Note over UI,DB: BOOTSTRAP — first ever register() succeeds with no caller (users.count()==0)
    UI->>AC: POST /register {email, password≥8, role, depotId}
    AC->>AS: register(...)
    AS->>AS: caller = DepotScope.current()<br/>OWNER → anyone · DISPATCHER → DRIVER in own depot only
    AS->>AS: email.lowercase · BCrypt hash
    AS->>JWT: generateAccessToken sub,email · depotId · role · 15 min · jti
    AS->>JWT: generateRefreshToken · typ=refresh · 7 days
    AS->>DB: save user + sha256(refresh) + refreshExpiresAt
    AS-->>UI: {accessToken, refreshToken}

    Note over UI,DB: LOGIN — wrong password is 401 INVALID_CREDENTIALS (BadCredentialsException)
    UI->>AC: POST /login {email, password}
    AC->>AS: login(...) → active? → BCrypt.matches?
    AS->>AS: issueTokens() — ROTATES refresh hash every login/refresh
    AS-->>UI: {accessToken, refreshToken}

    Note over UI,DB: REFRESH — replay of a rotated-out token dies at the hash check
    UI->>AC: POST /refresh {refreshToken}
    AC->>AS: refresh(...)
    AS->>JWT: expired? · isRefresh (typ claim)?
    AS->>DB: sha256(presented) == stored hash? · not past refreshExpiresAt?
    AS->>AS: issueTokens() — old refresh INVALID from here on
    AS-->>UI: new pair

    Note over UI,HB: EVERY AUTHENTICATED CALL
    UI->>F: GET /api/vehicles + Authorization: Bearer ...
    F->>JWT: parse + verify signature · access only (refresh rejected)
    alt bad/expired token
        F->>F: SecurityContext.clearContext()
        F-->>UI: 401 + standard error envelope
    else ok
        F->>F: setAuthentication(DepotPrincipal username,depotId,role)
        F->>DS: enable depotFilter with :depotId — EXCEPT OWNER sees all
        DS->>HB: filter(depotFilter).setParameter(depotId)
        HB->>HB: appends depot_id = :depotId to Vehicle + Driver queries
        Note over HB: client-sent depot ids are UNTRUSTED and never read
    end
```

**Internals worth knowing**

- `DepotPrincipal(username, depotId, role)` — the only identity downstream code may trust.
- `DepotScope.apply()` must run inside each `@Transactional` service method (filter is
  session-scoped; `VehicleService` and `DriverService` call it on list paths,
  `requireDepotAccess` on single-entity paths → cross-depot = 403).
- `AuthErrorBody` uses Boot's configured `ObjectMapper` (Instant-safe) — a hand-rolled
  mapper truncated 401/403 bodies, which is why unauthenticated-POST tests once failed.
- Rate limiting (Bucket4j) and contract tests are deferred — see slice reports.

---

## 3. Traccar ingest pipeline — inside `TraccarIngestService.handle`

```mermaid
flowchart TB
    T([MQTT frame<br/>topic + JSON string]) --> SPLIT{topic split /<br/>== zonepilot depot vehicles id kind?}
    SPLIT -- no --> IGN1[log MALFORMED_TOPIC<br/>or BAD_TOPIC_VEHICLE → drop]
    SPLIT -- yes --> KIND{kind?}
    KIND -- status --> IGN2[log only · ignition/fuel noted<br/>nothing stored]
    KIND -- event --> EV[accepted: Traccar event<br/>stored as audit trail]
    KIND -- position --> POSN[handlePosition]
    KIND -- other --> IGN3[log UNKNOWN_KIND → drop]

    POSN --> PARSE{JSON parses?}
    PARSE -- no --> Q1[quarantine MALFORMED_JSON]
    PARSE -- yes --> VEH{vehicle id exists<br/>+ isActive?}
    VEH -- no --> Q2[quarantine UNKNOWN_VEHICLE]
    VEH -- yes --> COORD{lat + lon present?}
    COORD -- no --> Q3[quarantine MISSING_COORDS]
    COORD -- yes --> MATCH{payload.vehicleId ==<br/>topic vehicleId?}
    MATCH -- no --> Q4[quarantine TOPIC_MISMATCH]
    MATCH -- yes --> TS[parse ts: epoch-ms NUMBER<br/>or ISO-8601 STRING, else null]
    TS --> QF

    subgraph QFBOX["QualityFilter.check — first reject wins"]
        QF1{in Bangalore bbox?<br/>GeoBounds} -->|no| R1[OUT_OF_BOUNDS]
        QF1 -->|yes| QF2{ts > now+300s?}
        QF2 -->|yes| R2[FUTURE_SKEW]
        QF2 -->|no| QF3{ts older than 24h?}
        QF3 -->|yes| R3[STALE]
        QF3 -->|no| QF4{speedKmh > 180?}
        QF4 -->|yes| R4[IMPLAUSIBLE_SPEED]
        QF4 -->|no| QF5{accuracy/hdop×5 > 50m?}
        QF5 -->|yes| R5[BAD_ACCURACY]
        QF5 -->|no| QF6{implied speed vs last Redis fix > 300 km/h?}
        QF6 -->|yes| R6[TELEPORT]
        QF6 -->|no| OKQ[accepted]
    end

    QF --> QFBOX
    R1 & R2 & R3 & R4 & R5 & R6 --> Q5[quarantine with reason<br/>nothing scored, nothing stored]
    OKQ --> DD{UNIQUE vehicle_id,recorded_at<br/>already stored?}
    DD -- yes --> DEDUPACK[QoS1 redelivery → silent ack]
    DD -- no --> REC[recordPosition TRACCAR<br/>§4]
    REC -->|DataIntegrityViolation<br/>lost the race| DEDUPACK
    REC -->|any other RuntimeException| Q6[quarantine INGEST_ERROR]
    REC --> SAVEFIX[RedisIngestState.saveFix<br/>zp:ingest:id:fix = lat,lng,ts + TTL]
    SAVEFIX --> TICKFAN[TickBroadcaster.tick<br/>to depot topic]
```

**State keys (Redis, all under `zp:ingest:`)**

| Key | Value | Written by | Read by |
|---|---|---|---|
| `{vid}:fix` | `lat,lng,epochMs` + TTL | every accepted fix | teleport check, WS tick continuity |
| `{vid}:streak` | consecutive off-route count | map-match §4 | map-match §4 |
| `{vid}:route` | active `dispatch_route` id | assignment | map-match route lookup |

---

## 4. `recordPosition` internals — map-match, breach, broadcast

```mermaid
flowchart TB
    IN([recordPosition vehicleId,lat,lng,ts,speed,heading,source]) --> VB{in Bangalore bbox?<br/>else IllegalArgument}
    VB --> FS{ts > now + 300s?}
    FS -- yes --> VEX[ValidationException<br/>future ping rejected]
    FS -- no --> VEH{vehicle exists + isActive?}
    VEH -- no --> NF[404 / ValidationException]
    VEH -- yes --> MM[applyRouteMapMatching<br/>ST_Distance geography<br/>ping ↔ active route geometry]

    MM --> D{dist cross-track}
    D -- "≤ 30m" --> ON[ON-ROUTE<br/>streak=0 in Redis]
    ON --> GL{heading reversed >150°?<br/>opposite carriageway glitch}
    GL -- yes --> SNAP[snap to route nearest point]
    GL -- no --> KEEP[keep ping as-is]
    D -- "30–50m" --> TOL[tolerate · streak=0]
    D -- "> 50m" --> C{streak++ → 1st or 2nd?}
    C -- 1st --> TOL
    C -- 2nd consecutive --> OFF[HARD OFF-ROUTE<br/>BreachService.computeReroute<br/>pgr_dijkstra · 1000× penalty<br/>best of 5 attempts · WAIT]

    SNAP & KEEP & TOL & OFF --> SAVE[INSERT vehicle_position_log<br/>monthly partition · V21 thru 2027<br/>recorded_at = ts not server time]
    SAVE --> TRIG[(DB trigger per insert<br/>ST_Covers + class + IST window<br/>+ bitmask → breach row<br/>60s continuity · V18 stateful)]
    TRIG --> LOOK[findByPositionLogId → fallback<br/>findByVehicleIdAndBreachTime]
    LOOK --> B{breaches?}
    B -- yes --> RR[computeReroute per breach<br/>resolvedRouteGeometry → WKT in response]
    B -- no --> NB[breachDetected=false]
    RR --> FAN
    NB --> FAN[FAN-OUT tick if vehicle.depot set<br/>TickBroadcaster → /topic/depots/id<br/>frame: lat,lng,speedKmh,recordedAt,breach?]
    FAN --> OUT([PositionRecordResponse<br/>+ breach + reroute GeoJSON])
```

---

## 5. Driver lifecycle — assignment state machine + doc wall

```mermaid
stateDiagram-v2
    [*] --> PLANNED: dispatcher creates DispatchRoute
    PLANNED --> ASSIGNED: DriverService.assign(route, driver)<br/>driver in same depot · licence valid
    ASSIGNED --> IN_TRANSIT: driver Accept then Start<br/>POST /driver/routes/{id}/accept|start<br/>wrong order → 422 ASSIGNMENT_BLOCKED
    IN_TRANSIT --> DELIVERED: trip complete
    DELIVERED --> [*]

    note right of ASSIGNED
        EligibilityException → 422 whenever:
        accept from non-ASSIGNED ·
        start before accept ·
        SOS/slip on someone else's route → 403
    end note
```

```mermaid
sequenceDiagram
    autonumber
    participant D as Driver PWA /driver
    participant DC as DriverAppController<br/>/api/driver/*
    participant DS as DriverService
    participant DW as DOC WALL<br/>vehicle_document V23
    participant DB as trip_slip · sos_alert V23

    D->>DC: GET /routes (my depot, my assignments)
    DC->>DW: expiringDocs() — 30/15/7/1-day alert ladder
    DW-->>D: trips + doc alerts (expired licence = hard block)
    D->>DC: POST /routes/{id}/accept → /start
    DC->>DS: accept() → start() — state asserted each step
    D->>DC: POST /routes/{id}/slips {FUEL|TOLL, amount}
    DC->>DS: slip() → TripSlip row → feeds settlement math §F6
    D->>DC: POST /sos {routeId, lat?, lng?}
    Note over D,DB: GPS may be null — coords are nullable end-to-end,<br/>never defaulted to 0,0 (Gulf of Guinea bug)
    DC->>DB: sos_alert row (status OPEN) → dispatch notified
    Note over D,DC: OFFLINE — fetch fails → frame queued in<br/>localStorage zp-offline-queue → manual sync replays<br/>in order. 4xx drops with toast, never silent
```

---

## 6. Frontend runtime — pages, state, live data

```mermaid
flowchart TB
    subgraph BUILD["Build time (Vercel / docker)"]
        ARG[VITE_API_BASE_URL] --> BUNDLE[npm run build → dist/<br/>BASE baked into client.js]
        BUNDLE --> NGINX[nginx / vercel.json<br/>SPA fallback → /index.html]
    end

    subgraph RUN["Browser runtime"]
        ROUTER[router.jsx<br/>/ → Dashboard · /vehicles · /zones<br/>/routes · /breaches · /reports<br/>/simulation · /driver]
        CTX[AppContext<br/>selected vehicle · drawer open<br/>map layers · toasts]
        CLIENT[api/client.js<br/>tokenStore localStorage JWT<br/>BASE + /api + Bearer]

        ROUTER --> DASH[Dashboard.jsx<br/>10s HTTP poll → positions map]
        ROUTER --> DRV[Driver.jsx<br/>own login form · EN/KN/HI labels<br/>trips · Accept/Start · slips · SOS<br/>offline queue + manual sync]
        ROUTER --> REST[Vehicles · Zones · Routes<br/>Breaches · Reports · Simulation<br/>Promise.allSettled fallbacks → demo data]

        LIVE[live.js connectTicks depotId,onTick<br/>broker URL = BASE with ws/wss swap<br/>path /ws · 5s reconnect<br/>malformed-frame guard]
        DASH -->|subscribe per visible depotId| LIVE
        LIVE -->|"tick {latitude,longitude,speedKmh,recordedAt}"| MERGE[merge into positions state<br/>LiveMap markers move · poll is fallback]
        MERGE --> MAP[LiveMap.jsx<br/>Leaflet 1.9 · Carto tiles<br/>cluster 50+ · OSM attribution]
    end

    NGINX -->|serves| ROUTER
    DASH & DRV & REST -->|fetch| CLIENT
    CLIENT -->|REST| BACKEND_API[Spring controllers §2–§5]
    BACKEND_WS[STOMP endpoint /ws] -->|frames| LIVE
```

**Known UI gaps (designed, not yet built):** no route guards (any visitor can open
every page — roles are backend-enforced only), no global login page (login lives
inside Driver), no query cache (per-page loading/error states).

---

## 7. Deploy topology — docker compose (local) vs cloud

```mermaid
flowchart LR
    subgraph LOCAL["docker compose · this box"]
        direction TB
        PG[(zonepilot-postgres :5434<br/>image built from docker/postgres<br/>PostGIS + pgRouting · init-db extensions<br/>Flyway migrates on backend boot<br/>pgdata volume)]
        RD[(zonepilot-redis :6379<br/>redis:7-alpine)]
        BE[zonepilot-backend 8081→8080<br/>temurin:25-jre + prebuilt jar<br/>SPRING_PROFILES_ACTIVE=dev<br/>REDIS_HOST=redis · mqtt.url empty<br/>= MQTT adapter OFF locally]
        FE[zonepilot-frontend 3000→80<br/>node:22 build · nginx serve<br/>BASE=http://localhost:8081]
        BE --> PG
        BE --> RD
    end

    subgraph CLOUD["Production"]
        direction TB
        VERCEL[Vercel → dist/<br/>VITE_API_BASE_URL=render URL]
        RENDER[Render → backend<br/>GOOGLE_ROUTES_API_KEY set]
        EMQXC[EMQX Serverless<br/>EMQX_USER/PASSWORD · anon OFF]
        MPG[(Managed Postgres<br/>+ sslmode=require)]
        MRD[(Managed Redis)]
        TR2[Traccar → forward.type=mqtt]
        VERCEL --> RENDER
        RENDER --> MPG
        RENDER --> MRD
        RENDER <--> EMQXC
        TR2 --> EMQXC
    end

    subgraph MISSING["Deliberately absent locally"]
        OSM[bangalore.osm.pbf<br/>road-network-importer profile<br/>→ blr_2po_4pgr tables<br/>WITHOUT it: RoutingService warns<br/>and seeds straight-line waypoints]
    end
```

**Table & migration map (condensed — full spec is `25-DATA-MODELS-AND-PIPELINE.md`)**

| Range | Contents |
|---|---|
| V1–V20 | depot, vehicle (+V14), zone_restriction/rule (+V20), dispatch_route (+V13/V15), position_log monthly partitions, breach_log (+V18 stateful), simulation_path, `blr_2po_4pgr`, views V8, indexes V9 |
| V21 | partitions through 2027-12 + `shortcut:` drop partitions older than 90 days |
| V22 | `app_user` (role CHECK, depot FK, refresh hash/expiry) + `UNIQUE(vehicle_id, recorded_at)` dedupe |
| V23 | `driver`, `vehicle_document` (+`UNIQUE(vehicle_id,type)`), `trip_slip`, `sos_alert`, route assignment columns |
| V24–V26 | fuel/odometer + money tables, maintenance_task (+WORKSHOP gate), share/bill/notify/audit |
| V27 | SOS location nullable |
| V28–V29 | `position_quarantine` (V28 jsonb → V29 TEXT: dead letters must hold malformed bytes) |
