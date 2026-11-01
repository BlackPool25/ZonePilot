package com.zonepilot.backend.it;

import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.VehicleClass;
import com.zonepilot.backend.repository.DriverRepository;
import com.zonepilot.backend.repository.FleetGroupRepository;
import com.zonepilot.backend.repository.TourRepository;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live pipe: a fix posted via the HTTP ingest entry reaches a JWT-scoped
 * STOMP subscriber on /topic/group/{id} within the 2s tick-to-pixel budget
 * (server leg).
 */
class WsBroadcastIT extends SliceIT {

    @Autowired
    TourRepository tours;
    @Autowired
    DriverRepository drivers;
    @Autowired
    FleetGroupRepository groups;

    @Test
    void ingestFix_reachesGroupTopicWithinBudget() throws Exception {
        String token = registerOwner("ws-owner-" + System.nanoTime() + "@zp.test");
        FleetGroup g = new FleetGroup();
        g.setName("BcastGroup " + System.nanoTime());
        g.setActive(true);
        groups.save(g);
        Driver d = new Driver();
        d.setName("Bcast Driver");
        d.setLicenceNo("LIC-BC-" + System.nanoTime());
        drivers.save(d);
        Vehicle vehicle = saveVehicle("KA09-WS-" + (System.nanoTime() % 100000));
        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(vehicle);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        tours.save(t);

        CompletableFuture<Map> frame = new CompletableFuture<>();
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = stomp
                .connectAsync("ws://localhost:" + port + "/ws",
                        (org.springframework.web.socket.WebSocketHttpHeaders) null, connect,
                        new StompSessionHandlerAdapter() {})
                .get(15, TimeUnit.SECONDS);
        session.subscribe("/topic/group/" + g.getId(), new StompFrameHandler() {
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
        Map<String, Object> res = post("/api/ingest/traccar",
                Map.of("vehicleId", vehicle.getId(),
                        "tourId", t.getId(),
                        "driverId", d.getId(),
                        "groupId", g.getId(),
                        "lat", 12.9100, "lng", 77.6000,
                        "speedKmh", 11.1,
                        "timestamp", Instant.ofEpochMilli(ts).toString(),
                        "source", "LIVE"),
                token);
        assertThat(res.get("status")).isEqualTo(200);

        Map tick = frame.get(10, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - sentAt;
        assertThat(((Number) tick.get("vehicleId")).longValue()).isEqualTo(vehicle.getId());
        assertThat(((Number) tick.get("groupId")).longValue()).isEqualTo(g.getId());
        assertThat(elapsed).isLessThan(2000);
        session.disconnect();
    }
}
