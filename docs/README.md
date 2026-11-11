# Docs map

Organized by what you're trying to do (Diátaxis: tutorials teach, how-tos solve, reference states facts, explanation gives background).

## Tutorials — learn by doing

| Doc | Gets you to |
|---|---|
| [`DEMO.md`](DEMO.md) | A full guided demo: seeded logins, demo fleet, 15-minute walkthrough |
| [`../README.md`](../README.md) Quickstart | Local boot: compose → backend → frontend |

## How-to guides — solve a problem

| Doc | Solves |
|---|---|
| [`../DEPLOYMENT.md`](../DEPLOYMENT.md) | Ship to prod (Render + Fly + Vercel), OSM import, env vars, costs |
| [`70-OSM-REFRESH-RUNBOOK.md`](70-OSM-REFRESH-RUNBOOK.md) | Refresh road data, triage quarantine spikes, roll partitions |
| [`API-FRONTEND-GUIDE.md`](API-FRONTEND-GUIDE.md) | Call every endpoint from the frontend correctly |

## Reference — look up facts

| Doc | Contains |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | C4 (as flowcharts), sequences, state machines, classes, Postgres ER — all Mermaid |
| [`10-PIM-PLATFORM-INDEPENDENT.md`](10-PIM-PLATFORM-INDEPENDENT.md) | Stable platform-independent contract (scope, actors, requirements) |
| [`25-DATA-MODELS-AND-PIPELINE.md`](25-DATA-MODELS-AND-PIPELINE.md) | Table specs, ER notes, pipeline sequences, config (Wave-C amended) |

## Explanation — understand why

| Doc | Explains |
|---|---|
| [`architecture/`](architecture/) MDA suite | Context / interaction / structural / state-transition viewpoints |
| [`USER-FLOWS-AND-UX.md`](USER-FLOWS-AND-UX.md) | User flows per role (frontend rebuild spec) |
| [`ULW-RESEARCH-TRACKING-RULES.md`](ULW-RESEARCH-TRACKING-RULES.md) | What tracking rules the industry actually uses (corridors, LEZ, ELD…) |
| [`20-TELEMETRY-AND-ROADMAP-PLAN.md`](20-TELEMETRY-AND-ROADMAP-PLAN.md) | Telemetry pipeline, roadmap, pre-build gates |
| [`UPDATE_SPEC_REAL_WORLD_COMPLIANCE.md`](UPDATE_SPEC_REAL_WORLD_COMPLIANCE.md) | Real-world compliance research (design history) |
| [`40-BUILDER-PROMPT.md`](40-BUILDER-PROMPT.md) | Copy-paste builder-agent prompt (slices) |
| [`30-DEMO-HOSTING-UX-PLAN.md`](30-DEMO-HOSTING-UX-PLAN.md) | Future hosting/UX vision (fast-changing companion to stable PIM) |
| [`50-DATA-FLOW.md`](50-DATA-FLOW.md) | End-to-end data flow + component internals |
| [`archive/`](archive/) | Stale QA snapshots + original build plan — history, not current spec |
