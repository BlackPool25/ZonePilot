# SDD ledger — plan: docs/superpowers/plans/2026-10-25-tour-core.md

Pre-flight: T1 produces Tour/TourStop/entities+repos consumed by T3; T2 produces orderStops/StopPoint/MAX_STOPS consumed by T3. No conflicts: signatures match plan. Ruling: none needed.
- [x] T1 entities+migration (5c9dc9d)
- [x] T2 optimizer (NN+2-opt, matrix+haversine)
- [x] T3 service+controller+DTOs (TourApiTest 2/2 GREEN standalone IT,
  compliance stubbed; leg-DONE-before-next-leg for ON_TRIP eligibility)
