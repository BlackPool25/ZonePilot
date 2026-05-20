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
     * Runs pgr_dijkstra on the Bangalore road network.
     * Returns rows of [seq, edge, cost, geom_wkt].
     */
    public List<Object[]> computeDijkstraRoute(long sourceId, long targetId) {
        return jdbcTemplate.query(
                "SELECT seq, edge, cost, ST_AsText(pt.the_geom) AS geom " +
                "FROM pgr_dijkstra(" +
                "  'SELECT id, source, target, length_m AS cost, length_m AS reverse_cost FROM blr_2po_4pgr'," +
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
}
