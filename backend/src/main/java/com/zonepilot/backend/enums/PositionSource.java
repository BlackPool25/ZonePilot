package com.zonepilot.backend.enums;

public enum PositionSource {
    LIVE,
    SIMULATED,
    /** Real Traccar feed via MQTT (slice 3). */
    TRACCAR,
    /** Batched re-insert of real fixes at 1-30x (replay still re-triggers breach). */
    REPLAY
}
