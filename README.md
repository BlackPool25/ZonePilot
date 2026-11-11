# 🛡 ZonePilot

> **Stop delivery trucks from entering restricted city zones — before the fine lands.**
> ZonePilot validates every route against Bengaluru's freight bans, watches live GPS for breaches, and proves each delivery with GPS-stamped proof-of-delivery.

[![Spring Boot 3.5](https://img.shields.io/badge/Spring_Boot-3.5.14-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL 16](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![PostGIS](https://img.shields.io/badge/PostGIS-3.6-5B8A3C?logo=postgis&logoColor=white)](https://postgis.net/)
[![pgRouting](https://img.shields.io/badge/pgRouting-4.x-2C5E8A)](https://pgrouting.org/)
[![React 19](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## Try it in 2 minutes (live demo, seeded)

No setup needed to look around — the database ships with a demo fleet (migration `V53`):

| Role | Login | Lands on |
|---|---|---|
| Dispatcher (manager flow) | `demo.disp@zonepilot.io` | `/` ops dashboard |
| Driver One (active tour + open breach) | `demo.driver1@zonepilot.io` | `/driver` PWA |
| Driver Two (planned tour) | `demo.driver2@zonepilot.io` | `/driver` PWA |
| Owner (full admin view) | `demo.owner@zonepilot.io` | `/` ops dashboard |

Password for all four: **`demo1234`**. Or open `/login` in the app and hit a one-click demo button.

**Guided 15-minute script:** [`docs/DEMO.md`](docs/DEMO.md) — home → fleet → zones → pre-dispatch check → breach triage → driver run → approve → replay → reports.

---

## What it does (by role)

**🚚 Dispatcher / manager** — sees the whole fleet on a live map with restricted zones overlaid; creates groups, assigns vehicles + drivers, dispatches multi-stop tours; gets an instant alert when a truck enters a banned zone (with an auto-computed detour); approves completed tours with an audit trail.
**Demo:** log in as dispatcher → HSR Demo Fleet is live, one breach is already open on MG Road.

**🛵 Driver (mobile PWA, English/Kannada/Hindi)** — sees assigned tours, navigates stop-by-stop, taps proof-of-delivery (GPS fence + PIN/photo/signature policy per stop), switches ELD duty states, hits one-tap SOS that survives offline.
**Demo:** log in as Driver One → active tour has a pending stop inside the MG Road zone.

**📊 Compliance & reports** — every position is keyed to vehicle + driver + tour + group; breaches extend while the truck stays inside; replay re-runs any tour at 1–30x for audits; reports show per-vehicle and per-zone violation stats.
**Demo:** `/replay` the active tour at 5x, then check `/breaches` and `/reports`.

---

## Why it exists

Bengaluru bans heavy freight during peak hours (07:00–11:00, 16:00–22:00), keeps trucks off flyovers, caps dwell time in market bays, and fines violations at ₹2,000–₹5,000 per vehicle. Manual route sheets mean fines are discovered on-site, after they're issued. ZonePilot moves that check into software: validate before dispatch, detect during the trip, prove after delivery.

---

## Tech stack

| Layer | Tech |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Data JPA, WebSocket (STOMP), MQTT ingest |
| Spatial | PostgreSQL 16 + PostGIS, pgRouting (zone-avoiding Dijkstra), OSM Bangalore network |
| Frontend | React 19, Leaflet maps, driver PWA with offline queue |
| Infra | Docker Compose (local), Render + Fly.io + Vercel (prod), Redis, 53 Flyway migrations |

Full diagrams: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — C4 context/containers, live-breach + tour + replay sequences, tour/breach/duty state machines, domain classes, Postgres ER.

---

## Run it locally

```bash
# 1. Database + services
docker compose up -d postgres redis

# 2. Backend (http://localhost:8080, Swagger at /swagger-ui.html)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (http://localhost:5173)
cd frontend && npm install && npm run dev
```

First boot applies all migrations including the seeded demo world. To import the real road network: `./import-osm-local.sh ~/osm/southern-zone-latest.osm.pbf`. Production deploy: [`DEPLOYMENT.md`](DEPLOYMENT.md).

---

## API map (all under `/api`, Bearer JWT)

| Area | Endpoints |
|---|---|
| Auth / groups | `POST /auth/login`, `GET /groups`, `POST /groups/{id}/dispatch` |
| Tours & PoD | `POST /tours`, `GET /tours/{id}/track`, `POST .../stops/{seq}/complete`, `POST /tours/{id}/approve` |
| Driver app | `GET /driver/tours`, `POST .../duty`, `POST /driver/sos`, `GET .../hos` |
| Compliance | `POST /routes/validate`, `POST /vehicles/{id}/positions`, `POST /ingest/traccar`, `GET /breaches`, `POST /replay/start` |
| Ops (owner) | `GET /api/ops/saturation`, `/quarantine`, `/partitions`, `/audit` |

Interactive spec with auth built in: `/swagger-ui.html`. Frontend call-by-call guide: [`docs/API-FRONTEND-GUIDE.md`](docs/API-FRONTEND-GUIDE.md).

---

## Project structure

```
backend/   Spring Boot API (Flyway V1–V53, PostGIS/pgRouting)
frontend/  React ops dashboard + driver PWA
docker/    Postgres+PostGIS image, init-db
docs/      DEMO walkthrough, ARCHITECTURE, API guide, MDA suite
```

---

## Trade-offs & roadmap

- **Background breach worker, not DB trigger:** ingest can never 500 when road tables are missing; breach visibility is eventual (UI polls separately).
- **Replay writes are quarantined** (`is_replay`, never fires the worker) so verification runs can't fake live compliance data.
- **Manual-first ELD:** auto-DRIVING suggests, the driver confirms — keeps the log defensible without hardware sensors.
- **Next:** LEZ/emission-class zones, hazmat/tunnel routing avoid, PoD photo storage backend, corridor fast-path at scale. Research notes: [`docs/ULW-RESEARCH-TRACKING-RULES.md`](docs/ULW-RESEARCH-TRACKING-RULES.md).

---

## Docs

| Doc | What it answers |
|---|---|
| [`docs/README.md`](docs/README.md) | Docs map — find anything by task (tutorials / how-tos / reference / explanation) |
| [`docs/DEMO.md`](docs/DEMO.md) | 15-minute guided demo script + logins + reset |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | C4, sequences, state machines, classes, ER (all Mermaid) |
| [`docs/API-FRONTEND-GUIDE.md`](docs/API-FRONTEND-GUIDE.md) | Endpoint-by-endpoint frontend guide |
| [`docs/USER-FLOWS-AND-UX.md`](docs/USER-FLOWS-AND-UX.md) | User flows per role (frontend rebuild spec) |
| [`docs/10-PIM-PLATFORM-INDEPENDENT.md`](docs/10-PIM-PLATFORM-INDEPENDENT.md) | Stable platform-independent contract |
| [`DEPLOYMENT.md`](DEPLOYMENT.md) | Prod deploy (Render + Fly + Vercel) + OSM import |
| [`docs/archive/`](docs/archive/) | Stale QA snapshots — history, not current spec |

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL).
