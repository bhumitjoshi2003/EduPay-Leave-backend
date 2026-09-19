package com.indraacademy.ias_management.config;

import com.indraacademy.ias_management.service.NotificationDeliveryWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

/**
 * Wires the Redis Stream consumer that gives {@link NotificationDeliveryWorker} its fast-path
 * wake-up. Only registered at all when {@code notification.delivery.redis.enabled=true} — in the
 * default (Redis mode off) configuration, none of this exists: no consumer group, no background
 * polling thread, nothing. That keeps the rollback path (the legacy 30-second scheduled poll)
 * completely unaffected by this feature's presence in the codebase.
 *
 * <p>The consumer's only job on receiving a signal is to re-run the existing generic PostgreSQL
 * claim query via {@link NotificationDeliveryWorker#runOnce()} — the stream message itself never
 * carries delivery data, so there is nothing else for this class to know how to process. Every
 * signal (fresh work, a duplicate, or a stale reclaimed message) gets exactly the same handling.
 */
@Configuration
@ConditionalOnProperty(name = "notification.delivery.redis.enabled", havingValue = "true")
public class NotificationDeliveryStreamConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryStreamConsumerConfig.class);

    @Value("${notification.delivery.redis.stream-key:notification:delivery:stream}")
    private String streamKey;
    @Value("${notification.delivery.redis.consumer-group:notification-delivery-workers}")
    private String consumerGroup;
    @Value("${notification.delivery.redis.consumer-block-ms:5000}")
    private long consumerBlockMs;
    @Value("${notification.delivery.redis.batch-size:10}")
    private int readBatchSize;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> notificationDeliveryStreamContainer(
            RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate, NotificationDeliveryWorker worker) {

        ensureConsumerGroupExists(redisTemplate);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .batchSize(readBatchSize)
                        .pollTimeout(Duration.ofMillis(consumerBlockMs))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        String consumerName = worker.instanceId();
        Subscription subscription = container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                message -> onMessage(redisTemplate, worker, message));
        log.info("Notification delivery Redis Stream consumer registered: stream={}, group={}, consumer={}",
                streamKey, consumerGroup, consumerName);

        return container;
    }

    private void onMessage(StringRedisTemplate redisTemplate, NotificationDeliveryWorker worker,
                            MapRecord<String, String, String> message) {
        try {
            // A stream signal never means "this exact record has work" — it only means "go check
            // the authoritative PostgreSQL query now". A duplicate or stale signal is harmless:
            // the claim query simply finds nothing due and runOnce() returns immediately.
            worker.runOnce();
        } catch (Exception e) {
            // runOnce() already catches everything it can attribute to a single claim pass, so
            // reaching here would be unexpected — logged defensively, never rethrown, since an
            // exception here must not prevent the ACK below (redelivering this signal endlessly
            // would not help; the next real signal, retry check, or reconciliation sweep will).
            log.error("Unexpected failure handling notification delivery Redis signal {}", message.getId(), e);
        } finally {
            try {
                redisTemplate.opsForStream().acknowledge(consumerGroup, message);
            } catch (Exception ackFailure) {
                log.warn("Failed to ACK notification delivery stream message {}: {} — " +
                        "pending-entry recovery will reclaim it if it stays unacknowledged too long.",
                        message.getId(), ackFailure.getClass().getSimpleName());
            }
        }
    }

    /** Idempotent: BUSYGROUP (the group already exists, true on every restart after the first)
     * is expected and silently ignored. mkStream=true so a brand-new deployment with no prior
     * signals ever sent doesn't fail here just because the stream key doesn't exist yet. */
    private void ensureConsumerGroupExists(StringRedisTemplate redisTemplate) {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(redisTemplate.getStringSerializer().serialize(streamKey),
                            consumerGroup, ReadOffset.from("0"), true));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Notification delivery consumer group '{}' already exists", consumerGroup);
            } else {
                log.warn("Failed to ensure notification delivery consumer group exists: {}", e.getMessage());
            }
        }
    }
}
