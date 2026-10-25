#!/usr/bin/env bash
# import-osm-local.sh — Clip + load Bangalore OSM into the local compose PostGIS.
#
# Replaces the docker-compose `road-network-importer` profile, which points at
# pgrouting/osm2pgrouting:latest — an image that does not exist on Docker Hub.
# Toolchain (no host root needed): debian:12-slim container with apt's
# osmium-tool (clip) + osm2pgsql (load), then SQL builds the osm2po-compatible
# blr_2po_4pgr table the backend expects (id/source/target/cost_time_sec/
# reverse_cost/the_geom, plus V13's kmh/clazz/length_m inputs).
#
# Usage:
#   ./import-osm-local.sh [SOURCE_PBF]   # default: ~/osm/southern-zone-latest.osm.pbf
#
# Prereqs: postgres service running (`docker compose up -d postgres`).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-$HOME/osm/southern-zone-latest.osm.pbf}"
CLIPPED="$SCRIPT_DIR/bangalore.osm.pbf"
BBOX="77.45,12.83,77.78,13.14"   # Bangalore metro, matches plan.md

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5434}"
DB_NAME="${DB_NAME:-ZonePilot}"
DB_USER="${DB_USER:-zonepilot}"
export PGPASSWORD="${DB_PASSWORD:-zonepilot}"

[ -f "$SRC" ] || { echo "ERROR: source PBF not found: $SRC"; exit 1; }

# SKIP_LOAD=1 reuses already-loaded planet_osm_* tables (topology rebuild only).
if [ "${SKIP_LOAD:-0}" != "1" ]; then
echo "==> [1/4] Clipping to bbox $BBOX ..."
docker run --rm \
  -v "$SRC:/data/src.pbf:ro" \
  -v "$SCRIPT_DIR:/data/out" \
  debian:12-slim \
  bash -c "apt-get update -qq && apt-get install -y -qq osmium-tool >/dev/null \
    && osmium extract --bbox $BBOX --strategy=complete_ways \
         -o /data/out/bangalore.osm.pbf /data/src.pbf --overwrite"

echo "==> [2/4] Loading highways with osm2pgsql ..."
docker run --rm --network host \
  -v "$SCRIPT_DIR:/data:ro" \
  -e PGPASSWORD="$PGPASSWORD" \
  debian:12-slim \
  bash -c "apt-get update -qq && apt-get install -y -qq osm2pgsql postgresql-client >/dev/null \
    && osm2pgsql --slim --hstore -l -C 512 \
         -d $DB_NAME -H 127.0.0.1 -P $DB_PORT -U $DB_USER \
         --drop /data/bangalore.osm.pbf"
fi # SKIP_LOAD

echo "==> [3/4] Building blr_2po_4pgr routing topology ..."
docker run --rm -i --network host -e PGPASSWORD="$PGPASSWORD" postgres:16-alpine \
  psql -h 127.0.0.1 -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL'
DROP TABLE IF EXISTS blr_2po_4pgr;
DROP TABLE IF EXISTS blr_2po_4pgr_vertices_pgr;
DROP TABLE IF EXISTS blr_noded;
DROP TABLE IF EXISTS blr_work;

-- Drivable roads only (footway/cycleway/steps excluded: vehicle routing).
CREATE TABLE blr_work AS
SELECT osm_id,
       highway,
       oneway,
       junction,
       tags->'maxspeed' AS maxspeed,
       (ST_Dump(way)).geom AS the_geom
FROM planet_osm_line
WHERE highway IN ('motorway','motorway_link','trunk','trunk_link',
  'primary','primary_link','secondary','secondary_link','tertiary','tertiary_link',
  'unclassified','residential','living_street','service','track','road');
DELETE FROM blr_work
WHERE the_geom IS NULL OR NOT ST_IsValid(the_geom)
   OR GeometryType(the_geom) <> 'LINESTRING';
ALTER TABLE blr_work ALTER COLUMN the_geom
  TYPE geometry(LineString,4326) USING the_geom::geometry(LineString,4326);
ALTER TABLE blr_work ADD COLUMN id BIGSERIAL PRIMARY KEY;

