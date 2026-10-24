package com.zonepilot.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Slice 3: broadcasts derived state (never raw echo) to the ops board and
 * driver app on {@code /topic/depots/{depotId}}.
 */
@Service
public class TickBroadcaster {

    private final SimpMessagingTemplate messaging;

    public TickBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void tick(long depotId, long vehicleId, double lat, double lng,
                     BigDecimal speedKmh, Instant recordedAt, boolean breachDetected) {
        messaging.convertAndSend("/topic/depots/" + depotId, Map.of(
                "type", "tick",
                "vehicleId", vehicleId,
                "lat", lat,
                "lng", lng,
                "speedKmh", speedKmh == null ? 0 : speedKmh.doubleValue(),
                "recordedAt", recordedAt.toString(),
                "breachDetected", breachDetected));
    }
}
