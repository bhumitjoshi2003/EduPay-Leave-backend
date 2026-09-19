package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Redis proof of the actual Stream/ZSet mechanics NotificationDeliveryRedisSignaler,
 * NotificationDeliveryStreamConsumerConfig, and NotificationDeliveryRetryCoordinator rely on —
 * a Mockito test can prove "we called the method with the right arguments" but not "Redis
 * genuinely behaves the way we assume". Runs against whatever Redis this dev/CI environment
 * actually has reachable (the same default the app itself uses, localhost:6379) and skips
 * itself cleanly — not a failure — if none is reachable, mirroring how the existing
 * *PostgresIT suite behaves when DB_URL isn't configured.
 *
 * <p>This test talks to Redis directly (no Spring context, no PostgreSQL/Hikari dependency),
 * exercising NotificationDeliveryRetryCoordinator's real production code plus the same
 * low-level Stream operations NotificationDeliveryRedisSignaler / NotificationDeliveryStreamConsumerConfig
 * use, so a genuine XADD/XREADGROUP/XACK/XCLAIM/ZADD/ZRANGEBYSCORE round trip is proven, not assumed.
 */
class NotificationDeliveryRedisIT {
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static boolean redisReachable;

    private static final String STREAM_KEY = "test:notification:delivery:stream:it";
    private static final String GROUP = "test-notification-delivery-workers-it";
    private static final String RETRY_ZSET_KEY = "test:notification:delivery:retry:it";

    @BeforeAll
    static void connectToRedis() {
        try {
            connectionFactory = new LettuceConnectionFactory("localhost", 6379);
            connectionFactory.afterPropertiesSet();
            redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();
            redisTemplate.getConnectionFactory().getConnection().ping();
            redisReachable = true;
        } catch (Exception e) {
            redisReachable = false;
        }
    }

    @AfterAll
    static void closeConnection() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void requireRedis() {
        Assumptions.assumeTrue(redisReachable, "No reachable Redis at localhost:6379 — skipping (not failing), " +
                "mirroring how *PostgresIT skips when DB_URL is unset.");
    }

    @AfterEach
    void cleanUp() {
        if (!redisReachable) return;
        redisTemplate.delete(STREAM_KEY);
        redisTemplate.delete(RETRY_ZSET_KEY);
    }

    // ─── Retry sorted set — NotificationDeliveryRetryCoordinator's real production code ───

    @Test
    void retryCoordinator_realZSetScheduleAndDueCheckRoundTrip() {
        NotificationDeliveryRetryCoordinator coordinator = new NotificationDeliveryRetryCoordinator(redisTemplate);
        ReflectionTestUtils.setField(coordinator, "redisEnabled", true);
        ReflectionTestUtils.setField(coordinator, "retryZsetKey", RETRY_ZSET_KEY);

        assertThat(coordinator.hasDueRetries()).isFalse();

        coordinator.scheduleRetry(101L, LocalDateTime.now().minusSeconds(5)); // already due
        assertThat(coordinator.hasDueRetries()).isTrue();

        coordinator.clearDueEntries();
        assertThat(coordinator.hasDueRetries()).isFalse();
    }

    @Test
    void retryCoordinator_futureRetryIsNotYetDue() {
        NotificationDeliveryRetryCoordinator coordinator = new NotificationDeliveryRetryCoordinator(redisTemplate);
        ReflectionTestUtils.setField(coordinator, "redisEnabled", true);
        ReflectionTestUtils.setField(coordinator, "retryZsetKey", RETRY_ZSET_KEY);

        coordinator.scheduleRetry(102L, LocalDateTime.now().plusMinutes(5));

        assertThat(coordinator.hasDueRetries()).isFalse();
    }

    @Test
    void retryCoordinator_clearRetryRemovesTheEntry() {
        NotificationDeliveryRetryCoordinator coordinator = new NotificationDeliveryRetryCoordinator(redisTemplate);
        ReflectionTestUtils.setField(coordinator, "redisEnabled", true);
        ReflectionTestUtils.setField(coordinator, "retryZsetKey", RETRY_ZSET_KEY);

        coordinator.scheduleRetry(103L, LocalDateTime.now().minusSeconds(1));
        assertThat(coordinator.hasDueRetries()).isTrue();

        coordinator.clearRetry(103L);
        assertThat(coordinator.hasDueRetries()).isFalse();
    }

    // ─── Stream signal + consumer group — the exact XADD/XREADGROUP/XACK cycle used in production ───

    @Test
    void stream_signalPublishedIsReceivedByConsumerGroupAndAcknowledged() {
        createConsumerGroup();

        RecordId published = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                .in(STREAM_KEY)
                .ofMap(Map.of("schoolId", "2")));
        assertThat(published).isNotNull();

        List<MapRecord<String, String, String>> received = redisTemplate.<String, String>opsForStream()
                .read(Consumer.from(GROUP, "it-consumer-1"),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getValue()).containsEntry("schoolId", "2");

        redisTemplate.opsForStream().acknowledge(GROUP, received.get(0));

        PendingMessagesSummary summary = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP);
        assertThat(summary.getTotalPendingMessages()).isZero();
    }

    @Test
    void stream_duplicateSignalsAreBothDeliveredAndBothHarmlessToAcknowledge() {
        createConsumerGroup();

        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(STREAM_KEY).ofMap(Map.of("schoolId", "2")));
        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(STREAM_KEY).ofMap(Map.of("schoolId", "2")));

        List<MapRecord<String, String, String>> received = redisTemplate.<String, String>opsForStream()
                .read(Consumer.from(GROUP, "it-consumer-1"),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

        // Two signals were sent; the consumer receives both, and processing each is a harmless
        // no-op re-check of the (mocked-away, here) PostgreSQL claim query — there is nothing
        // Redis needs to do to make this "exactly once".
        assertThat(received).hasSize(2);
        received.forEach(m -> redisTemplate.opsForStream().acknowledge(GROUP, m));
    }

    // ─── Pending-entry recovery — a message read but never ACKed by its original consumer ───

    @Test
    void stream_pendingMessageCanBeReclaimedByAnotherConsumer() {
        createConsumerGroup();

        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(STREAM_KEY).ofMap(Map.of("schoolId", "2")));

        // "Crashed" consumer reads the message but never ACKs it.
        List<MapRecord<String, String, String>> readByDeadConsumer = redisTemplate.<String, String>opsForStream()
                .read(Consumer.from(GROUP, "it-dead-consumer"),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        assertThat(readByDeadConsumer).hasSize(1);
        RecordId stuckId = readByDeadConsumer.get(0).getId();

        PendingMessagesSummary summaryBeforeClaim = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP);
        assertThat(summaryBeforeClaim.getTotalPendingMessages()).isEqualTo(1);

        // A live consumer reclaims it via XCLAIM (minIdleTime=0 here purely so the test doesn't
        // need to sleep — production uses notification.delivery.redis.pending-claim-min-idle-ms).
        List<MapRecord<String, String, String>> reclaimed = redisTemplate.<String, String>opsForStream()
                .claim(STREAM_KEY, GROUP, "it-live-consumer", Duration.ZERO, stuckId);
        assertThat(reclaimed).hasSize(1);

        redisTemplate.opsForStream().acknowledge(GROUP, reclaimed.get(0));

        PendingMessagesSummary summaryAfterAck = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP);
        assertThat(summaryAfterAck.getTotalPendingMessages()).isZero();
    }

    private void createConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(redisTemplate.getStringSerializer().serialize(STREAM_KEY),
                            GROUP, ReadOffset.from("0"), true));
        } catch (Exception e) {
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) throw e;
        }
    }
}
