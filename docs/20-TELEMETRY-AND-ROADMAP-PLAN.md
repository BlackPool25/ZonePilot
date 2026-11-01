# ZonePilot — Telemetry Pipeline + Full Roadmap Plan

> Amendment 2026-11-01 (Wave C): depots removed (V33) — JWT + group-scoped RBAC, STOMP `/topic/group/{id}`. Earlier "depot" wording below now reads "group"; history kept in git.

> Decisions locked 2026-10-24, corrected by PSM spike (§8): EMQX Cloud Serverless · Traccar native-MQTT simulator (no bridge) · Spring Integration MQTT (official, BOM-managed) · STOMP fan-out · JWT + group-scoped RBAC · build auth first.
> Data models, ER, pipeline sequences and config: [`25-DATA-MODELS-AND-PIPELINE.md`](25-DATA-MODELS-AND-PIPELINE.md).

## 1. Pipeline (decided)

```mermaid
flowchart LR
    SIM[Traccar + simulated devices<br/>native forward.type=mqtt] -->|publish + events| EMQX[(EMQX Cloud Serverless<br/>free quota)]
    EMQX -->|subscribe zonepilot/#| ING[Spring ingest<br/>@MqttListener, validated]
    ING --> TRACK[PositionTracking + Breach<br/>derive idle/dwell/off-route]
    TRACK -->|broadcast| WS[STOMP /topic/group/{id}]
    WS --> OPS[Ops board]
    WS --> DRV[Driver app queue /user]
```

- Topics (unified namespace): `zonepilot/{group}/vehicles/{id}/position`, `.../event` (overspeed, fuel drop, geofence enter/exit — Traccar event names reused), `.../status` (online/offline).
- Payload: `{vehicleId, ts, lat, lon, speedKmh, fuelPct?, ignition}` — validated on ingest, invalid → dead-letter log, never silent drop.
- Sim cadence 1Hz/vehicle; backend broadcasts derived state, not raw echo.
- Why Traccar: 200+ real device protocols + built-in overspeed/geofence/fuel-drop events; sim today, real AIS-140 trackers tomorrow with zero backend change. Native `forward.type=mqtt` + `event.forward.*` — no bridge process to own.
- Why EMQX Cloud Serverless: free quota (1M session-min + 1GB/mo) covers demo ~100×, TLS + dashboard built in, zero broker ops. Mosquitto fallback only if you ever self-host.

## 2. Auth + group-scoped RBAC (build first)

- JWT access (15min) + refresh (7d); roles OWNER / DISPATCHER / DRIVER; customer links = signed read-only tokens, no login.
- Every query scoped `WHERE group_id = :group` via aspect/filter — no endpoint trusts a client-sent group id.
- Driver sees own wallet + trips only; dispatcher sees group; owner sees all groups.
- Acceptance: unauthenticated → 401; cross-group id → 403 + audit row; refresh rotation on reuse.

## 3. Roadmap (in your order, each slice independently demoable)

| # | Slice | Done when |
|---|---|---|
| 1 | Auth + RBAC | matrix above green; seed users per role |
| 2 | Driver app (Accept/Start/slips/SOS) | 4-button PWA, offline queue, Kannada/Hindi labels |
| 3 | Real ingest (this pipeline) | sim→map live <2s, replay deterministic |
| 4 | Fuel log + theft/idle flags | fill vs estimate ±8%, drop alert from Traccar fuel event |
| 5 | Maintenance schedule | km/date triggers, workshop state blocks dispatch |
| 6 | Customer link + ePOD | share link, ETA, photo/signature closes trip |
| 7 | Billing | freight bill from closed trip, overdue nudges |
| 8 | Notifications | doc-expiry + delay + settlement pings (log first, SMS later) |
| 9 | Audit trail | who-changed-what on assignments, ledger, rules |
| 10 | Multi-tenancy | org_id above group, per-org pricing/SSO — last |

## 4. Concurrency, caching & production hardening (I/O-bound design)

