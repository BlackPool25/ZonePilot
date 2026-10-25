package com.zonepilot.backend.it;

import com.zonepilot.backend.entity.Vehicle;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 3: a position posted via REST reaches a STOMP subscriber on
 * /topic/fleet within the 2s tick-to-pixel budget (server leg).
 */
class WsBroadcastIT extends SliceIT {

    @Test
    void restPosition_reachesFleetTopicWithinBudget() throws Exception {
        String token = registerOwner("ws-owner-" + System.nanoTime() + "@zp.test");
        Vehicle vehicle = saveVehicle("KA09-WS-" + (System.nanoTime() % 100000));

        CompletableFuture<Map> frame = new CompletableFuture<>();
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = stomp
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(15, TimeUnit.SECONDS);
        session.subscribe("/topic/fleet", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                frame.complete((Map) payload);
            }
        });

        long ts = Instant.now().toEpochMilli();
        long sentAt = System.currentTimeMillis();
        Map<String, Object> res = post("/api/vehicles/" + vehicle.getId() + "/positions",
                Map.of("lat", 12.9352, "lng", 77.6245, "speedKmh", 11.1,
                        "timestamp", Instant.ofEpochMilli(ts).toString()), token);
        assertThat(res.get("status")).isEqualTo(200);

        Map tick = frame.get(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - sentAt;
        assertThat(((Number) tick.get("vehicleId")).longValue()).isEqualTo(vehicle.getId());
        assertThat(elapsed).isLessThan(2000);
        session.disconnect();
    }
}
