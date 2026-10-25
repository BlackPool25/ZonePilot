-- V36__hstore.sql
-- hstore is required by the OSM import path (osm2pgsql --hstore stores all
-- tags in planet_osm_*.tags, which the blr_2po_4pgr build reads).
-- V2 only enabled postgis + pgrouting, so imports failed with:
--   ERROR: type "hstore" does not exist
CREATE EXTENSION IF NOT EXISTS hstore;
