# ZonePilot Frontend

Production-grade React admin console for the ZonePilot fleet compliance platform.

## Quick Start

```bash
cd frontend
npm install
npm run dev
# Opens at http://localhost:3000
# API proxied to http://localhost:8080
```

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | React 19 + Vite |
| Routing | React Router v6.30.3 |
| Map | React Leaflet v5 + Leaflet v1.9.4 |
| Charts | Recharts v2.15.3 |
| Styling | CSS Modules + CSS custom properties |
| State | React Context + useReducer |

## Project Structure

```
src/
├── api/
│   └── client.js          — Typed API client for all backend endpoints
├── components/
│   ├── atoms/             — Badge, Button, Input/Select, Spinner/EmptyState/ErrorState, Toast
│   ├── drawers/           — VehicleDrawer, ZoneDrawer, BreachDrawer, RouteDrawer
│   ├── layout/            — AppShell, Drawer (container)
│   └── map/               — LiveMap (shared Leaflet map component)
├── context/
│   └── AppContext.jsx      — Global state: drawer, map, toasts, breach count
├── data/
│   └── demo.js            — Demo/fallback data matching exact API shapes
├── pages/
│   ├── Dashboard.jsx      — Map-first live view
│   ├── Vehicles.jsx       — Fleet table with filters + RegisterVehicleModal
│   ├── Zones.jsx          — Zone list + map + create form
│   ├── Routes.jsx         — Route validation form + history
│   ├── Breaches.jsx       — Breach log with acknowledge
│   ├── Reports.jsx        — Fleet analytics + charts
│   └── Simulation.jsx     — Scenario control panel + tick loop
├── styles/
│   └── global.css         — Design tokens (CSS vars) + reset
├── utils/
│   └── helpers.js         — Domain helpers, formatters, WKT/GeoJSON converters
├── router.jsx             — createBrowserRouter route tree
└── main.jsx               — Entry point
```

## Design System

All design tokens are CSS custom properties in `src/styles/global.css`:

```css
--brand-500: #2563eb      /* selection, focus, primary actions */
--green-500: #16a34a      /* compliant, active, acknowledged */
--amber-500: #d97706      /* warning, time-restricted zones */
--red-500:   #dc2626      /* breach, no-entry, critical */
--surface-0: #ffffff      /* cards, panels */
--surface-1: #f8f9fb      /* page background */
```

Zone type colors:
- `NO_ENTRY` → red (`#dc2626`)
- `TIME_RESTRICTED` → orange (`#f97316`)
- `VEHICLE_CLASS_RESTRICTED` → yellow (`#eab308`)

## State Management

`AppContext` (React Context + `useReducer`) manages:

| State | Purpose |
|---|---|
| `drawer` | Currently open drawer: `{ type, entityId, data }` |
| `mapCenter / mapZoom` | Shared map position (synced via `MapController`) |
| `mapLayers` | Layer visibility toggles |
| `selectedVehicleId / selectedZoneId` | Map selection highlight |
| `toasts` | Notification queue (auto-dismissed after 4s) |
| `unacknowledgedBreachCount` | Polled every 30s, shown in nav badge |
| `activeRestrictionsCount` | Polled every 30s, shown in top bar |
| `navExpanded` | Left nav collapsed/expanded |

Page-level state (fetch results, filters, form values) is local `useState` within each page component.

## API Client

`src/api/client.js` wraps all backend endpoints. Every call:
1. Checks `json.success` — throws a typed error with `code`, `fields`, `status` if false
2. Returns `json.data` directly

```js
import { vehiclesApi, breachesApi } from './api/client.js';

// List with filters
const vehicles = await vehiclesApi.list({ vehicleClass: 'HCV', isActive: true });

// Acknowledge breach — handles 409 Conflict
try {
  await breachesApi.acknowledge(id);
} catch (err) {
  if (err.status === 409) { /* already acknowledged */ }
}
```

## Demo Data Fallback

When the backend is offline, all pages fall back to `src/data/demo.js`. The demo data matches the exact API response shapes from `FRONTEND_API_REPORT.md`. No special configuration needed — the fallback is automatic via `Promise.allSettled`.

## Pages

### Dashboard (`/`)
- Full-height Leaflet map as primary surface
- Floating layer toggles (Vehicles, Zones, Breaches, Routes, Labels)
- Floating metric strip (dark pill, reference image style): Active Vehicles · Active Zones · Open Breaches
- Live activity feed (top-right): recent unacknowledged breaches
- Zone legend (bottom-left)
- Polls vehicle positions every 10s
- Click vehicle → opens VehicleDrawer
- Click zone polygon → opens ZoneDrawer
- Click breach marker → opens BreachDrawer

### Vehicles (`/vehicles`)
- Searchable, filterable table (class, status, depot)
- Register Vehicle modal with inline validation
- Row click → VehicleDrawer

### Zones (`/zones`)
- Split layout: zone list sidebar + full-height map
- Create Zone panel (slides in from right) with:
  - Name, description, restriction type
  - GeoJSON boundary textarea
  - Progressive disclosure: optional rule with class, time window, days picker
- Zone card click → ZoneDrawer + map highlight

### Routes (`/routes`)
- Route validation form with Bangalore landmark presets
- Inline compliance result with violation list
- Route history panel (right side) for selected vehicle
- Row click → RouteDrawer

### Breaches (`/breaches`)
- Segmented tab filter: All / Unacknowledged / Acknowledged
- Additional filters: vehicle, zone, breach type
- Unacknowledged rows highlighted in red
- Inline Acknowledge button per row
- Row click → BreachDrawer

### Reports (`/reports`)
- KPI strip: Total Breaches, Unacknowledged, Vehicles with Breaches, Active Restrictions
- Fleet breach summary table (sortable by total breaches)
- Pie chart: breaches by type
- Bar chart: top offenders
- Active restrictions list
- Zone violation drill-down (select zone → stacked bar chart by vehicle class)

### Simulation (`/simulation`)
- Split layout: control panel (left) + map (right)
- Scenario selection with toggle cards (A, B, C)
- Controls: Start, Step (single tick), Auto (1 tick/sec), Reset
- Vehicle progress bars with step counter
- Breach log (real-time, last 10 events)
- Tick counter display
- Exhausted banner when all vehicles complete

## Polling Intervals

| Data | Interval | Endpoint |
|---|---|---|
| Dashboard positions | 10s | `GET /api/vehicles/{id}/positions/latest` |
| Breach count (nav badge) | 30s | `GET /api/breaches?unacknowledged=true` |
| Active restrictions (top bar) | 30s | `GET /api/reports/active-restrictions` |
| Simulation tick (auto mode) | 1s | `POST /api/simulation/tick` |

## Accessibility

- Semantic HTML: `<nav>`, `<main>`, `<header>`, `<aside>`, `<table>`, `<form>`, `<fieldset>`
- ARIA: `aria-label`, `aria-pressed`, `aria-selected`, `aria-live`, `aria-invalid`, `aria-describedby`, `role="alert"`, `role="status"`, `role="progressbar"`
- Keyboard: all interactive elements are focusable; drawer closes on Escape; table rows respond to Enter
- Focus management: drawer close button receives focus when drawer opens
- Screen reader: `.sr-only` for icon-only buttons; `aria-hidden` on decorative elements
- Color: status never conveyed by color alone (always paired with text/icon)
- Contrast: all text meets WCAG AA (4.5:1 minimum)

## Environment

The Vite dev server proxies `/api/*` to `http://localhost:8080`. No CORS configuration needed in development.

For production, configure your reverse proxy (nginx/caddy) to route `/api/*` to the Spring Boot backend.