Workload shape: app threads mostly wait on PG spatial queries, MQTT, WS — so throughput comes from cheap threads + bounded queues, not more CPU. Java 25 → virtual threads are stable; use them.

- **Requests:** `spring.threads.virtual.enabled=true` (Tomcat serves each request on a virtual thread). Sync controller code stays; no reactive rewrite.
- **Slow validation (seconds of Dijkstra):** return `CompletableFuture`/`DeferredResult` (or 202 + poll for the 5-attempt loop) so Tomcat threads aren't pinned; cap concurrent validations with a `Semaphore(PG_CPUs)` — PG is the bottleneck, not the app.
- **Ingest → derive → broadcast:** MQTT listener hands off to a bounded `ThreadPoolTaskExecutor` (queue cap + `CallerRunsPolicy` = natural backpressure); breach/WS fan-out via `@Async("broadcastExecutor")`; add unique `(vehicle_id, recorded_at)` for MQTT redelivery dedupe.
- **Write path:** Redis write-behind buffer for 1Hz positions (flush every N sec); keep per-row DB trigger for demo, async consumer at scale; time partitions + BRIN recent / GIST history.
- **Read path:** Caffeine L1 (zone rules), Redis L2 (`gps:latest:{group}:{vehicle}` GEO + Lua stale-guard, validated-route cache); Fly read replica for reports; V8 materialized views gain a refresh policy.
- **Routing split:** pgRouting stays for penalty-based compliance; Valhalla/OSRM sidecar for plain ETA/matrix (45s vs 0.08s per 100×100).
- **Reliability now (cheap):** actuator `/health` as Render healthcheck (not `/v3/api-docs`), api-docs off in prod, verify Fly PITR backups, Prometheus metrics + ingest-lag alert. PgBouncer transaction mode at 50 trucks; Kafka/Redpanda buffer at 500+.

## 6. Pre-build gates (things to fix before Slice 1 code)

- **Repo hygiene:** no CI, no LICENSE. Add GitHub Actions (build + 8 backend test files + Flyway validate) and a license before any demo link goes out. OSM data needs © OpenStreetMap attribution in the UI (ODbL).
- **DPDP Act (India):** driver GPS = personal data — consent notice in driver app, track only within duty windows (start/end shift gates ingestion), purpose limitation, retention policy (raw positions 90d → aggregates; challan evidence pinned longer). Off-duty tracking off by default.
- **Ingest data-quality filter (the inverse of your sim glitch injector):** quarantine points failing speed-plausibility (implied speed > vehicle capability), teleport jumps, duplicate (vehicle, ts), HDOP/accuracy threshold, clock-skew ts. Publish exception counts on reports; never silently include bad points in scorecards or fuel math.
- **Security basics:** rate-limit auth + ingest endpoints (Bucket4j), security headers, actuator on a separate internal port with docs disabled in prod, secrets rotated from compose defaults.
- **Ops:** verify Fly PITR + test a restore (RTO/RPO written down), Sentry/error tracking, Prometheus ingest-lag + queue-depth alerts, staging env with `simulation.enabled` isolated from prod data.
- **Abuse:** customer share links signed + expiring; driver SOS rate-limited; audit slice covers who-viewed-location (DPDP accountability).

## 5. Traceability

PIM FR-11..13→slice 1+2; FR-40/41→slice 3; FR-50..52→slice 4+7; FR-60→slice 5; FR-61→score inside slice 3; FR-62→slice 6. Audit-trail + tenancy are new NFR-driven rows, PIM §11C to be amended when built.

## 7. PSM spike — must resolve before Slice 1 (research backlog)

Verdict 2026-10-24: plan is **~70% build-ready**. Requirements, slices, and UX are solid; the platform specifics below are still hand-waving. Do this spike first — it is 1–2 days and blocks everything.