-- Endpoint-only topology fragments the graph (largest component 21k/279k
-- vertices: ways only meet end-to-end, mid-way crossings never connect).
-- Noding IS required — but one giant ST_Node chokes. Tile it: 0.02deg cells
-- (~2.2km) with 100m overlap, ST_Node per tile. Overlap duplicates yield
-- parallel edges, which Dijkstra handles (min cost wins). Attributes rejoin
-- via startpoint (lies ON the parent way; LATERAL+LIMIT 1 dedupes).
CREATE INDEX idx_blr_work_geom ON blr_work USING GIST (the_geom);
CREATE TABLE blr_tiles AS
SELECT ST_Buffer(g.geom::geography, 100)::geometry(Polygon,4326) AS geom
FROM ST_SquareGrid(0.02, ST_MakeEnvelope(77.45, 12.83, 77.78, 13.14, 4326)) g;
CREATE INDEX idx_blr_tiles_geom ON blr_tiles USING GIST (geom);
CREATE TABLE blr_noded AS
SELECT (ST_Dump(ST_Node(ST_Collect(w.the_geom)))).geom AS the_geom
FROM blr_work w JOIN blr_tiles t ON w.the_geom && t.geom AND ST_Intersects(w.the_geom, t.geom)
GROUP BY t.geom;
DELETE FROM blr_noded
WHERE the_geom IS NULL OR NOT ST_IsValid(the_geom)
   OR GeometryType(the_geom) <> 'LINESTRING';
ALTER TABLE blr_noded ALTER COLUMN the_geom
  TYPE geometry(LineString,4326) USING the_geom::geometry(LineString,4326);
ALTER TABLE blr_noded ADD COLUMN nid BIGSERIAL PRIMARY KEY;
SELECT COUNT(*) AS work_rows FROM blr_work;
SELECT COUNT(*) AS noded_rows FROM blr_noded;
CREATE TABLE blr_2po_4pgr_vertices_pgr AS
SELECT ROW_NUMBER() OVER () AS id, MIN(pt)::geometry(Point,4326) AS the_geom,
       ROUND(ST_X(MIN(pt))::numeric, 7)::text || ','
         || ROUND(ST_Y(MIN(pt))::numeric, 7)::text AS snap_key
FROM (SELECT ST_StartPoint(the_geom) AS pt FROM blr_noded
      UNION ALL
      SELECT ST_EndPoint(the_geom) FROM blr_noded) p
GROUP BY ROUND(ST_X(pt)::numeric, 7), ROUND(ST_Y(pt)::numeric, 7);
ALTER TABLE blr_2po_4pgr_vertices_pgr ADD PRIMARY KEY (id);
ALTER TABLE blr_2po_4pgr_vertices_pgr
  ADD CONSTRAINT uq_blr_vertices_snap UNIQUE (snap_key);
CREATE INDEX idx_blr_vertices_geom ON blr_2po_4pgr_vertices_pgr USING GIST (the_geom);

CREATE TABLE blr_2po_4pgr AS
SELECT
  n.nid AS id,
  vs.id AS source,
  ve.id AS target,
  w.osm_id,
  CASE w.highway
    WHEN 'motorway' THEN 11 WHEN 'motorway_link' THEN 12
    WHEN 'trunk' THEN 13 WHEN 'trunk_link' THEN 14
    WHEN 'primary' THEN 21 WHEN 'primary_link' THEN 22
    WHEN 'secondary' THEN 31 WHEN 'secondary_link' THEN 32
    WHEN 'tertiary' THEN 41 WHEN 'tertiary_link' THEN 41
    WHEN 'unclassified' THEN 51 WHEN 'residential' THEN 61
    WHEN 'living_street' THEN 62 WHEN 'service' THEN 63
    ELSE 51 END AS clazz,
  CASE
    WHEN w.maxspeed ~ '^[0-9]+(\.[0-9]+)?\s*(mph)?$'
      THEN (regexp_replace(w.maxspeed, '[^0-9.]', '', 'g'))::double precision
           * CASE WHEN w.maxspeed ~* 'mph' THEN 1.60934 ELSE 1.0 END
    ELSE NULL END AS kmh,
  ST_Length(w.the_geom::geography) / 1000.0 AS km,
  ST_Length(w.the_geom::geography) AS length_m,
  w.the_geom
