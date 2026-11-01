# ZonePilot API — Frontend Guide

Interactive reference: **Swagger UI at `/swagger-ui.html`** (same origin as the
backend; raw spec at `/v3/api-docs`). Click **Authorize**, paste the
`accessToken` from login — every call below then carries it as
`Authorization: Bearer <token>`.

Base URL: same origin in prod (`/api`); local dev overrides via
`VITE_API_BASE_URL` (see `frontend/src/api/client.js`).

All responses are wrapped: `{ "success": true, "data": … }`.
Errors: `{ "success": false, "error": { "code", "message", "fields?" } }`.
Common codes: `401` bad login/token, `403` wrong group/role, `404` unknown id,
`422` assignment blocked (vehicle/driver not dispatchable), `429` throttled.

## Auth flow

```
POST /api/auth/login   { "email": "owner@zonepilot.io", "password": "…" }
→ { "accessToken": "…", "refreshToken": "…" }   (access 15m, refresh 7d)

GET  /api/auth/me            → { "email": "…", "role": "OWNER" }
POST /api/auth/refresh      { "refreshToken": "…" } → new pair (old refresh revoked)
```

Throttle: 10 bad logins / 5 min per email **and** 30 / 5 min per client IP
(shared across instances via Redis, in-memory fallback) → `429 RATE_LIMITED`.
Audit-logged server-side. Logins are bootstrapped: the first user registers as
OWNER; afterwards only OWNER creates users (DISPATCHER may create DRIVER logins).

```js
const { accessToken } = await authApi.login(email, password);
tokenStore.set(accessToken); // every later request sends Bearer automatically
```

## Groups

```
GET  /api/groups                       → [{ id, name, … }]
POST /api/groups                       { "name": "HSR" }
GET  /api/groups/{id}                  → detail + member availability
POST /api/groups/{id}/vehicles/{vid}   → "added"      DELETE → "removed"
POST /api/groups/{id}/drivers/{did}    → "added"      DELETE → "removed"
POST /api/groups/{id}/dispatch         { originLat, originLng, destLat, destLng }
POST /api/groups/dispatch-solo        { vehicleId, driverId, originLat, … }
GET  /api/groups/{id}/ticks?since=&limit=   → missed-tick backfill, oldest first
```

`since` is an ISO instant (pass the last seen `recordedAt`); default `limit=20`,
max 50. Used on WS reconnect — see Live ticks below.

## Tours (manager)

```
POST /api/tours                        { vehicleId, driverId, groupId, stops: [{ lat, lng, address? }] }
→ { id, status: "ACTIVE", dutyStatus, groupId, vehicleId, driverId, stops: [{ id, seq, status }] }
GET  /api/tours/{id}
GET  /api/tours/{id}/track             → stops + ETAs + per-leg geometries + latest position
```

## Proof of delivery / skip (driver + manager)

Driver app (`/api/driver/tours/…`) and manager (`/api/tours/…`) share the same
rules: only the **current** (first PENDING) stop can close, PoD needs a fix
within **100 m** of the stop (else `400`), closing the last stop parks the tour
in `PENDING_APPROVAL` (vehicle stays IN_TRANSIT).

```
POST /api/driver/tours/{tourId}/stops/{stopId}/deliver   { "lat": 12.9352, "lng": 77.6245 }
POST /api/tours/{tourId}/stops/{stopId}/deliver          (manager override, same body)
POST /api/driver/tours/{tourId}/stops/{stopId}/skip      { "reason": "gate locked" }
```

Offline conflict: a queued deliver/skip replayed **after** the manager approved
the tour gets `4xx` and is **dropped** (server is the authority) — only
network/5xx failures stay queued for retry.

## Approve (manager)

```
POST /api/tours/{tourId}/approve   → { …, status: "COMPLETED" }  (vehicle → AVAILABLE)
```

Only `PENDING_APPROVAL` tours approve (`400` otherwise). The driver tour card
hides COMPLETED tours; history stays server-side.

## Ingest (live positions)

```
POST /api/ingest/traccar
{ "vehicleId": 7, "lat": 12.93, "lon": 77.59, "ts": "2026-…Z",
  "driverId": 3, "tourId": 11, "groupId": 4, "speedKmh": 28.5, "headingDeg": 90 }
→ { "breachDetected": false, "position": { "latitude", "longitude", "recordedAt", … } }
```

Null keys resolve from the vehicle's active dispatch route; fixes for
COMPLETED tours are rejected (`400`). Same-ms redelivery of an existing fix
returns the stored row (`200`, not `500`).

## Replay

```
POST /api/replay/start   { "tourId": 11, "speed": 4 }   (≤1/user+tour/min, else 429)
```

Replays history through the live pipe; the client sees one summary tick plus
normal per-fix ticks via WS.

## Breaches

```
GET /api/breaches?unacknowledged=true&vehicleId=&zoneId=
GET /api/breaches/{id}
PUT /api/breaches/{id}/acknowledge   → 200 {}
```

Dashboard polls `?unacknowledged=true` and opens the drawer on click.

## SOS

Driver: `POST /api/driver/sos { lat, lng, routeId, accuracyM, stale }`.
Offline replays carry the **last-known fix** + `accuracyM` + `stale: true`;
no fix at all sends `lat/lng: null` (never `0,0`).

Dispatcher inbox: `GET /api/sos` (OWNER/DISPATCHER) → newest first:

```json
{ "id": 9, "driverName": "Ravi", "routeId": 4, "lat": 12.9352, "lng": 77.6245,
  "accuracyM": 12.5, "stale": true, "unknown": false, "status": "OPEN",
  "createdAt": "2026-…Z" }
```

Dashboard shows 🆘 with **"location unknown — call driver"** when `unknown`,
or **"stale fix — confirm on call"** when `stale`.

## Live ticks (WS)

Native-WebSocket STOMP at `/ws` (no SockJS). CONNECT carries
`Authorization: Bearer <token>`; SUBSCRIBE to `/topic/group/{id}` (per-group
JWT + membership enforced; anonymous/foreign-group subscribes get an ERROR
frame). Ticks are derived state:

```json
{ "type": "tick", "groupId": 7, "vehicleId": 3, "lat": 12.93, "lng": 77.62,
  "speedKmh": 28.5, "recordedAt": "2026-…Z", "breachDetected": false }
```

Reconnect backfill: on every authorized SUBSCRIBE the server pushes the buffered
frames to your **private queue** — also subscribe `/user/queue/ticks-backfill`
and feed each element into the same `onTick` handler (`frontend/src/api/live.js`
does this). Explicit variant: `GET /api/groups/{id}/ticks?since=…`.
