#!/usr/bin/env bash
# import-osm-remote.sh — Ingest OSM data into any remote PostGIS + pgRouting database using Docker
#
# Usage:
#   ./import-osm-remote.sh <DB_HOST> <DB_NAME> <DB_USER> <DB_PASSWORD> [DB_PORT]
#
# Example (Neon):
#   ./import-osm-remote.sh ep-cool-snowflake-a5oep3w9.us-east-2.aws.neon.tech neondb neondb_owner pAsSwOrD
#
# Example (Supabase):
#   ./import-osm-remote.sh db.xxxxxxxxxxxxxxxxxxxx.supabase.co postgres postgres pAsSwOrD

set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "Usage: $0 <DB_HOST> <DB_NAME> <DB_USER> <DB_PASSWORD> [DB_PORT]"
  exit 1
fi

DB_HOST="$1"
DB_NAME="$2"
DB_USER="$3"
DB_PASSWORD="$4"
DB_PORT="${5:-5432}"
OSM_FILE="bangalore.osm.pbf"

# Resolve project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OSM_PATH="$SCRIPT_DIR/$OSM_FILE"

if [ ! -f "$OSM_PATH" ]; then
  echo "ERROR: $OSM_FILE not found in $SCRIPT_DIR."
  echo "Please make sure bangalore.osm.pbf is in the project root."
  exit 1
fi

echo "================================================================="
echo "   ZonePilot — Remote OSM Ingestion Utility"
echo "================================================================="
echo "Target Host: $DB_HOST:$DB_PORT"
echo "Database:    $DB_NAME"
echo "User:        $DB_USER"
echo "OSM File:    $OSM_FILE"
echo "-----------------------------------------------------------------"
echo "Starting osm2pgrouting via Docker container..."
echo "This will stream and compile routing topology directly to your remote DB."
echo "Depending on network speeds, this usually takes 2–5 minutes."
echo "================================================================="

docker run --rm \
  -v "$OSM_PATH:/data/$OSM_FILE:ro" \
  zonepilot-osm-importer \
  bash -c "
    echo '==> Converting binary .osm.pbf to XML .osm format...' && \
    osmium cat /data/$OSM_FILE -o /tmp/bangalore.osm && \
    echo '==> Starting osm2pgrouting import...' && \
    osm2pgrouting \
      --file /tmp/bangalore.osm \
      --conf /usr/share/osm2pgrouting/mapconfig.xml \
      --dbname \"$DB_NAME\" \
      --host \"$DB_HOST\" \
      --port \"$DB_PORT\" \
      --username \"$DB_USER\" \
      --password \"$DB_PASSWORD\" \
      --schema public \
      --prefix blr_ \
      --clean
  "

echo "-----------------------------------------------------------------"
echo "Ingestion completed successfully!"
echo "Checking segment counts..."
echo "-----------------------------------------------------------------"

# Run a check query to verify segment population
docker run --rm \
  postgres:16-alpine \
  sh -c "PGPASSWORD='$DB_PASSWORD' psql -h '$DB_HOST' -p '$DB_PORT' -U '$DB_USER' -d '$DB_NAME' -c 'SELECT COUNT(*) AS road_segments FROM blr_2po_4pgr;'"

# Backfill cost_time_sec (time-based routing cost, mirrors Flyway V13 —
# cf. fly/import-osm.sh). V13 is a no-op when the road table does not exist
# yet at app startup, so the import script must populate this column after
# loading OSM data.
echo "-----------------------------------------------------------------"
echo "Backfilling cost_time_sec (mirrors Flyway V13)..."
echo "-----------------------------------------------------------------"
docker run --rm \
  postgres:16-alpine \
  sh -c "PGPASSWORD='$DB_PASSWORD' psql -h '$DB_HOST' -p '$DB_PORT' -U '$DB_USER' -d '$DB_NAME' -v ON_ERROR_STOP=1 <<'SQL'
ALTER TABLE blr_2po_4pgr ADD COLUMN IF NOT EXISTS cost_time_sec DOUBLE PRECISION;
UPDATE blr_2po_4pgr
SET cost_time_sec = length_m / (
    CASE
        WHEN kmh IS NOT NULL AND kmh > 0 THEN kmh
        ELSE CASE clazz
            WHEN 11 THEN 90.0
            WHEN 12 THEN 60.0
            WHEN 13 THEN 80.0
            WHEN 14 THEN 50.0
            WHEN 21 THEN 60.0
            WHEN 22 THEN 40.0
            WHEN 31 THEN 50.0
            WHEN 32 THEN 30.0
            WHEN 41 THEN 40.0
            WHEN 51 THEN 30.0
            WHEN 61 THEN 30.0
            WHEN 62 THEN 10.0
            WHEN 63 THEN 20.0
            ELSE        30.0
        END
    END / 3.6
)
WHERE cost_time_sec IS NULL;
CREATE INDEX IF NOT EXISTS idx_blr_2po_4pgr_cost_time_sec ON blr_2po_4pgr (cost_time_sec);
SQL"

echo "================================================================="
echo "Ingestion verified. Your remote PostGIS/pgRouting database is ready!"
echo "================================================================="
