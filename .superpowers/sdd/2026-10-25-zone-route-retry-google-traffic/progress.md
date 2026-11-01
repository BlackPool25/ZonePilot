# SDD ledger — plan: docs/superpowers/plans/2026-10-25-zone-route-retry-google-traffic.md
Pre-flight: tasks 1-4 share RouteComplianceService.computeBestRoute + TimePredictionService + RouteValidationResponse/DispatchRoute; Task2 produces TrafficEnrichment consumed by Task4; Task3 produces similarityScore consumed by Task4.
DONE Task1 iterative 1e9 retry + endpoint guard + AttemptTrace.geometry
DONE Task2 fetchTrafficEnrichment + enriched FieldMask
DONE Task3 RouteSimilarityService Hausdorff+overlap + safe decoder
DONE Task4 DTO/entity/V30/V31 + attempt persistence + enrichment wiring
VERIFY 97 unit tests pass; live PG unavailable so no live zone reroute run
