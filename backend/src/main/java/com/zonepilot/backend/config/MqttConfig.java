package com.zonepilot.backend.config;

import com.zonepilot.backend.service.TraccarIngestService;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Slice 3: Traccar (native {@code forward.type=mqtt}) → EMQX → Paho driven
 * adapter (QoS 1) → bounded {@code ExecutorChannel} (500, discard+warn) →
 * {@link TraccarIngestService}. Only active when {@code mqtt.url} is set, so
 * local dev and tests boot without a broker. MQTT redelivery covers dropped
 * frames, so saturation discards (counted + warned) instead of CallerRuns.
 */
@Configuration
@ConditionalOnExpression("'${mqtt.url:}' != ''")
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);
    static final AtomicLong rejectedMessages = new AtomicLong();

    /** Discarded-message count since boot (saturation signal for dashboards). */
    public static long rejectedCount() {
        return rejectedMessages.get();
    }

    @Bean
    public ExecutorChannel mqttInputChannel() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setRejectedExecutionHandler((task, exe) -> {
            long n = rejectedMessages.incrementAndGet();
            log.warn("mqtt-ingest saturated, discarding message (discarded={})", n);
        });
        executor.setThreadNamePrefix("mqtt-ingest-");
        executor.initialize();
        return new ExecutorChannel(executor);
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttAdapter(
            @Value("${mqtt.url}") String url,
            @Value("${mqtt.client-id:${spring.application.name:zonepilot}-ingest}") String clientId,
            @Value("${mqtt.username:}") String username,
            @Value("${mqtt.password:}") String password,
            @Value("${mqtt.topics:zonepilot/+/vehicles/+/position,zonepilot/+/vehicles/+/event,zonepilot/+/vehicles/+/status}") String[] topics,
            ExecutorChannel mqttInputChannel) {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        if (!username.isBlank()) {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            factory.setConnectionOptions(options);
        }
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(url, clientId, factory, topics);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler mqttHandler(TraccarIngestService ingest) {
        return message -> {
            String topic = (String) message.getHeaders().get("mqtt_receivedTopic");
            Object payload = message.getPayload();
            ingest.handle(topic, payload instanceof String s ? s : String.valueOf(payload));
        };
    }
}
