# Fleet Groups + Dispatch + Depot Removal — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Managers organise vehicles/drivers into flat M2M groups and dispatch trips to a group or solo, with depots removed.

**Architecture:** New `fleet_group` + two join tables with JPA entities; `GroupService` owns membership + availability; dispatch reuses `DriverService.checkEligible`; depot references deleted and MQTT/STOMP topics re-keyed to group-agnostic fleet topics; manager scoping replaces `DepotScope`.

**Tech Stack:** Java 21, Spring Boot 3.5, Hibernate Spatial/JTS, PostGIS, Flyway, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/fleet-groups-vision.md`

## Global Constraints

- Java 21 (`pom.xml:30`), Spring Boot 3.5.x, Jakarta persistence.
- Every position/geometry column keeps SRID 4326.
- Flyway migrations are append-only: new files only, `V32+` (`V31` is latest).
- `NULL` vehicle class rule and `ALL`-matches-all semantics in zone code stay untouched.
- Signed commits: `git commit -s`.

## Review Focus

- Vehicle in 2 groups dispatched twice: second dispatch must refuse with busy state, not double-book.
- Driver whose licence expired yesterday: group dispatch must skip them exactly like solo assign does.
- MANAGER of group A requesting group B detail: must get 403, not empty 200.
- Removed depot column: any leftover `depot_id` reference must fail at migration time, not at runtime.
- Group with zero free vehicles: dispatch returns a clear empty result, never a partial assignment.

---

### Task 1: Group entities + migration + repositories

**Files:**
- Create: `backend/src/main/java/com/zonepilot/backend/entity/FleetGroup.java`
- Create: `backend/src/main/java/com/zonepilot/backend/entity/GroupVehicle.java`
- Create: `backend/src/main/java/com/zonepilot/backend/entity/GroupDriver.java`
- Create: `backend/src/main/java/com/zonepilot/backend/repository/FleetGroupRepository.java`
- Create: `backend/src/main/resources/db/migration/V32__fleet_groups.sql`
- Test: `backend/src/test/java/com/zonepilot/backend/service/GroupMembershipTest.java`

**Interfaces:**
- Consumes: `entity/Vehicle.java`, `entity/Driver.java`, `auth/AppUser.java` (manager FK target).
- Produces: `FleetGroup{id, name, manager(AppUser), homeBase, active}`, `FleetGroupRepository.findByManagerId(Long)`, join entities with composite uniqueness `(group_id, vehicle_id)` / `(group_id, driver_id)`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void duplicateVehicleMembership_rejected() {
    groupVehicles.save(new GroupVehicle(group, vehicle));
    assertThrows(DataIntegrityViolationException.class,
        () -> groupVehicles.saveAndFlush(new GroupVehicle(group, vehicle)));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=GroupMembershipTest test` in `backend/`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement entities + `V32__fleet_groups.sql`** (`fleet_group`, `group_vehicle`, `group_driver`, unique pairs, FKs to `vehicle`, `driver`, `app_user`, indexes)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=GroupMembershipTest test` in `backend/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/entity/FleetGroup.java backend/src/main/java/com/zonepilot/backend/entity/GroupVehicle.java backend/src/main/java/com/zonepilot/backend/entity/GroupDriver.java backend/src/main/java/com/zonepilot/backend/repository/FleetGroupRepository.java backend/src/main/resources/db/migration/V32__fleet_groups.sql backend/src/test/java/com/zonepilot/backend/service/GroupMembershipTest.java
git commit -s -m "feat(groups): fleet group entities with M2M membership"
```

### Task 2: GroupService + /api/groups CRUD and availability

**Files:**
- Create: `backend/src/main/java/com/zonepilot/backend/service/GroupService.java`
- Create: `backend/src/main/java/com/zonepilot/backend/controller/GroupController.java`
- Test: `backend/src/test/java/com/zonepilot/backend/service/GroupServiceTest.java`

**Interfaces:**
- Consumes: Task 1 repositories; `repository/VehicleRepository.java`; `service/DriverService.checkEligible` (reuse, do not copy).
- Produces: `GroupService.createGroup(name, managerId)`, `addVehicle(groupId, vehicleId)`, `addDriver(groupId, driverId)`, `removeMember(...)`, `groupDetail(groupId)` (members + availability), `GET/POST /api/groups`, `POST /api/groups/{id}/vehicles`, `POST /api/groups/{id}/drivers`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void groupDetail_showsBusyVehicle() {
    // vehicle with IN_TRANSIT assignment; detail must mark it busy
    GroupDetail detail = groupService.groupDetail(groupId);
    assertTrue(detail.vehicles().stream()
        .filter(v -> v.id().equals(busyVehicleId)).findFirst().orElseThrow().busy());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=GroupServiceTest test` in `backend/`
Expected: FAIL — `GroupService` does not exist.

