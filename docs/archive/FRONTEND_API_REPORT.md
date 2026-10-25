# ZonePilot — Frontend API Integration Report

**Date:** 2026-05-23  
**Backend version:** 0.0.1-SNAPSHOT (post bug-fix)  
**Base URL:** `http://localhost:8080`  
**Content-Type:** `application/json` (all requests)  
**Swagger UI:** `http://localhost:8080/swagger-ui.html`

---

## 1. Response Envelope

Every endpoint returns the same wrapper. Always check `success` before reading `data`.

```json
{
  "success": true,
  "timestamp": "2026-05-23T16:42:00Z",
  "data": { ... },
  "error": null
}
```

On error:
```json
{
  "success": false,
  "timestamp": "2026-05-23T16:42:00Z",
  "data": null,
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "Vehicle with id 99 not found",
    "fields": null
  }
}
```

On validation error (BUG-09 fixed):
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid request body",
    "fields": {
      "vehicleClass": "must not be blank",
      "depotId": "must not be null"
    }
  }
}
```

**Frontend pattern:**
```js
const res = await fetch('/api/vehicles', { method: 'POST', body: JSON.stringify(payload), headers: { 'Content-Type': 'application/json' } });
const json = await res.json();
if (!json.success) {
  // show json.error.message
  // highlight json.error.fields keys in the form
  return;
}
// use json.data
```

---

## 2. Error Codes Reference

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Bean validation failed. `fields` map populated. |
| 400 | `MALFORMED_JSON` | Request body is missing or not valid JSON |
| 400 | `INVALID_VALUE` | Invalid enum value (e.g., unknown `vehicleClass`) |
| 400 | `INVALID_PARAMETER_TYPE` | Path/query param has wrong type (e.g., `/vehicles/abc`) |
| 400 | `MISSING_PARAMETER` | Required query param absent |
| 400 | `SIMULATION_ERROR` | Unknown scenario name |
| 400 | `CONSTRAINT_VIOLATION` | Jakarta constraint violation outside request body |
| 404 | `RESOURCE_NOT_FOUND` | Entity not found by ID |
| 404 | `ENDPOINT_NOT_FOUND` | No route at the requested path |
| 405 | `METHOD_NOT_ALLOWED` | Wrong HTTP verb |
| 409 | `CONFLICT` | Re-acknowledging an already-acknowledged breach |
| 409 | `DATA_INTEGRITY_VIOLATION` | Duplicate unique value (e.g., duplicate registration number) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Missing or wrong `Content-Type` header |
| 422 | `ROUTING_ERROR` | pgRouting failed (road network loaded but no path found) |
| 503 | `ROAD_NETWORK_UNAVAILABLE` | Road network not loaded — route validation disabled |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

---

## 3. Vehicles

### 3.1 List Vehicles
```
GET /api/vehicles
GET /api/vehicles?vehicleClass=HCV
GET /api/vehicles?isActive=true
GET /api/vehicles?vehicleClass=LCV&isActive=true
```

**Response `data`:** array of `VehicleResponse`

```json
[
  {
    "id": 7,
    "registrationNumber": "KA01-HCV-0007",
    "vehicleClass": "HCV",
    "ownerName": "Fleet Owner 7",
    "depotId": 2,
    "depotName": "Yeshwantpur Depot",
    "isActive": true
  }
]
```

**`vehicleClass` enum values:** `TWO_WHEELER`, `LCV`, `HCV`

**Frontend use:** vehicle list table, filter dropdowns, map marker layer.

---

### 3.2 Get Vehicle
```
GET /api/vehicles/{id}
```
Returns single `VehicleResponse`. 404 if not found.

---

### 3.3 Create Vehicle
```
POST /api/vehicles
```
```json
{
  "registrationNumber": "KA01-LCV-0099",
  "vehicleClass": "LCV",
  "ownerName": "Owner Name",
  "depotId": 1,
  "isActive": true
}
```
- `registrationNumber`: required, unique
- `vehicleClass`: required, one of `TWO_WHEELER | LCV | HCV`
- `ownerName`: required
- `depotId`: required, must exist
- `isActive`: optional, defaults to `true`

Returns 400 with `fields` map on validation failure. Returns 409 on duplicate `registrationNumber`.

---

### 3.4 Update Vehicle
```
PUT /api/vehicles/{id}
```
Same body as create. Returns updated `VehicleResponse`.

---

### 3.5 Zones at Location
```
GET /api/vehicles/{id}/zones-at-location?lat=12.974&lng=77.620
```

Returns array of `ZoneResponse` for zones that contain the given point. Each zone includes its currently-active rules (empty array `[]` if none active — never `null`).

**Frontend use:** vehicle detail panel showing "currently in restricted zone" badge, active rule list.

---

## 4. Depots

### 4.1 List Depots
```
GET /api/depots
```
```json
[
  {
    "id": 1,
    "name": "Koramangala Depot",
    "address": "Koramangala, Bengaluru",
    "latitude": 12.9352,
    "longitude": 77.6245
  }
]
```

### 4.2 Get Depot
```
GET /api/depots/{id}
```

### 4.3 Create Depot
```
POST /api/depots
```
```json
{
  "name": "New Depot",
  "address": "Street, Area, Bengaluru",
  "lat": 12.91,
  "lng": 77.63
}
```
All fields required.

---

## 5. Zones

### 5.1 List All Zones
```
GET /api/zones
```

### 5.2 Get Zone
```
GET /api/zones/{id}
```

### 5.3 List Active Zones
```
GET /api/zones/active
```
Returns zones where at least one rule is currently active (IST time + day of week).

**`ZoneResponse` shape:**
```json
{
  "id": 1,
  "name": "MG Road No-Entry",
  "description": "No heavy vehicles on MG Road",
  "boundaryGeoJson": {
    "type": "Polygon",
    "coordinates": [[[77.617, 12.976], [77.623, 12.976], [77.623, 12.971], [77.617, 12.971], [77.617, 12.976]]]
  },
  "restrictionType": "NO_ENTRY",
  "isActive": true,
  "rules": [
    {
      "id": 1,
      "applicableVehicleClass": "HCV",
      "restrictionStartTime": "07:00",
      "restrictionEndTime": "21:00",
      "daysOfWeekBitmask": 31,
      "isActive": true
    }
  ]
}
```

**`boundaryGeoJson`** is a proper GeoJSON object — pass directly to Leaflet/Mapbox/Google Maps:
```js
L.geoJSON(zone.boundaryGeoJson).addTo(map);
```

**`restrictionType` enum values:** `NO_ENTRY`, `TIME_RESTRICTED`, `VEHICLE_CLASS_RESTRICTED`

**`daysOfWeekBitmask`:** bitmask where bit 0 = Monday, bit 1 = Tuesday, ..., bit 6 = Sunday. Value 127 = all days, 31 = Mon–Fri.

### 5.4 Create Zone
```
POST /api/zones
```
```json
{
  "name": "Zone Name",
  "description": "Optional description",
  "boundaryGeoJson": "{\"type\":\"Polygon\",\"coordinates\":[[[77.617,12.976],[77.623,12.976],[77.623,12.971],[77.617,12.971],[77.617,12.976]]]}",
  "restrictionType": "TIME_RESTRICTED",
  "isActive": true,
  "rules": [
    {
      "applicableVehicleClass": "HCV",
      "restrictionStartTime": "08:00",
      "restrictionEndTime": "22:00",
      "daysOfWeekBitmask": 31,
      "isActive": true
    }
  ]
}
```

Notes:
- `boundaryGeoJson` must be a valid GeoJSON Polygon **string** in the request body (the server parses it)
- `rules` is optional; omit or pass `[]` to create a zone with no rules
- `applicableVehicleClass`: `TWO_WHEELER | LCV | HCV | ALL` (omit for ALL)
- `restrictionStartTime` / `restrictionEndTime`: `"HH:MM"` format
- Polygon must be closed (first and last coordinate identical) and valid

---

## 6. Positions

### 6.1 Record Position
```
POST /api/vehicles/{vehicleId}/positions
```
```json
{
  "lat": 12.9716,
  "lng": 77.5946,
  "speedKmh": 35.5,
  "headingDeg": 90,
  "timestamp": "2026-05-23T16:42:00Z"
}
```

- `lat`: required, Bangalore bounds: 12.83–13.14
- `lng`: required, Bangalore bounds: 77.45–77.78
- `speedKmh`: optional, must be ≥ 0
- `headingDeg`: optional, 0–359
- `timestamp`: optional ISO-8601; defaults to server time; cannot be >5 min in the future

**Response `data`:**
```json
{
  "breachDetected": true,
  "position": {
    "latitude": 12.9716,
    "longitude": 77.5946,
    "recordedAt": "2026-05-23T16:42:00Z",
    "speedKmh": 35.5,
    "source": "GPS"
  },
  "breaches": [
    {
      "breachId": 42,
      "zoneId": 1,
      "zoneName": "MG Road No-Entry",
      "breachType": "NO_ENTRY",
      "rerouteGeoJson": null
    }
  ]
}
```

`rerouteGeoJson` is a WKT LineString if a reroute was computed, `null` if road network is not loaded.

**Frontend use:** real-time position update, breach alert toast, reroute overlay on map.

### 6.2 Position History
```
GET /api/vehicles/{vehicleId}/positions
GET /api/vehicles/{vehicleId}/positions?from=2026-05-23T00:00:00Z&to=2026-05-23T23:59:59Z
```

Returns array of `PositionResponse`:
```json
[
  {
    "id": { "vehicleId": 7, "recordedAt": "2026-05-23T16:42:00Z" },
    "vehicleId": 7,
    "latitude": 12.9716,
    "longitude": 77.5946,
    "recordedAt": "2026-05-23T16:42:00Z",
    "speedKmh": 35.5,
    "headingDeg": 90,
    "source": "GPS"
  }
]
```

`source` values: `GPS`, `SIMULATED`, `MANUAL`

### 6.3 Latest Position
```
GET /api/vehicles/{vehicleId}/positions/latest
```
Returns single `PositionResponse`. 404 if no positions recorded.

---

## 7. Breaches

### 7.1 List Breaches
```
GET /api/breaches
GET /api/breaches?vehicleId=7
GET /api/breaches?zoneId=1
GET /api/breaches?vehicleId=7&from=2026-05-23T00:00:00Z&to=2026-05-23T23:59:59Z
GET /api/breaches?unacknowledged=true
```

Returns array of `BreachResponse`:
```json
[
  {
    "id": 42,
    "vehicleId": 7,
    "registrationNumber": "KA01-HCV-0007",
    "zoneId": 1,
    "zoneName": "MG Road No-Entry",
    "breachType": "NO_ENTRY",
    "breachTime": "2026-05-23T16:42:00Z",
    "rerouteGeoJson": null,
    "isAcknowledged": false
  }
]
```

`breachType` values: `NO_ENTRY`, `TIME_WINDOW`, `VEHICLE_CLASS`

### 7.2 Get Breach
```
GET /api/breaches/{id}
```

### 7.3 Acknowledge Breach
```
PUT /api/breaches/{id}/acknowledge
```

- **200** — first acknowledgement, returns updated `BreachResponse` with `isAcknowledged: true`
- **409** — already acknowledged, error code `CONFLICT`

**Frontend pattern:**
```js
const res = await fetch(`/api/breaches/${id}/acknowledge`, { method: 'PUT' });
const json = await res.json();
if (res.status === 409) {
  showToast('Already acknowledged');
  return;
}
// update UI
```

---

## 8. Routes

### 8.1 Validate Route (Pre-Dispatch)
```
POST /api/routes/validate
```
```json
{
  "vehicleId": 7,
  "originLat": 12.9716,
  "originLng": 77.5946,
  "destLat": 12.974,
  "destLng": 77.620
}
```

**Requires road network loaded.** Returns 503 `ROAD_NETWORK_UNAVAILABLE` if not.

**Response `data`:**
```json
{
  "compliant": false,
  "routeGeoJson": "LINESTRING (77.5946 12.9716, ...)",
  "violations": [
    {
      "zoneId": 1,
      "zoneName": "MG Road No-Entry",
      "breachType": "NO_ENTRY"
    }
  ],
  "alternativeRouteGeoJson": "LINESTRING (...)",
  "alternativeRouteUnavailable": false,
  "dispatchRouteId": 12,
  "waitUntil": null,
  "waitDurationSec": null
}
```

If all 5 routing attempts hit curfews, `waitUntil` (ISO-8601) and `waitDurationSec` are populated — the vehicle should wait at the zone boundary until the curfew ends.

**Frontend use:** route planning form, compliance badge, map overlay of planned vs alternative route.

### 8.2 Get Route
```
GET /api/routes/{id}
```

### 8.3 Vehicle Route History
```
GET /api/routes/vehicle/{vehicleId}
```
Returns array of dispatch routes, newest first.

---

## 9. Reports

### 9.1 Fleet Breach Summary
```
GET /api/reports/summary
```
```json
[
  {
    "vehicleId": 7,
    "registrationNumber": "KA01-HCV-0007",
    "vehicleClass": "HCV",
    "totalBreaches": 12,
    "noEntryBreaches": 5,
    "timeWindowBreaches": 7,
    "classBreaches": 0,
    "unacknowledgedBreaches": 3,
    "lastBreachTime": "2026-05-23T16:42:00Z"
  }
]
```

**Frontend use:** fleet dashboard table, per-vehicle breach count badges, unacknowledged alert counter.

### 9.2 Zone Violation Stats
```
GET /api/reports/zones/{zoneId}/violations
```
Returns empty array `[]` if zone has no violations (not 404).

```json
[
  {
    "zoneId": 1,
    "zoneName": "MG Road No-Entry",
    "restrictionType": "NO_ENTRY",
    "totalViolations": 24,
    "hcvViolations": 18,
    "lcvViolations": 6,
    "twoWheelerViolations": 0,
    "lastViolationTime": "2026-05-23T16:42:00Z"
  }
]
```

**Frontend use:** zone detail panel, violation breakdown chart.

### 9.3 Currently Active Restrictions
```
GET /api/reports/active-restrictions
```
Returns restrictions whose time window is currently active in IST.

```json
[
  {
    "id": 1,
    "name": "MG Road No-Entry",
    "restrictionType": "NO_ENTRY",
    "applicableVehicleClass": "HCV",
    "restrictionStartTime": "07:00",
    "restrictionEndTime": "21:00"
  }
]
```

Times are `"HH:MM"` format (IST). Poll this endpoint every 60 seconds to keep the active restriction overlay current.

**Frontend use:** live restriction map overlay, operator dashboard "active now" panel.

---

## 10. Simulation

### 10.1 Start Scenarios
```
POST /api/simulation/start
```
```json
{ "scenarios": ["A", "B", "C"] }
```

Accepted values: `"A"`, `"B"`, `"C"`, `"SCENARIO_A"`, `"SCENARIO_B"`, `"SCENARIO_C"`. Case-insensitive.

Returns 200 with `data: null` on success. Returns 400 `SIMULATION_ERROR` for unknown scenario names.

### 10.2 Tick
```
POST /api/simulation/tick
```
No body. Advances all active vehicles one step.

```json
{
  "tickNumber": 6,
  "vehicles": [
    {
      "vehicleId": 7,
      "registrationNumber": "KA01-HCV-0007",
      "latitude": 12.9716,
      "longitude": 77.5946,
      "breachDetected": true,
      "breaches": [
        {
          "breachId": 42,
          "zoneId": 1,
          "zoneName": "MG Road No-Entry",
          "breachType": "NO_ENTRY",
          "rerouteGeoJson": null
        }
      ],
      "status": "MOVING"
    }
  ],
  "exhausted": null
}
```

When all vehicles have completed their paths:
```json
{
  "tickNumber": 17,
  "vehicles": [
    { "vehicleId": 7, "status": "COMPLETED", "breachDetected": false, ... }
  ],
  "exhausted": true
}
```

**`status` values:** `MOVING`, `COMPLETED`

**Frontend pattern:**
```js
async function runSimulation() {
  while (true) {
    const res = await fetch('/api/simulation/tick', { method: 'POST' });
    const json = await res.json();
    updateMap(json.data.vehicles);
    if (json.data.exhausted) {
      showBanner('Simulation complete');
      break;
    }
    await sleep(1000); // 1 tick per second
  }
}
```

### 10.3 Get State
```
GET /api/simulation/state
```
```json
[
  {
    "pathId": 1,
    "vehicleId": 7,
    "registrationNumber": "KA01-HCV-0007",
    "scenarioName": "SCENARIO_B",
    "currentStep": 6,
    "totalSteps": 17,
    "isActive": true,
    "latitude": 12.9716,
    "longitude": 77.5946
  }
]
```

After exhaustion: `currentStep == totalSteps` and `isActive == false`.

**Frontend use:** simulation progress bar (`currentStep / totalSteps`), active/inactive vehicle indicator.

### 10.4 Reset
```
POST /api/simulation/reset
```
No body. Resets all paths to `currentStep = 0`, `isActive = false`. Returns 200 with `data: null`.

---

## 11. Configurable Display Recommendations

### Map Layer Priority
1. Zone boundaries (GeoJSON polygons from `GET /api/zones/active`) — color by `restrictionType`
2. Vehicle positions (latest from `GET /api/vehicles/{id}/positions/latest`) — icon by `vehicleClass`
3. Active restrictions overlay (from `GET /api/reports/active-restrictions`) — pulsing highlight
4. Breach markers (from `GET /api/breaches?unacknowledged=true`) — red alert pins
5. Reroute geometry (from breach `rerouteGeoJson` or route `alternativeRouteGeoJson`) — dashed line

### Polling Intervals
| Data | Recommended interval | Endpoint |
|---|---|---|
| Vehicle positions (live) | 5s | `GET /api/vehicles/{id}/positions/latest` |
| Active restrictions | 60s | `GET /api/reports/active-restrictions` |
| Unacknowledged breaches | 10s | `GET /api/breaches?unacknowledged=true` |
| Simulation tick | 1s (manual trigger) | `POST /api/simulation/tick` |
| Fleet summary | 30s | `GET /api/reports/summary` |

### Days of Week Bitmask Decoder
```js
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
function decodeDays(bitmask) {
  return DAYS.filter((_, i) => (bitmask >> i) & 1);
}
// decodeDays(31) → ['Mon', 'Tue', 'Wed', 'Thu', 'Fri']
// decodeDays(127) → ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
```

### Zone Color Scheme
```js
const ZONE_COLORS = {
  NO_ENTRY: '#ef4444',              // red
  TIME_RESTRICTED: '#f97316',       // orange
  VEHICLE_CLASS_RESTRICTED: '#eab308' // yellow
};
```

### Simulation Progress Bar
```js
// From GET /api/simulation/state
const progress = state.currentStep / state.totalSteps; // 0.0 to 1.0
const isRunning = state.isActive;
const isDone = !state.isActive && state.currentStep === state.totalSteps;
```

---

## 12. Key API Contract Notes

| Field | Type | Notes |
|---|---|---|
| `boundaryGeoJson` | GeoJSON object | Pass directly to map library. Not a string. |
| `restrictionStartTime` / `restrictionEndTime` | `"HH:MM"` string | IST. Consistent across all endpoints. |
| `breachTime`, `recordedAt`, `timestamp` | ISO-8601 UTC string | e.g., `"2026-05-23T16:42:00Z"` |
| `rules` in zones-at-location | array | Always an array, never `null`. May be empty `[]`. |
| `exhausted` in tick response | boolean or null | `true` only when all vehicles are COMPLETED |
| `rerouteGeoJson` | WKT string or null | `null` when road network not loaded |
| `waitUntil` | ISO-8601 or null | Non-null only when all 5 routing attempts hit curfews |
| `fields` in error | object or null | Populated only for `VALIDATION_ERROR` |

---

## 13. Enums Quick Reference

**VehicleClass:** `TWO_WHEELER`, `LCV`, `HCV`  
**RestrictionType:** `NO_ENTRY`, `TIME_RESTRICTED`, `VEHICLE_CLASS_RESTRICTED`  
**BreachType:** `NO_ENTRY`, `TIME_WINDOW`, `VEHICLE_CLASS`  
**RouteStatus:** `PENDING`, `ACTIVE`, `COMPLETED`, `CANCELLED`  
**PositionSource:** `GPS`, `SIMULATED`, `MANUAL`

---

## 14. Minimal Frontend Feature Checklist

| Feature | Endpoints needed |
|---|---|
| Vehicle list + filter | `GET /api/vehicles?vehicleClass=&isActive=` |
| Vehicle detail + current zone | `GET /api/vehicles/{id}`, `GET /api/vehicles/{id}/zones-at-location` |
| Register vehicle form | `GET /api/depots`, `POST /api/vehicles` |
| Zone map overlay | `GET /api/zones/active` |
| Zone detail + rules | `GET /api/zones/{id}` |
| Create zone with rules | `POST /api/zones` |
| Live position tracking | `POST /api/vehicles/{id}/positions`, `GET /api/vehicles/{id}/positions/latest` |
| Breach alert panel | `GET /api/breaches?unacknowledged=true`, `PUT /api/breaches/{id}/acknowledge` |
| Breach history + filter | `GET /api/breaches?vehicleId=&zoneId=&from=&to=` |
| Route planning | `POST /api/routes/validate` |
| Fleet dashboard | `GET /api/reports/summary` |
| Active restrictions overlay | `GET /api/reports/active-restrictions` |
| Zone violation chart | `GET /api/reports/zones/{zoneId}/violations` |
| Simulation control panel | `POST /api/simulation/start`, `POST /api/simulation/tick`, `GET /api/simulation/state`, `POST /api/simulation/reset` |
