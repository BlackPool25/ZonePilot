package com.zonepilot.backend.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteSimilarityServiceTest {

    @Test
    void decodePolyline_roundTrip() {
        // Encoded polyline for (38.5,-120.2) (40.7,-120.95) (43.252,-126.453) — Google docs example
        String encoded = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
        String wkt = RouteSimilarityService.decodePolylineToWkt(encoded);
        assertNotNull(wkt);
        assertTrue(wkt.startsWith("LINESTRING("));
        assertTrue(wkt.contains("-120.2 38.5"));
    }

    @Test
    void decodePolyline_nullOrEmpty_returnsNull() {
        assertNull(RouteSimilarityService.decodePolylineToWkt(null));
        assertNull(RouteSimilarityService.decodePolylineToWkt(""));
        assertNull(RouteSimilarityService.decodePolylineToWkt("?"));
    }
}