- [ ] **Step 3: Implement `GroupService` + `GroupController`** (availability derived from active `DispatchRoute` assignment: vehicle busy if it has an `ASSIGNED/ACCEPTED/IN_TRANSIT` route)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=GroupServiceTest test` in `backend/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/service/GroupService.java backend/src/main/java/com/zonepilot/backend/controller/GroupController.java backend/src/test/java/com/zonepilot/backend/service/GroupServiceTest.java
git commit -s -m "feat(groups): group CRUD, membership and availability"
```

### Task 3: Group / solo dispatch with free + eligibility checks

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/service/DriverService.java` (extract reusable `checkEligible` if private)
- Create: `backend/src/main/java/com/zonepilot/backend/service/GroupDispatchService.java`
- Test: `backend/src/test/java/com/zonepilot/backend/service/GroupDispatchServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `GroupService`; `DriverService.assign` semantics; `enums/AssignmentStatus.java`.
- Produces: `GroupDispatchService.dispatchToGroup(groupId, routeId)` → per-free-pair assignments; `dispatchSolo(groupId, vehicleId, driverId, routeId)`; busy/expired members skipped with reasons, never partial-failed without report.

- [ ] **Step 1: Write the failing test**

```java
@Test
void dispatchToGroup_skipsBusyAndExpired() {
    DispatchReport report = dispatchService.dispatchToGroup(groupId, routeId);
    assertEquals(1, report.assigned().size());
    assertEquals(2, report.skipped().size()); // 1 busy + 1 expired licence
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=GroupDispatchServiceTest test` in `backend/`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `GroupDispatchService`** (iterate free pairs; reuse solo `assign` per pair so eligibility stays single-sourced)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=GroupDispatchServiceTest test` in `backend/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/service/GroupDispatchService.java backend/src/test/java/com/zonepilot/backend/service/GroupDispatchServiceTest.java
git commit -s -m "feat(groups): group and solo dispatch with eligibility"
```

### Task 4: Manager ownership + scoping

**Files:**
- Modify: `backend/src/main/java/com/zonepilot/backend/entity/FleetGroup.java` (manager FK — if missed in Task 1)
- Create: `backend/src/main/java/com/zonepilot/backend/auth/GroupScope.java`
- Modify: `backend/src/main/java/com/zonepilot/backend/service/GroupService.java` (scope checks)
- Test: extend `backend/src/test/java/com/zonepilot/backend/service/GroupServiceTest.java` (cross-group 403)

**Interfaces:**
- Consumes: `auth/AppUser.java`, `auth/Role.java` (OWNER sees all).
- Produces: `GroupScope.requireGroupAccess(user, groupId)` throwing `AccessDeniedException` for non-owners of the group.

- [ ] **Step 1: Write the failing test**

```java
@Test
void managerCannotSeeForeignGroup() {
    assertThrows(AccessDeniedException.class,
        () -> groupService.groupDetailAs(otherManager, foreignGroupId));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -Dtest=GroupServiceTest test` in `backend/`
Expected: FAIL — no scoping yet.

- [ ] **Step 3: Implement `GroupScope` + wire into `GroupService`** (OWNER bypass; MANAGER must own group)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -Dtest=GroupServiceTest test` in `backend/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/zonepilot/backend/auth/GroupScope.java backend/src/main/java/com/zonepilot/backend/service/GroupService.java backend/src/test/java/com/zonepilot/backend/service/GroupServiceTest.java
git commit -s -m "feat(groups): manager ownership and group scoping"
```

### Task 5: Depot removal

**Files:**
- Modify: `entity/Vehicle.java`, `entity/Driver.java`, `auth/AppUser.java` (drop `depot`, add `homeBase` text on vehicle)
- Delete: `entity/Depot.java`, `repository/DepotRepository.java`, `service/DepotService.java`, `controller/DepotController.java` (verify exact names first)
- Modify: `auth/DepotScope.java` (delete or repurpose), `service/TraccarIngestService.java` (topic `zonepilot/fleet/vehicles/{id}/...`), `service/TickBroadcaster.java` (`/topic/fleet`), frontend depot dropdowns, seed migrations
- Create: `backend/src/main/resources/db/migration/V33__remove_depots.sql`
- Test: full unit suite must stay green; update depot-scoped tests

**Interfaces:**
- Consumes: all of Tasks 1–4 (scoping now group-based).
- Produces: zero `depot` references in `backend/src/main` (verify with grep).

- [ ] **Step 1: Write the failing test**

```java
@Test
void vehicleHasHomeBase_notDepot() {
    assertNull(vehicle.getDepot()); // must not compile after removal
}
```

(Concretely: grep must show zero main-source `depot` hits; compile is the test.)

- [ ] **Step 2: Verify it fails**

Run: `grep -ri "depot" backend/src/main --include="*.java" | head` in repo root
Expected: many hits (removal not done).

- [ ] **Step 3: Remove depots end-to-end** (entities, filter, topics, `V33` dropping `depot_id` FKs + `depot` table, seeds, frontend dropdowns → `home_base` text)

- [ ] **Step 4: Verify removal + green suite**

Run: `grep -ri "depot" backend/src/main --include="*.java" | wc -l` → `0`; then `./mvnw -q test` in `backend/`
Expected: 0 hits, BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -s -m "feat(fleet): remove depots, single fleet with home_base"
```

### Task 6: Minimal manager Groups page

**Files:**
- Create: `frontend/src/pages/Groups.jsx` (+ CSS), route in `frontend/src/router.jsx`, API helpers in `frontend/src/api/`
- Test: manual — page lists groups, opens detail with members + busy flags, dispatch button posts to group dispatch endpoint

**Interfaces:**
- Consumes: Task 2–3 endpoints.
- Produces: manager UI for groups.

- [ ] **Step 1: Add page skeleton wired to `GET /api/groups`**

- [ ] **Step 2: Verify in browser** (dev server, groups list renders)

- [ ] **Step 3: Add detail + dispatch button**

- [ ] **Step 4: Verify dispatch posts and report renders**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/Groups.jsx frontend/src/router.jsx frontend/src/api/
git commit -s -m "feat(ui): manager groups page with dispatch"
```
