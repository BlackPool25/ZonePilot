package com.zonepilot.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometric similarity between our pgRouting polyline and Google's polyline.
 * score = overlap * exp(-hausdorffMeters / 500). Range [0,1].
 * Cosine similarity is deliberately not used — it ignores point order and projection.
 */
@Service
public class RouteSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(RouteSimilarityService.class);

    private final JdbcTemplate jdbcTemplate;

    public RouteSimilarityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public double score(String ourWkt, String googleWkt) {
        if (ourWkt == null || googleWkt == null || ourWkt.isBlank() || googleWkt.isBlank()) return 0.0;
        try {
            Double result = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(ST_Length(ST_Intersection(ST_Buffer(ST_GeomFromText(?, 4326)::geography, 50), "
                    + "ST_GeomFromText(?, 4326)::geography)) / NULLIF(ST_Length(ST_GeomFromText(?, 4326)::geography), 0), 0) "
                    + "* EXP(-COALESCE(ST_HausdorffDistance(ST_GeomFromText(?, 4326)::geography, "
                    + "ST_GeomFromText(?, 4326)::geography), 999999) / 500.0)",
                    Double.class, ourWkt, googleWkt, googleWkt, ourWkt, googleWkt);
            return result == null ? 0.0 : Math.min(1.0, Math.max(0.0, result));
        } catch (Exception e) {
            log.warn("Similarity scoring failed: {}", e.getMessage());
            return 0.0;
        }
    }

    /** Decodes a Google encoded polyline to WKT LINESTRING (lng lat order). */
    public static String decodePolylineToWkt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            List<double[]> pts = new ArrayList<>();
            int index = 0, lat = 0, lng = 0;
            while (index < encoded.length()) {
                int[] r = decodeValue(encoded, index); lat += r[0]; index = r[1];
                r = decodeValue(encoded, index); lng += r[0]; index = r[1];
                pts.add(new double[]{lat / 1e5, lng / 1e5});
            }
            if (pts.size() < 2) return null;
            StringBuilder sb = new StringBuilder("LINESTRING(");
            for (int i = 0; i < pts.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(pts.get(i)[1]).append(" ").append(pts.get(i)[0]);
            }
            return sb.append(")").toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] decodeValue(String s, int start) {
        int result = 0, shift = 0, b, index = start;
        do {
            b = s.charAt(index++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new int[]{(result & 1) != 0 ? ~(result >> 1) : (result >> 1), index};
    }
}
