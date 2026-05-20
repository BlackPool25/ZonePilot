package com.zonepilot.backend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Road network queries against the osm2po-generated blr_2po_4pgr table.
 * Not a JPA-managed entity — uses JdbcTemplate directly.
 */
@Repository
public class RoadNetworkRepository {

    private final JdbcTemplate jdbcTemplate;

    public RoadNetworkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Finds the nearest road network node to the given coordinates using the
     * KNN <-> operator on the GIST index (faster than ST_Distance ORDER BY).
     */
    public Optional<Long> findNearestNodeId(double lat, double lng) {
        try {
            List<Long> results = jdbcTemplate.query(
                    "SELECT id FROM blr_2po_4pgr_vertices_pgr " +
                    "ORDER BY the_geom <-> ST_SetSRID(ST_Point(?, ?), 4326) LIMIT 1",
                    (rs, rowNum) -> rs.getLong("id"),
                    lng, lat);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Picks two random road network nodes within the Bangalore bounding box.
     * Returns [sourceId, targetId] or empty if road network not loaded.
     */
    public Optional<long[]> findTwoRandomNodes() {
        try {
            List<long[]> results = jdbcTemplate.query(
                    "SELECT id FROM blr_2po_4pgr_vertices_pgr " +
                    "WHERE the_geom && ST_MakeEnvelope(77.45, 12.83, 77.78, 13.14, 4326) " +
                    "ORDER BY random() LIMIT 2",
                    (rs, rowNum) -> new long[]{rs.getLong("id")});
            if (results.size() < 2) return Optional.empty();
            return Optional.of(new long[]{results.get(0)[0], results.get(1)[0]});
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Runs pgr_dijkstra on the Bangalore road network using time-based cost.
     *
     * Cost column: cost_time_sec (travel time in seconds, pre-calculated from
     * length_m and osm2po kmh / clazz-based fallback speed).
     *
     * Reverse cost: osm2po's reverse_cost column is preserved as-is.
     * A value of -1 signals a one-way street — pgRouting will not traverse
     * that direction, correctly enforcing Bangalore's one-way network.
     *
     * Returns rows of [seq, edge, cost_time_sec, geom_wkt].
     */
    public List<Object[]> computeDijkstraRoute(long sourceId, long targetId) {
        return jdbcTemplate.query(
                "SELECT seq, edge, cost, ST_AsText(pt.the_geom) AS geom " +
                "FROM pgr_dijkstra(" +
                "  'SELECT id, source, target, cost_time_sec AS cost, reverse_cost FROM blr_2po_4pgr'," +
                "  ?, ?, directed := true" +
                ") AS di " +
                "JOIN blr_2po_4pgr pt ON pt.id = di.edge " +
                "ORDER BY seq",
                (rs, rowNum) -> new Object[]{
                        rs.getInt("seq"),
                        rs.getLong("edge"),
                        rs.getDouble("cost"),
                        rs.getString("geom")
                },
                sourceId, targetId);
    }

    /**
     * Generates interpolated waypoints along a route geometry at ~30m intervals.
     * Uses ST_LineInterpolatePoints for map-snapped tick positions.
     * Returns list of [lat, lng] double arrays.
     */
    public List<double[]> interpolateWaypoints(String routeWkt, double intervalMeters) {
        // ST_Length in geography gives meters; divide by total length to get fraction per interval
        return jdbcTemplate.query(
                "WITH route AS (SELECT ST_GeomFromText(?, 4326) AS geom), " +
                "     len   AS (SELECT ST_Length(geom::geography) AS meters FROM route) " +
                "SELECT ST_Y(pt) AS lat, ST_X(pt) AS lng " +
                "FROM route, len, " +
                "     ST_DumpPoints(ST_LineInterpolatePoints(geom, LEAST(? / meters, 1.0))) AS dp(path, pt) " +
                "ORDER BY dp.path",
                (rs, rowNum) -> new double[]{rs.getDouble("lat"), rs.getDouble("lng")},
                routeWkt, intervalMeters);
    }

    /**
     * Returns the nearest OSM edge id and its geometry for a given node,
     * used by the wrong-turn injector to pick a plausible wrong edge.
     * Returns [edgeId, geom_wkt] or empty.
     */
    public Optional<Object[]> findRandomAdjacentEdge(long nodeId, long excludeEdgeId) {
        try {
            List<Object[]> results = jdbcTemplate.query(
                    "SELECT id, ST_AsText(the_geom) AS geom FROM blr_2po_4pgr " +
                    "WHERE (source = ? OR target = ?) AND id != ? AND cost_time_sec > 0 " +
                    "ORDER BY random() LIMIT 1",
                    (rs, rowNum) -> new Object[]{rs.getLong("id"), rs.getString("geom")},
                    nodeId, nodeId, excludeEdgeId);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
