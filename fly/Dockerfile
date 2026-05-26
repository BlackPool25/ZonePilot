# PostGIS 3.4 + pgRouting 3.x on PostgreSQL 16
# Used for Fly.io database deployment
FROM postgis/postgis:16-3.4

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        postgresql-16-pgrouting \
        osm2pgrouting \
        osmium-tool \
    && rm -rf /var/lib/apt/lists/*

# Copy init script to install extensions on first boot
COPY init-db.sh /docker-entrypoint-initdb.d/01-init.sh
RUN chmod +x /docker-entrypoint-initdb.d/01-init.sh

EXPOSE 5432