- [ ] **BLOCKER — PG hosting with pgRouting.** Fly Managed Postgres supports PostGIS + pgvector only; **pgRouting is not available** (Fly docs, Jul-2025). But `render.yaml` points at Fly PG — `V10__stored_procedures.sql` (pgr_dijkstra) will fail to migrate there. Decide: (a) self-hosted PG on a Fly Machine using `docker/postgres/Dockerfile` (keeps pgRouting, you own backups), or (b) split topology: compliance routing stays on self-hosted PG, app data on managed. Spike: boot option (a), run Flyway V1–V24, record restore procedure.
- [ ] **Pin MQTT coordinates.** `io.github.enesdurmus:mqtt-spring-boot-starter:2.0.0` declares Boot 3.x + MQTT5 (EMQX-compatible) — verify against Boot 3.5.14 in a scratch module with a publish/subscribe round-trip to EMQX Cloud Serverless (TLS port, credentials via env).
- [ ] **Traccar forward schema.** Run Traccar locally, enable HTTP forward, capture one position + one geofence event JSON; freeze the bridge mapping (field names, ts format, fuel field) as a contract test fixture.
- [ ] **STOMP handshake.** `spring-boot-starter-websocket` + SockJS client versions for React 19; confirm `/topic/group/{id}` + `/user` queues through one Render instance (no broker relay needed at demo scale).
- [ ] **JWT + group filter.** Pick jjwt vs Nimbus against Boot 3.5; prove the `group_id` enforcement (Hibernate `@Filter` vs interceptor) with a cross-group 403 test.
- [ ] **Bucket4j + Caffeine + Redis client.** Version check vs Boot 3.5; confirm Lettuce GEO commands for the `gps:latest:*` index.
- [ ] **~~Leaflet.TrackPlayer API.~~ SUPERSEDED 2026-11-01:** replay is server-driven (`POST /api/replays`, REPLAY-source fixes re-inserted at 1–30x, breach feed polled separately) — no TrackPlayer dependency. Original spike item kept for history: ~~Install in a scratch branch; confirm `speed/passedLineColor/panTo/markerRotation` props against Leaflet 1.9.4 + React 19 before committing the Simulation page to it.~~
- [ ] **Java 25 on Render.** Confirm the backend Dockerfile builds Temurin-25 on Render's infra (native, not Docker-in-Docker issue) or pin the image accordingly.

Exit criteria: each box has a version number + a passing spike test or fixture recorded in this section. Then Slice 1 starts. (Note: no plan-review skill is installed in this environment, so this audit was done directly against repo + docs.)

## 8. PSM resolutions — spike executed 2026-10-24 (verified against Maven Central, GitHub, Traccar docs, npm)

### 8.1 PG hosting — SELF-HOST on a Fly Machine (blocker resolved as decision, manual boot left)

Fly Managed Postgres: PostGIS + pgvector only, **no pgRouting** — confirmed in Fly docs. So: deploy `docker/postgres/Dockerfile` (PG16 + PostGIS 3.4 + `postgresql-16-pgrouting`) as a Fly Machine with a persistent volume + daily snapshots; backend connects over Fly private network with `?sslmode=require`. `render.yaml`'s Fly-Postgres URL must be replaced. Manual steps left: boot it, run Flyway V1–V24, snapshot-restore once, write down RTO/RPO. Demo fallback: same image on the laptop. Caffeine/Redis need no research — both Boot-BOM-managed (`spring-boot-starter-cache`, `spring-boot-starter-data-redis`/Lettuce with GEO commands).

### 8.2 MQTT ingest — OFFICIAL Spring Integration, third-party starter REJECTED

`io.github.enesdurmus:mqtt-spring-boot-starter:2.0.0` reads well (Boot 3.x, MQTT5, `@MqttListener`, `mqtt.url` + backoff) but is **absent from Maven Central** under that group — unverifiable supply chain, rejected. `tocrhz:2.0.2` is on Central but pins Paho v3 (MQTT 3.1.1) and a 2023 autoconfigure. Brutal call: use Boot-BOM-managed `spring-integration-mqtt` (+ transitive Paho) — zero compat risk with Boot 3.5.14, version upgrades ride the parent BOM:

```yaml
mqtt:
  url: ${EMQX_URL}            # ssl://<deployment>.emqx.cloud:8883
  username: ${EMQX_USER}
  password: ${EMQX_PASSWORD}
```

