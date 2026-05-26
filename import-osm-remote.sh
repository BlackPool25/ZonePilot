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

echo "================================================================="
echo "Ingestion verified. Your remote PostGIS/pgRouting database is ready!"
echo "================================================================="
