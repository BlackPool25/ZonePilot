package com.zonepilot.backend.it;

import com.zonepilot.backend.entity.DispatchRoute;
import com.zonepilot.backend.entity.Driver;
import com.zonepilot.backend.entity.FleetGroup;
import com.zonepilot.backend.entity.Tour;
import com.zonepilot.backend.entity.TourStatus;
import com.zonepilot.backend.entity.Vehicle;
import com.zonepilot.backend.enums.PositionSource;
import com.zonepilot.backend.repository.PositionQuarantineRepository;
import com.zonepilot.backend.repository.VehiclePositionLogRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 3 contract: Traccar fixture JSON replayed through a real EMQX broker
 * lands in Postgres as TRACCAR positions; redelivery dedupes; bad points
 * quarantine; events don't break the pipeline.
 */
class TraccarIngestIT extends SliceIT {

    @Container
    @SuppressWarnings("rawtypes")
    static GenericContainer emqx = new GenericContainer("emqx/emqx:5.8")
            .withExposedPorts(1883)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void mqttProps(DynamicPropertyRegistry registry) {
        registry.add("mqtt.url", () ->
                "tcp://" + emqx.getHost() + ":" + emqx.getMappedPort(1883));
    }

    @Autowired
    VehiclePositionLogRepository positionLogs;
    @Autowired
    PositionQuarantineRepository quarantine;
    @Autowired
    com.zonepilot.backend.repository.TourRepository tours;
    @Autowired
    com.zonepilot.backend.repository.DriverRepository drivers;
    @Autowired
    com.zonepilot.backend.repository.FleetGroupRepository groups;
    @Autowired
    com.zonepilot.backend.repository.DispatchRouteRepository routes;
    @Autowired
    com.zonepilot.backend.service.IngestState ingestState;
    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private String brokerUrl() {
        return "tcp://" + emqx.getHost() + ":" + emqx.getMappedPort(1883);
    }

