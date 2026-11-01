# Fleet Manager + Driver Vision (locked 2026-10-25)

Student project. No real drivers — a simulation sidecar generates all telemetry.

## Decisions

1. **Depots removed.** Single fleet. Vehicles keep optional `home_base` text. Auth scoping
   moves from depot to manager-owned groups.
2. **Flat groups, many-to-many both ways.** A vehicle and a driver can each belong to
   several groups. Dispatch shows free/busy + full driver/vehicle detail at assign time.
3. **Trip = multi-stop tour** (later slice): one pickup, ordered drops, per-stop
   complete/failed + reason. This plan covers groups + dispatch on current single-leg trips.
4. **Cargo = simple manifest** (goods text + weight + qty) — later slice.
5. **PoD = tap + GPS + timestamp** — later slice.
6. **Duty = ELD hybrid** (OFF_DUTY / ON_DUTY / DRIVING-auto / BREAK / IN_TRANSIT,
   timestamped log) — later slice.
7. **Position keys**: every telemetry row carries `driver_id + trip_id + group_id` — later slice.
8. **Managers own groups.** `fleet_group.manager_id -> app_user.id`. OWNER sees all;
   MANAGER sees own groups only.
9. **Availability explicit**: AVAILABLE / RESERVED / IN_TRANSIT / MAINTENANCE / OFFLINE
   on vehicle — this plan.

## This plan (slice 1): groups + dispatch + depot removal

- `fleet_group`, `group_vehicle`, `group_driver` tables; GroupService + `/api/groups` CRUD,
  member add/remove, group detail with member availability.
- Dispatch: assign current single-leg trip to whole group (one assignment per free
  vehicle+driver pair) or solo; free/busy + licence/doc eligibility enforced.
- Manager ownership + scoping; OWNER sees all.
- Depot removal across backend (entities, DepotScope/DepotFilter, MQTT + STOMP topics,
  seeds) and frontend (depot dropdowns), replaced by `home_base` text + group scoping.
- Minimal manager Groups page.

## Later slices (separate plans)

- Slice 2: tour trips + stops + manifest + PoD tap.
- Slice 3: duty states + availability automation + telemetry keys.
- Slice 4: simulation sidecar + driver view (progress/history/ETA).
