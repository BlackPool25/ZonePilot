package com.zonepilot.backend.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Orders tour stops for fastest total time. With a Google Routes API key the
 * origin..stop time matrix comes from computeRouteMatrix (TRAFFIC_AWARE); without
 * one it falls back to haversine at urban speed. NN construction + 2-opt.
 */
@Service
public class TourOptimizerService {

    private static final Logger log = LoggerFactory.getLogger(TourOptimizerService.class);

    static final int MAX_STOPS = 12;
    private static final String MATRIX_URL = "https://routes.googleapis.com/directions/v2:computeRouteMatrix";
    private static final String MATRIX_MASK = "originIndex,destinationIndex,duration,distanceMeters,status";
    private static final double FALLBACK_SPEED_MPS = 50_000.0 / 3600.0; // 50 km/h urban
    private static final double EARTH_M = 6_371_008.0;

    private final String apiKey;
    private final RestClient restClient;

    public TourOptimizerService(@Value("${google.routes.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().build();
    }

    public record StopPoint(double lat, double lng) {}

    /** Ordered stops plus per-leg travel seconds (origin→stop0, stop0→stop1, …). */
    public record Plan(List<StopPoint> ordered, List<Long> legTimesSec) {}

    public Plan plan(double originLat, double originLng,
                     List<StopPoint> stops, Instant departureTime) {
        if (stops.size() > MAX_STOPS) {
            throw new IllegalArgumentException("Tours are capped at " + MAX_STOPS + " stops");
        }
        if (stops.size() < 2) {
            List<StopPoint> single = List.copyOf(stops);
            long[][] m = timeMatrix(originLat, originLng, stops, departureTime);
            List<Long> legs = new ArrayList<>(single.size());
            for (int i = 0; i < single.size(); i++) {
                legs.add(m[i][i + 1]);
            }
            return new Plan(single, legs);
        }
        long[][] matrix = timeMatrix(originLat, originLng, stops, departureTime);
        List<Integer> order = nearestNeighbor(matrix);
        twoOpt(order, matrix);
        List<StopPoint> out = new ArrayList<>(order.size());
        for (int i : order) {
            out.add(stops.get(i));
        }
        // Leg times along the final order: matrix rows 0=origin, stopIdx+1.
        List<Long> legs = new ArrayList<>(order.size());
        int prev = 0;
        for (int stopIdx : order) {
            legs.add(matrix[prev][stopIdx + 1]);
            prev = stopIdx + 1;
        }
        return new Plan(List.copyOf(out), List.copyOf(legs));
    }

    public List<StopPoint> orderStops(double originLat, double originLng,
                                      List<StopPoint> stops, Instant departureTime) {
        return plan(originLat, originLng, stops, departureTime).ordered();
    }

    /** Single-leg haversine estimate (no HTTP) for mid-tour ETA refresh fallback. */
    public long estimateLegSec(double fromLat, double fromLng, double toLat, double toLng) {
        return Math.round(haversineMeters(fromLat, fromLng, toLat, toLng) / FALLBACK_SPEED_MPS);
    }

    public long totalTimeSec(double originLat, double originLng,
                             List<StopPoint> ordered, Instant departureTime) {        long[][] matrix = timeMatrix(originLat, originLng, ordered, departureTime);
        // matrix rows: 0 = origin, 1..N = ordered stops in list order
        long total = 0;
        for (int i = 0; i < ordered.size(); i++) {
            total += matrix[i][i + 1];
        }
        return total;
    }

    // matrix[i][j] = seconds from point i to point j; index 0 = origin, 1..N = stops
    private long[][] timeMatrix(double originLat, double originLng,
                                List<StopPoint> stops, Instant departureTime) {
        long[][] google = googleMatrix(originLat, originLng, stops, departureTime);
        if (google != null) {
            return google;
        }
        List<StopPoint> pts = new ArrayList<>(stops.size() + 1);
        pts.add(new StopPoint(originLat, originLng));
        pts.addAll(stops);
        long[][] m = new long[pts.size()][pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            for (int j = 0; j < pts.size(); j++) {
                m[i][j] = i == j ? 0
                        : Math.round(haversineMeters(pts.get(i), pts.get(j)) / FALLBACK_SPEED_MPS);
            }
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private long[][] googleMatrix(double originLat, double originLng,
                                  List<StopPoint> stops, Instant departureTime) {
        if (apiKey == null || apiKey.isBlank() || stops.size() + 1 > 25) {
            return null;
        }
        try {
            List<Map<String, Object>> waypoints = new ArrayList<>();
            waypoints.add(Map.of("waypoint", Map.of("location",
                    Map.of("latLng", Map.of("latitude", originLat, "longitude", originLng)))));
            for (StopPoint s : stops) {
                waypoints.add(Map.of("waypoint", Map.of("location",
                        Map.of("latLng", Map.of("latitude", s.lat(), "longitude", s.lng())))));
            }
            Map<String, Object> body = Map.of(
                    "origins", waypoints,
                    "destinations", waypoints,
                    "travelMode", "DRIVE",
                    "routingPreference", "TRAFFIC_AWARE",
                    "departureTime", departureTime.toString());
            List<Map<String, Object>> elements = restClient.post()
                    .uri(MATRIX_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", MATRIX_MASK)
                    .body(body)
                    .retrieve()
                    .body(List.class);
            if (elements == null || elements.isEmpty()) {
                return null;
            }
            int n = stops.size() + 1;
            long[][] m = new long[n][n];
            boolean[][] seen = new boolean[n][n];
            for (Map<String, Object> el : elements) {
                Map<String, Object> status = (Map<String, Object>) el.get("status");
                if (status == null || !Integer.valueOf(200).equals(((Number) status.get("code")).intValue())) {
                    return null;
                }
                int o = ((Number) el.get("originIndex")).intValue();
                int d = ((Number) el.get("destinationIndex")).intValue();
                m[o][d] = parseDurationSec(String.valueOf(el.get("duration")));
                seen[o][d] = true;
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (!seen[i][j]) {
                        return null;
                    }
                }
            }
            return m;
        } catch (Exception e) {
            log.warn("Route matrix unavailable, using haversine fallback: {}", e.getMessage());
            return null;
        }
    }

    private static List<Integer> nearestNeighbor(long[][] m) {
        int n = m.length - 1; // stops; row/col 0 = origin
        List<Integer> order = new ArrayList<>(n);
        boolean[] used = new boolean[n + 1];
        used[0] = true;
        int cur = 0;
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int j = 1; j <= n; j++) {
                if (!used[j] && (best < 0 || m[cur][j] < m[cur][best])) {
                    best = j;
                }
            }
            order.add(best - 1); // stop index
            used[best] = true;
            cur = best;
        }
        return order;
    }

    private static void twoOpt(List<Integer> order, long[][] m) {
        // order holds stop indexes; matrix index = stop index + 1, 0 = origin
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 0; i < order.size() - 1; i++) {
                for (int k = i + 1; k < order.size(); k++) {
                    if (gain(order, m, i, k) > 0) {
                        reverse(order, i, k);
                        improved = true;
                    }
                }
            }
        }
    }

    private static long leg(long[][] m, int fromStop, int toStop) {
        return m[fromStop + 1][toStop + 1];
    }

    private static long gain(List<Integer> order, long[][] m, int i, int k) {
        int a = i == 0 ? -1 : order.get(i - 1);
        int b = order.get(i);
        int c = order.get(k);
        int d = k + 1 < order.size() ? order.get(k + 1) : -1;
        long before = leg(m, a, b) + leg(m, c, d);
        long after = leg(m, a, c) + leg(m, b, d);
        return before - after;
    }

    private static void reverse(List<Integer> order, int i, int k) {
        while (i < k) {
            int t = order.get(i);
            order.set(i, order.get(k));
            order.set(k, t);
            i++;
            k--;
        }
    }

    private static long parseDurationSec(String s) {
        if (s == null || !s.endsWith("s")) {
            return -1;
        }
        try {
            return (long) Double.parseDouble(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_M * Math.asin(Math.sqrt(a));
    }

    private static double haversineMeters(StopPoint a, StopPoint b) {
        return haversineMeters(a.lat(), a.lng(), b.lat(), b.lng());
    }
}