FROM (SELECT nid, the_geom,
       ROUND(ST_X(ST_StartPoint(the_geom))::numeric, 7)::text || ','
         || ROUND(ST_Y(ST_StartPoint(the_geom))::numeric, 7)::text AS skey,
       ROUND(ST_X(ST_EndPoint(the_geom))::numeric, 7)::text || ','
         || ROUND(ST_Y(ST_EndPoint(the_geom))::numeric, 7)::text AS ekey
  FROM blr_noded) n
JOIN LATERAL (
  SELECT w.* FROM blr_work w
  WHERE n.the_geom && w.the_geom
    AND ST_DWithin(ST_StartPoint(n.the_geom), w.the_geom, 0.0000001)
  ORDER BY ST_Length(ST_Intersection(n.the_geom, w.the_geom)) DESC
  LIMIT 1
) w ON true
JOIN blr_2po_4pgr_vertices_pgr vs ON vs.snap_key = n.skey
JOIN blr_2po_4pgr_vertices_pgr ve ON ve.snap_key = n.ekey;

-- Same speed formula as V13__add_cost_time_sec.sql (V13 already applied/skipped).
ALTER TABLE blr_2po_4pgr ADD COLUMN cost_time_sec DOUBLE PRECISION;
UPDATE blr_2po_4pgr SET cost_time_sec = length_m / (
  CASE WHEN kmh IS NOT NULL AND kmh > 0 THEN kmh ELSE CASE clazz
    WHEN 11 THEN 90.0 WHEN 12 THEN 60.0 WHEN 13 THEN 80.0 WHEN 14 THEN 50.0
    WHEN 21 THEN 60.0 WHEN 22 THEN 40.0 WHEN 31 THEN 50.0 WHEN 32 THEN 30.0
    WHEN 41 THEN 40.0 WHEN 51 THEN 30.0 WHEN 61 THEN 30.0 WHEN 62 THEN 10.0
    WHEN 63 THEN 20.0 ELSE 30.0 END END / 3.6);

-- Oneways (explicit tag, motorways, roundabouts) are impassable backwards.
ALTER TABLE blr_2po_4pgr ADD COLUMN reverse_cost DOUBLE PRECISION;
UPDATE blr_2po_4pgr e SET reverse_cost = CASE
  WHEN w.oneway IN ('yes','1','true')
    OR (w.highway IN ('motorway','motorway_link') AND w.oneway IS DISTINCT FROM 'no')
    OR (w.junction = 'roundabout' AND w.oneway IS DISTINCT FROM 'no')
  THEN -1 ELSE e.cost_time_sec END
FROM blr_work w WHERE w.osm_id = e.osm_id;

ALTER TABLE blr_2po_4pgr ADD PRIMARY KEY (id);
CREATE INDEX idx_blr_2po_4pgr_cost_time_sec ON blr_2po_4pgr (cost_time_sec);
CREATE INDEX idx_blr_2po_4pgr_geom ON blr_2po_4pgr USING GIST (the_geom);

-- Match-rate guard: noded pieces should each yield one edge (overlap
-- duplicates make edges slightly exceed noded count — that is fine).
SELECT (SELECT COUNT(*) FROM blr_work) AS work_rows,
       (SELECT COUNT(*) FROM blr_noded) AS noded_rows,
       (SELECT COUNT(*) FROM blr_2po_4pgr) AS edge_rows;

-- Slim + intermediate tables are large; the built topology is what the app uses.
DROP TABLE blr_noded, blr_work, blr_tiles;
DROP TABLE IF EXISTS planet_osm_line, planet_osm_point, planet_osm_polygon, planet_osm_roads;
DROP TABLE IF EXISTS planet_osm_nodes, planet_osm_ways, planet_osm_rels;
SQL

echo "==> [4/4] Verifying ..."
docker run --rm --network host -e PGPASSWORD="$PGPASSWORD" postgres:16-alpine \
  psql -h 127.0.0.1 -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT (SELECT COUNT(*) FROM blr_2po_4pgr) AS edges,
          (SELECT COUNT(*) FROM blr_2po_4pgr_vertices_pgr) AS vertices,
          (SELECT COUNT(*) FROM blr_2po_4pgr WHERE cost_time_sec IS NULL) AS null_costs;"
echo "Done. Table blr_2po_4pgr is ready for the backend."
