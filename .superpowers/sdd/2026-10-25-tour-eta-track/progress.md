# SDD ledger — plan: docs/superpowers/plans/2026-10-25-tour-eta-track.md

Pre-flight: T1 plan() consumed by T3 (legTimes); T2 travelDurationSec consumed by
T3 (actuals) and T4 indirectly; T4 track() reuses T3 response shape + legs;
T5 converts TourApiTest to real network (drops T3's stub) + standalone
GroupMembershipTest. No signature conflicts. Ruling: own-network edge-cost sum
deliberately NOT plumbed (Google/matrix fallback chain is honest and tiny).
- [x] T1 optimizer plan() single-matrix
- [x] T2 travelDurationSec + V35
- [x] T3 per-stop ETAs + advance re-anchor
- [x] T4 track endpoint
- [x] T5 real-network IT + GroupMembershipTest standalone
Full suite 152/152 green (commits 1a73854, d380732, 40d9331, 7cbf533, 2db3541).
