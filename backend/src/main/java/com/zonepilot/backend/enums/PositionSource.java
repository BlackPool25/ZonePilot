package com.zonepilot.backend.enums;

public enum PositionSource {
    LIVE,
    SIMULATED,
    /** Real Traccar feed via MQTT (slice 3). */
    TRACCAR
}
