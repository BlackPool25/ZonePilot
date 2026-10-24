package com.zonepilot.backend.service;

/** Bangalore operating bounds shared by the HTTP path and the MQTT quality filter. */
public final class GeoBounds {

    public static final double MIN_LAT = 12.8;
    public static final double MAX_LAT = 13.2;
    public static final double MIN_LNG = 77.4;
    public static final double MAX_LNG = 77.8;

    private GeoBounds() {}

    public static boolean inBounds(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }
}