Inbound: `MqttPahoMessageDrivenChannelAdapter` topics `zonepilot/+/vehicles/+/position` + `zonepilot/+/vehicles/+/event`, qos 1 → `ExecutorChannel` (bounded queue 500, `CallerRunsPolicy` = backpressure) → `@ServiceActivator` validates → dedupe on unique `(vehicle_id, recorded_at)` → tracking/breach → STOMP broadcast. Exit test: publish one position + one event to EMQX Cloud, assert derived WS frame <2s.

### 8.3 Traccar simulator — NATIVE forward, no bridge (verified in Traccar forwarding docs)

Traccar speaks MQTT itself: `forward.url` (broker), `forward.type=mqtt`, `forward.topic=zonepilot/{group}/vehicles/{id}/position`, `forward.retry.*` for loss; events via `event.forward.url/type/topic` (overspeed, geofence enter/exit, fuel drop land here — your harsh/idle/theft feed for free). The `from-traccar` bridge idea is deleted from this plan. Contract test: capture one position JSON + one event JSON from local Traccar into fixtures; ingest must accept exactly that shape.

### 8.4 Browser fan-out — STOMP confirmed

Server: BOM-managed `spring-boot-starter-websocket`, endpoint `/ws`, topics `/topic/group/{id}` + `/user/queue/*` (driver). Client: `@stomp/stompjs@7.5.0` (verified on npm — pure ESM, **no React peer deps**, React 19/Vite 8 safe) with native WebSocket, no SockJS. Heartbeats on; single Render instance needs no relay at demo scale.

### 8.5 Auth — jjwt 0.12.6 + Hibernate filter (verified on Central)

`io.jsonwebtoken:jjwt-api:0.12.6` (+ `jjwt-impl`, `jjwt-jackson` runtime) — latest, Java 17+, Boot 3 safe. HS256 secret ≥256-bit from env; access 15min + rotating refresh 7d. Group scoping: `@FilterDef("groupFilter")` on group-owned entities, auto-enabled per request (interceptor sets `:groupId` from JWT) — client-sent group ids never trusted. Exit tests: no-token → 401, cross-group → 403 + audit row.

### 8.6 Rate limiting — Bucket4j coordinates pinned

`com.giffing.bucket4j.spring.boot.starter:bucket4j-spring-boot-starter:0.12.10` (verified on Central; Boot 3 line). Apply to auth + ingest + share-link endpoints first; SOS gets its own tight bucket.

### 8.7 Simulation playback — SUPERSEDED 2026-11-01: server-driven replay, no TrackPlayer

~~`npm install leaflet-trackplayer` (verified README): `new L.TrackPlayer(latlngs, {speed, passedLineColor, notPassedLineColor, panTo, markerRotation})` + `start()/pause()/setSpeed()/setProgress()/on('progress'|'finished')` + [live demo](https://weijun-lab.github.io/Leaflet.TrackPlayer/). Traps: (1) default `speed: 600` km/h — always set explicitly per scenario; (2) no TypeScript types — add `src/types/leaflet-trackplayer.d.ts` shim. Dual-trail trick stands: compliant = blue→green trail, breach = red via `passedLineColor`.~~

Replay shipped as server-driven playback instead: `POST /api/replays` re-inserts a tour's stored fixes with source REPLAY at 1–30x; the background worker evaluates breaches eventually and the UI polls `/api/breaches?vehicleId=` separately. `simulation_path` retired (V43). No `leaflet-trackplayer` dependency in `frontend/package.json`.

### 8.8 Java 25 on Render — RESOLVED, no action

`backend/Dockerfile` is multi-stage `eclipse-temurin:25` and `render.yaml` already uses `runtime: docker` — arbitrary images build fine. Only addition: `-XX:MaxRAMPercentage=75.0` for the 512MB box.

**Build-readiness: GREEN except the manual PG boot (8.1). Start Slice 1 (auth) + PG boot in parallel.**
