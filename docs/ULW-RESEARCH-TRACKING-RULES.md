# Beyond Polygons — Tracking Rules Industry Actually Needs (2026-11-01)

Source ledger: SearXNG Exa (20+ results) + 4 research lanes. Primary sources below, vendor ROI excluded.

## 1. Geometry is not just polygons
- Circle (center+radius, 50-100m outdoor): depots, customer sites. Fastest to create.
- Polygon (exact footprint): ports, yards, city zones. Removes adjacent-road false alerts.
- Corridor / polyline + buffer (route-deviation enforcement): hazmat corridors, high-value cargo, SLA. Traccar `geofence.polylineDistance` 25m default; industry highway 150-200m, urban 100-150m, last-mile use zones not corridors. Dwell-filter deviations >60s.
- Dynamic / moving geofence (safety bubble): requested, rarely supported — note as gap.

What to add to ZonePilot: `zone_restriction.geometry_type` {CIRCLE, POLYGON, CORRIDOR} + `corridor_width_m` + `corridor_route_id`. Corridor check = `ST_DWithin(position, route_geom, width)` + >60s outside → breach.

## 2. Event semantics on top of geometry (all vendors do these)
- Entry/Exit split: separate rules per direction (arrival vs theft).
- Dwell/overstay: 3-5 min job-site, 5-10 min delivery, after-hours security → detention billing. ZonePilot has 20-min CBD bay — extend to per-zone `dwell_threshold_min`.
- Speed-by-zone: per-geofence max, lowest of asset/geofence/road limit, severity bands.
- Time-window/curfew: zone + calendar (Traccar Calendar pattern). ZonePilot already has TIME_RESTRICTED + bitmask — add calendar exception dates.
- Exclusion/no-go + approach buffer: blast zones, airports, hazmat-restricted roads.
- Ignition/theft: ignition-on outside hours → security alert.
- Customer-site automation: auto check-in/out → TMS status, dock assignment, PoD trigger, failed-delivery (short dwell, no scan).

## 3. Regulatory zones (fleets comply, don't define)
- LEZ/ZEZ/CAZ/ZTL: Euro class + badge + ANPR. London ULEZ petrol Euro4/diesel Euro6 £12.50, heavies Euro VI; EU 300+ cities via UVARBox DATEX II feed. OSM `boundary=low_emission_zone`.
- Congestion/toll cordon: London £18.95, Stockholm 10-20 SEK, Singapore ERP — geofence for cost allocation.
- India no-entry windows (closest to ZonePilot): ULIP 2,364 orders, Delhi 851 road rules, Bangalore 07-11 + 16-21. Use as curfew corpus, not BTP gazettes.
- US hazmat routing 49 CFR 397 + NHMRR: Designated/Preferred/Restricted + class codes 0-9+i. OSM `hazmat=no`, `hazmat:B/C/D/E`. OSRM/GraphHopper/Valhalla avoid/favour.
- EU ADR tunnels A→E: B-explosion, C-+toxic, D-+fire, E-all DG.
- ELD/HOS (US FMCSA): auto-DRIVING ≤5 mph, 11h driving / 14h window / 60-70h / 30-min break after 8h, yard-move annotation. ZonePilot manual duty → add auto-DRIVING threshold + HOS alarms as next step.

## 4. Cold-chain (if ZonePilot ever carries food/pharma)
- FDA FSMA: written temp + pre-cool verify, records §1.912.
- EU 37/2005: EN 12830/13485/13486 recorders, ≥1yr records.
- WHO GDP: excursion = out-of-label exposure, logger proof + stability justification.
- Rule pattern: absolute band + rate-of-change + debounce + door+geofence correlation + escalation ladder + time-out-of-range log.
- Add later: `sensor_reading (tour_id, temp_c, door_open, recorded_at)` + `temp_rule (min/max, rate, debounce)` — not in current scope.

## 5. PoD standard (what to copy)
- e-CMR: reliable e-signature (uniquely linked, sole control, tamper-evident) + audit trail.
- De-facto bundle: photo + signature/code + scan, each GPS+time stamped, mandatory before close, downloadable.
- ZonePilot today: tap + GPS 100m only. Next: photo/signature optional fields, accuracy + distance stored, weak-signal rule (don't block if fix accuracy > limit).

## 6. What this means for ZonePilot now
1. Add CORRIDOR geometry + dwell threshold per zone (kills drove-past false alerts).
2. Add entry/exit split + after-hours calendar.
3. Add LEZ/Euro-class vehicle attribute + cost-allocation tag (no enforcement, just flag).
4. Add hazmat class + tunnel category to routing avoid/favour.
5. Keep ELD manual, add auto-DRIVING ≤8 km/h prompt as next slice.
6. PoD: keep tap, add photo/signature columns nullable for later overhaul.

## Sources (primary)
- Traccar geofences/events: https://www.traccar.org/geofences/ , https://www.traccar.org/events/
- FMCSA HOS/ELD: https://www.fmcsa.dot.gov/hours-service/summary-hours-service-regulations , https://eld.fmcsa.dot.gov/
- ULIP no-entry: https://library.intugine.com/reports/truck-no-entry-hours-india-report
- UVAR/LEZ: https://urbanaccessregulations.eu/ , https://transport.ec.europa.eu/transport-themes/urban-transport/urban-vehicle-access-regulations_en
- TfL ULEZ: https://tfl.gov.uk/modes/driving/ultra-low-emission-zone
- FDA FSMA: https://www.ecfr.gov/current/title-21/chapter-I/subchapter-A/part-1/subpart-O
- EU 37/2005: https://eur-lex.europa.eu/eli/reg/2005/37
- WHO GDP: TRS957 Annex5 / TRS961 Annex9 (who.int)
- ADR tunnels: https://unece.org/transport/road-transport/country-information-competent-authorities-notifications
- NHMRR: https://www.fmcsa.dot.gov/regulations/hazardous-materials/national-hazardous-materials-route-registry
- PoD e-CMR: https://treaties.un.org/doc/source/signature/xi-b-11-b_eng.pdf , https://unece.org/trade/documents/2023/10/executive-guide-e-cmr
- springdoc: https://springdoc.org/ , https://github.com/springdoc/springdoc-openapi/
