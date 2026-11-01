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
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 3: live pipe + scoped WS. Anonymous SUBSCRIBE to /topic/group/{id}
 * is rejected; a group JWT gets per-group ticks from POST /api/ingest/traccar.
 */
class IngestWsTest extends SliceIT {

    @Autowired
    TourRepository tours;
    @Autowired
    DriverRepository drivers;
    @Autowired
    FleetGroupRepository groups;

    private Tour mkTour() {
        FleetGroup g = new FleetGroup();
        g.setName("WsGroup " + System.nanoTime());
        g.setActive(true);
        groups.save(g);
        Driver d = new Driver();
        d.setName("Ws Driver");
        d.setLicenceNo("LIC-WS-" + System.nanoTime());
        drivers.save(d);
        Vehicle v = new Vehicle();
        v.setRegistrationNumber("KA09-WS-" + (System.nanoTime() % 100000));
        v.setVehicleClass(VehicleClass.LCV);
        v.setOwnerName("WS Owner");
        v.setIsActive(true);
        vehicles.save(v);
        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(v);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        return tours.save(t);
    }

    private WebSocketStompClient stomp() {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        return stomp;
    }

    private static StompFrameHandler drop() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        };
    }

    @Test
    void anonSubscribe_rejected() throws Exception {
        CompletableFuture<StompHeaders> error = new CompletableFuture<>();
        StompSession session = stomp()
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession s, StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable ex) {
                        error.complete(headers);
                    }
                })
                .get(15, TimeUnit.SECONDS);
        session.subscribe("/topic/group/1", drop());
        // Rejection surfaces as an ERROR frame, never a tick subscription
        // (the server closes the connection after the ERROR).
        assertThat(error.get(10, TimeUnit.SECONDS)).isNotNull();
        try {
            session.disconnect();
        } catch (IllegalStateException alreadyClosed) {
            // expected: rejected connections are already closed server-side
        }
    }

    @Test
    void groupJwtAccepted_tickDelivered() throws Exception {
        String token = registerOwner("ws-owner-" + System.nanoTime() + "@zp.test");
        Tour t = mkTour();
        Long groupId = t.getGroup().getId();

        CompletableFuture<Map> frame = new CompletableFuture<>();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = stomp()
                .connectAsync("ws://localhost:" + port + "/ws",
                        (org.springframework.web.socket.WebSocketHttpHeaders) null, connect,
                        new StompSessionHandlerAdapter() {})
                .get(15, TimeUnit.SECONDS);
        session.subscribe("/topic/group/" + groupId, new StompFrameHandler() {
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

        Map<String, Object> res = post("/api/ingest/traccar",
                Map.of("vehicleId", t.getVehicle().getId(),
                        "tourId", t.getId(),
                        "driverId", t.getDriver().getId(),
                        "groupId", groupId,
                        "lat", 12.9100, "lng", 77.6000,
                        "speedKmh", 11.1,
                        "timestamp", Instant.now().toString(),
                        "source", "LIVE"),
                token);
        assertThat(res.get("status")).isEqualTo(200);

        Map tick = frame.get(10, TimeUnit.SECONDS);
        assertThat(((Number) tick.get("vehicleId")).longValue())
                .isEqualTo(t.getVehicle().getId());
        assertThat(((Number) tick.get("groupId")).longValue()).isEqualTo(groupId);
        session.disconnect();
    }
}
