package com.zonepilot.backend.service;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Epic 4: Calls Google Routes API (computeRoutes) to predict traffic-aware
 * arrival times at zone entry points along a planned route.
 *
 * Waypoint pruning strategy (max 25 intermediates per API contract):
 *   1. Always include zone entry/exit points (curfew prediction requires these).
 *   2. Fill remaining slots with evenly-spaced route geometry points.
 *   3. All intermediate points use via:true so they don't split legs.
 *
 * Duration format: protobuf Duration string, e.g. "123s" or "3.5s".
 */
@Service
public class TimePredictionService {

    private static final Logger log = LoggerFactory.getLogger(TimePredictionService.class);

    private static final String ROUTES_API_URL = "https://routes.googleapis.com/directions/v2:computeRoutes";
    private static final int MAX_INTERMEDIATES = 25;
    // Field mask: only request what we need — duration per leg
    private static final String FIELD_MASK = "routes.legs.duration,routes.duration";

    private final String apiKey;
    private final RestClient restClient;

    public TimePredictionService(@Value("${google.routes.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Predicts traffic-aware travel duration (seconds) for the given route.
     *
     * @param routeGeometry  the planned route LineString (SRID 4326)
     * @param zoneEntryPoints lat/lng pairs of zone boundary entry points (priority waypoints)
     * @param departureTime  when the vehicle departs (used for traffic model)
     * @return total predicted duration in seconds, or -1 if API unavailable/unconfigured
     */
    public long predictTotalDurationSec(LineString routeGeometry,
                                         List<double[]> zoneEntryPoints,
                                         Instant departureTime) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Google Routes API key not configured — skipping time prediction");
            return -1;
        }

        try {
            Coordinate[] coords = routeGeometry.getCoordinates();
            if (coords.length < 2) return -1;

            Coordinate origin = coords[0];
            Coordinate destination = coords[coords.length - 1];

            List<Map<String, Object>> intermediates = buildIntermediates(coords, zoneEntryPoints);

            Map<String, Object> requestBody = Map.of(
                    "origin", waypointFromCoord(origin),
                    "destination", waypointFromCoord(destination),
                    "intermediates", intermediates,
                    "travelMode", "DRIVE",
                    "routingPreference", "TRAFFIC_AWARE",
                    "departureTime", departureTime.toString()
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(ROUTES_API_URL)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            return parseTotalDuration(response);

        } catch (Exception e) {
            log.warn("Google Routes API call failed: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Predicts per-leg durations so we can determine arrival time at each zone entry point.
     * Returns a list of cumulative arrival times (seconds from departure) for each intermediate.
     * Returns empty list if API unavailable.
     */
    public List<Long> predictCumulativeArrivalsSec(LineString routeGeometry,
                                                    List<double[]> zoneEntryPoints,
                                                    Instant departureTime) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }

        try {
            Coordinate[] coords = routeGeometry.getCoordinates();
            if (coords.length < 2) return List.of();

            Coordinate origin = coords[0];
            Coordinate destination = coords[coords.length - 1];

            // For per-leg timing, zone entry points must NOT be via:true
            // so each becomes a leg boundary. We limit to MAX_INTERMEDIATES.
            List<Map<String, Object>> intermediates = buildZoneIntermediates(zoneEntryPoints);

            Map<String, Object> requestBody = Map.of(
                    "origin", waypointFromCoord(origin),
                    "destination", waypointFromCoord(destination),
                    "intermediates", intermediates,
                    "travelMode", "DRIVE",
                    "routingPreference", "TRAFFIC_AWARE",
                    "departureTime", departureTime.toString()
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(ROUTES_API_URL)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", "routes.legs.duration")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            return parseLegCumulativeArrivals(response);

        } catch (Exception e) {
            log.warn("Google Routes API leg prediction failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds intermediate waypoints list with via:true.
     * Priority: zone entry points first, then evenly-spaced route geometry points.
     * Total capped at MAX_INTERMEDIATES.
     */
    private List<Map<String, Object>> buildIntermediates(Coordinate[] coords,
                                                          List<double[]> zoneEntryPoints) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Add zone entry points first (priority)
        int zoneSlots = Math.min(zoneEntryPoints.size(), MAX_INTERMEDIATES);
        for (int i = 0; i < zoneSlots; i++) {
            double[] pt = zoneEntryPoints.get(i);
            result.add(viaWaypointFromLatLng(pt[0], pt[1]));
        }

        // Fill remaining slots with evenly-spaced route geometry points
        int remaining = MAX_INTERMEDIATES - result.size();
        if (remaining > 0 && coords.length > 2) {
            int step = Math.max(1, (coords.length - 2) / remaining);
            for (int i = step; i < coords.length - 1 && result.size() < MAX_INTERMEDIATES; i += step) {
                result.add(viaWaypointFromLatLng(coords[i].getY(), coords[i].getX()));
            }
        }

        return result;
    }

    /**
     * Builds non-via intermediate waypoints for zone entry points only.
     * These create leg boundaries so we can read per-leg duration.
     */
    private List<Map<String, Object>> buildZoneIntermediates(List<double[]> zoneEntryPoints) {
        List<Map<String, Object>> result = new ArrayList<>();
        int limit = Math.min(zoneEntryPoints.size(), MAX_INTERMEDIATES);
        for (int i = 0; i < limit; i++) {
            double[] pt = zoneEntryPoints.get(i);
            result.add(waypointFromLatLng(pt[0], pt[1]));
        }
        return result;
    }

    private Map<String, Object> waypointFromCoord(Coordinate coord) {
        return waypointFromLatLng(coord.getY(), coord.getX());
    }

    private Map<String, Object> waypointFromLatLng(double lat, double lng) {
        return Map.of("location", Map.of(
                "latLng", Map.of("latitude", lat, "longitude", lng)));
    }

    private Map<String, Object> viaWaypointFromLatLng(double lat, double lng) {
        return Map.of(
                "via", true,
                "location", Map.of("latLng", Map.of("latitude", lat, "longitude", lng)));
    }

    /**
     * Parses total route duration from response.
     * Duration format: "123s" or "3.5s" (protobuf Duration).
     */
    @SuppressWarnings("unchecked")
    private long parseTotalDuration(Map<String, Object> response) {
        if (response == null) return -1;
        List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
        if (routes == null || routes.isEmpty()) return -1;
        String duration = (String) routes.get(0).get("duration");
        return parseDurationString(duration);
    }

    /**
     * Parses per-leg durations and returns cumulative arrival seconds.
     */
    @SuppressWarnings("unchecked")
    private List<Long> parseLegCumulativeArrivals(Map<String, Object> response) {
        List<Long> arrivals = new ArrayList<>();
        if (response == null) return arrivals;
        List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
        if (routes == null || routes.isEmpty()) return arrivals;
        List<Map<String, Object>> legs = (List<Map<String, Object>>) routes.get(0).get("legs");
        if (legs == null) return arrivals;

        long cumulative = 0;
        for (Map<String, Object> leg : legs) {
            long legSec = parseDurationString((String) leg.get("duration"));
            cumulative += legSec;
            arrivals.add(cumulative);
        }
        return arrivals;
    }

    /**
     * Parses protobuf Duration string "123s" or "3.5s" → seconds (long).
     */
    static long parseDurationString(String duration) {
        if (duration == null || !duration.endsWith("s")) return 0;
        try {
            String numeric = duration.substring(0, duration.length() - 1);
            return (long) Double.parseDouble(numeric);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
