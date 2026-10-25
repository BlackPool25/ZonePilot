# SDD ledger — plan: docs/superpowers/plans/2026-10-25-tour-eta-track.md

Pre-flight: T1 plan() consumed by T3 (legTimes); T2 travelDurationSec consumed by
T3 (actuals) and T4 indirectly; T4 track() reuses T3 response shape + legs;
T5 converts TourApiTest to real network (drops T3's stub) + standalone
GroupMembershipTest. No signature conflicts. Ruling: own-network edge-cost sum
deliberately NOT plumbed (Google/matrix fallback chain is honest and tiny).
- [ ] T1 optimizer plan() single-matrix
- [ ] T2 travelDurationSec + V35
- [ ] T3 per-stop ETAs + advance re-anchor
- [ ] T4 track endpoint
- [ ] T5 real-network IT + GroupMembershipTest standalone
