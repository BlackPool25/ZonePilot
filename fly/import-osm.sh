#!/usr/bin/env bash
# import-osm.sh — Import Bangalore OSM road network into Fly.io Postgres
#
# Prerequisites:
#   - flyctl installed and authenticated (fly auth login)
#   - bangalore.osm.pbf in the project root (43 MB clipped file)
#   - Fly.io app deployed (fly deploy from fly/ directory)
#
# Usage:
#   ./fly/import-osm.sh
#
# What it does:
#   1. Copies bangalore.osm.pbf into the running Fly.io container via sftp
#   2. Runs osm2pgrouting inside the container to populate blr_2po_4pgr
#   3. Verifies the import by counting rows

set -euo pipefail

APP="zonepilot-db"
OSM_FILE="bangalore.osm.pbf"
DB_NAME="ZonePilot"

# Resolve project root (script lives in fly/)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OSM_PATH="$PROJECT_ROOT/$OSM_FILE"

if [[ ! -f "$OSM_PATH" ]]; then
  echo "ERROR: $OSM_PATH not found."
  echo "Run: osmium extract --bbox=77.45,12.83,77.78,13.14 karnataka-latest.osm.pbf -o bangalore.osm.pbf"
  exit 1
fi

echo "==> Uploading $OSM_FILE to Fly.io app: $APP"
# fly sftp shell pipes stdin commands; use a heredoc to upload the file
fly sftp shell --app "$APP" <<EOF
put $OSM_PATH /tmp/$OSM_FILE
EOF

echo "==> Running osm2pgrouting inside container"
fly ssh console --app "$APP" --command \
  "osm2pgrouting \
    --file /tmp/$OSM_FILE \
    --conf /usr/share/osm2pgrouting/mapconfig.xml \
    --dbname $DB_NAME \
    --host localhost \
    --port 5432 \
    --username \$POSTGRES_USER \
    --password \$POSTGRES_PASSWORD \
    --schema public \
    --prefix blr_ \
    --clean"

echo "==> Verifying import"
fly ssh console --app "$APP" --command \
  "psql -U \$POSTGRES_USER -d $DB_NAME -c 'SELECT COUNT(*) AS road_segments FROM blr_2po_4pgr;'"

echo "==> OSM import complete. Route validation endpoints are now available."
