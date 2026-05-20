#!/bin/bash
set -e

# POSTGRES_DB is already created by the postgres image as "ZonePilot".
# This script only installs the required extensions into that database.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE EXTENSION IF NOT EXISTS postgis;
    CREATE EXTENSION IF NOT EXISTS pgrouting;
EOSQL
