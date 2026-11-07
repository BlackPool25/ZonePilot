# ZonePilot Demo — 15-minute guided walkthrough

Seeded by migration `V53__demo_seed.sql` (runs automatically on boot).
All demo passwords: **`demo1234`**.

## Accounts

| Role | Email | Lands on | Shows |
|---|---|---|---|
| Dispatcher (manager flow) | `demo.disp@zonepilot.io` | `/` dashboard | HSR Demo Fleet, live map, alerts |
| Driver One | `demo.driver1@zonepilot.io` | `/driver` | Active HCV tour + open breach |
| Driver Two | `demo.driver2@zonepilot.io` | `/driver` | Planned LCV tour |
| Owner | `demo.owner@zonepilot.io` | `/` dashboard | Full admin view |

Or hit **Account → Try the demo** one-click buttons on the `/login` page.

## Seeded world

- Fleet: `KA01-LCV-0004` (reserved), `KA01-LCV-0005` (in transit), `KA01-HCV-0007` (in transit) + the V4 seed fleet.
- Zones: MG Road No-Entry, Silk Board, Majestic, Koramangala, EC Phase 1 (V5) + rules (V6).
- Tour A (ACTIVE, HCV-0007): HSR → **inside MG Road zone** → Indiranagar; 3 live positions, **1 open TIME_WINDOW breach** + duty trail (ON_DUTY → DRIVING).
- Tour B (PLANNED, LCV-0004): HSR → Koramangala, no positions — the unassigned-triage state.
- Tour C (PENDING_APPROVAL, LCV-0005): 2 stops DELIVERED with GPS PoD stamps — approve it live.
- Older acknowledged VEHICLE_CLASS breach at Silk Board (triage history).

## The flow (in order)

1. **Home** (`/`, login as dispatcher): KPI cards, live map with trucks + purple zone polygons, alerts feed. Toggle map layers.
2. **Fleet** (`/vehicles`, `/groups`): HSR Demo Fleet members, availability states (IN_TRANSIT/RESERVED), group dispatch form (origin/destination → assigns free vehicles).
3. **Zones** (`/zones`): the 5 polygons + rules; click one for time windows and vehicle classes.
4. **Pre-dispatch check** (`/routes`): validate HSR → MG Road for an HCV — expect NON_COMPLIANT + alternative route.
5. **Live breach** (`/breaches`): the open MG Road breach (unacknowledged) + the older acknowledged Silk Board one. Acknowledge the open one.
6. **Driver run** (`/driver`, login as driver1): My Tours → active tour → stops with GPS PoD tap → duty buttons (OFF/ON/DRIVING/BREAK) → SOS drill.
7. **Approve** (dispatcher): `Routes` → history → open the Richmond Road route → Tour C timeline → **✓ Approve tour** → COMPLETED, vehicle released.
8. **Replay** (`/replay`): pick Tour A, 5x — watch the breach-point replay; breaches listed separately (worker is eventual).
9. **Reports** (`/reports`): breach summary per vehicle, zone violation stats, active restrictions.

## Reset

Local: `docker compose down -v && docker compose up` (fresh volume re-runs all seeds).
The seed is idempotent — safe to re-run migrations; existing demo tours are left untouched.

## Resetting just the demo tours

Delete the group and reboot the seed guard clears on next migrate of a fresh volume only.
For a live DB, delete in FK order: `zone_breach_log → vehicle_position_log → duty_log → tour_stop → tour → group_driver/group_vehicle → driver → app_user (demo.*)`, then restart the backend to re-apply V53.
