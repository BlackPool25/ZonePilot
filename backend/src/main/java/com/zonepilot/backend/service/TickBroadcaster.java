package com.zonepilot.backend.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Live pipe broadcaster: derived state (never raw echo) per fleet group on
 * {@code /topic/group/{id}}. JWT-scoped subscribe enforced in WsConfig.
 */
@Service
public class TickBroadcaster {

    private final SimpMessagingTemplate messaging;

    public TickBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void tick(long groupId, long vehicleId, double lat, double lng,
                     BigDecimal speedKmh, Instant recordedAt, boolean breachDetected) {
        messaging.convertAndSend("/topic/group/" + groupId, Map.of(
                "type", "tick",
                "groupId", groupId,
                "vehicleId", vehicleId,
                "lat", lat,
                "lng", lng,
                "speedKmh", speedKmh == null ? 0 : speedKmh.doubleValue(),
                "recordedAt", recordedAt.toString(),
                "breachDetected", breachDetected));
    }
}
