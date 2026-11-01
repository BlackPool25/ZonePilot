# wave-1 codebase digest
- 9 routes: / dashboard, vehicles, zones, routes, breaches, reports, replay(lazy), driver, groups. Shell polls breaches+restrictions 30s.
- client.js Bearer zp-access-token, {success,data} unwrap; WS /ws + /topic/group/{id} + backfill queue.
- Demo fallback per-page to demo.js except Dashboard-empty, Groups-error, Driver/Replay-no-demo.
- No seed users in migrations; bootstrap first-register→OWNER. Test logins owner@zonepilot.io etc. Manager has no login UI (Driver only).
- Seeds V3-V6 historical, V33 removed depots, V38 strict keys, V39 availability, V40/V41 duty, groups V32 M2M.