    private void publish(String topic, String payload) throws Exception {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(10);
        Exception last = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try (MqttClient client = new MqttClient(brokerUrl(), "zp-test-" + System.nanoTime())) {
                client.connect(options);
                client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
                client.disconnect();
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("mqtt publish failed", last);
    }

    private void awaitCondition(String what, Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.check()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    interface Condition {
        boolean check();
    }

    private static String fixture(String name, long vehicleId) throws Exception {
        String json = Files.readString(
                Paths.get("src/test/resources/traccar/" + name + ".json"), StandardCharsets.UTF_8);
        return json.replace("\"vehicleId\": 7", "\"vehicleId\": " + vehicleId);
    }

    @Test
    void traccarPosition_reachesDb_eventSurvives_redeliveryDedupes_teleportQuarantines() throws Exception {
        Vehicle vehicle = saveVehicle("KA09- mqtt-" + (System.nanoTime() % 100000));
        // Strict keys (V38): the tour-less pipe resolves the tour from the active route.
        wireTour(vehicle);
        String topic = "zonepilot/fleet/vehicles/" + vehicle.getId() + "/position";

        // 1. fixture position (ts = now, so quality + blind-box pass)
        long ts = Instant.now().toEpochMilli();
        String position = fixture("position", vehicle.getId())
                .replaceAll("\"ts\":\\s*\\d+", "\"ts\": " + ts);
        publish(topic, position);
        awaitCondition("position row", () ->
                positionLogs.existsByVehicleIdAndRecordedAt(vehicle.getId(), Instant.ofEpochMilli(ts)));
        var latest = positionLogs.findLatestByVehicleId(vehicle.getId()).orElseThrow();
        assertThat(latest.getSource()).isEqualTo(PositionSource.TRACCAR);
        assertThat(quarantine.countByVehicleId(vehicle.getId())).isZero();

        // 2. QoS1 redelivery of the same frame: still exactly one row
        publish(topic, position);
        Thread.sleep(2000);
        assertThat(positionLogs.findByVehicleIdAndRecordedAtBetween(
                vehicle.getId(), Instant.ofEpochMilli(ts - 1000), Instant.ofEpochMilli(ts + 1000))).hasSize(1);

        // 3. teleport far away 60s later: quarantined, no new position
        long ts2 = ts + 60_000;
        String teleport = position
                .replaceAll("\"lat\":\\s*[\\d.]+", "\"lat\": 12.9900")
                .replaceAll("\"lon\":\\s*[\\d.]+", "\"lon\": 77.6800")
                .replaceAll("\"ts\":\\s*\\d+", "\"ts\": " + ts2);
        publish(topic, teleport);
        awaitCondition("quarantine row", () -> quarantine.countByVehicleId(vehicle.getId()) == 1);
        Thread.sleep(1500);
        assertThat(positionLogs.findByVehicleIdAndRecordedAtBetween(
                vehicle.getId(), Instant.ofEpochMilli(ts2 - 1000), Instant.ofEpochMilli(ts2 + 1000))).isEmpty();
        assertThat(quarantine.findByVehicleIdOrderByCreatedAtDesc(vehicle.getId()).get(0).getReason())
                .isEqualTo("TELEPORT");

        // 4. contract event fixture: accepted without breaking the pipeline
        String event = fixture("event", vehicle.getId())
                .replaceAll("\"ts\":\\s*\\d+", "\"ts\": " + (ts2 + 1000));
        publish("zonepilot/fleet/vehicles/" + vehicle.getId() + "/event", event);
        Thread.sleep(2000);
        assertThat(positionLogs.findByVehicleIdAndRecordedAtBetween(
                vehicle.getId(), Instant.ofEpochMilli(0), Instant.ofEpochMilli(ts2 + 60_000))).hasSize(1);
    }

    /** Strict-key tour wiring: group + driver + tour + route linked as active. */
    private void wireTour(Vehicle vehicle) {
        FleetGroup g = new FleetGroup();
        g.setName("MqttGroup " + System.nanoTime());
        g.setActive(true);
        groups.save(g);
        Driver d = new Driver();
        d.setName("Mqtt Driver");
        d.setLicenceNo("LIC-MQ-" + System.nanoTime());
        drivers.save(d);
        Tour t = new Tour();
        t.setGroup(g);
        t.setVehicle(vehicle);
        t.setDriver(d);
        t.setStatus(TourStatus.ACTIVE);
        tours.save(t);
        DispatchRoute r = new DispatchRoute();
        r.setVehicle(vehicle);
        r.setDriver(d);
        r.setTour(t);
        r.setOriginPoint(gf.createPoint(new org.locationtech.jts.geom.Coordinate(77.60, 12.91)));
        r.setDestinationPoint(gf.createPoint(new org.locationtech.jts.geom.Coordinate(77.62, 12.93)));
        routes.save(r);
        vehicle.setActiveDispatchRouteId(r.getId());
        vehicles.save(vehicle);
    }

    @Test
    void redisState_roundTripsFixStreakAndRoute() {
        long vid = 999_001L;
        assertThat(ingestState.lastFix(vid)).isEmpty();
        assertThat(ingestState.offRouteCount(vid)).isZero();

        Instant ts = Instant.now();
        ingestState.saveFix(vid, 12.9352, 77.6245, ts);
        var fix = ingestState.lastFix(vid).orElseThrow();
        assertThat(fix.lat()).isEqualTo(12.9352);
        assertThat(fix.recordedAt()).isEqualTo(ts);

        // corrupt value reads as empty, never throws
        redisTemplate.opsForValue().set("zp:ingest:" + vid + ":fix", "garbage");
        assertThat(ingestState.lastFix(vid)).isEmpty();

        assertThat(ingestState.incrOffRoute(vid)).isEqualTo(1);
        assertThat(ingestState.incrOffRoute(vid)).isEqualTo(2);
        assertThat(ingestState.offRouteCount(vid)).isEqualTo(2);
        ingestState.clearOffRoute(vid);
        assertThat(ingestState.offRouteCount(vid)).isZero();

        assertThat(ingestState.lastRouteId(vid)).isEmpty();
        ingestState.saveRouteId(vid, 42L);
        assertThat(ingestState.lastRouteId(vid)).contains(42L);
        ingestState.clearRoute(vid);
        assertThat(ingestState.lastRouteId(vid)).isEmpty();
    }

    @Test
    void malformedPayload_quarantinesInsteadOfBreaking() throws Exception {
        Vehicle vehicle = saveVehicle("KA09-MAL-" + (System.nanoTime() % 100000));
        publish("zonepilot/fleet/vehicles/" + vehicle.getId() + "/position", "{not json");
        awaitCondition("quarantine row", () -> quarantine.countByVehicleId(vehicle.getId()) == 1);
        assertThat(quarantine.findByVehicleIdOrderByCreatedAtDesc(vehicle.getId()).get(0).getReason())
                .isEqualTo("MALFORMED_JSON");
    }
}
