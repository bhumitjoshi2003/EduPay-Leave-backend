package com.indraacademy.ias_management.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * All the Redis-mode-only background maintenance for notification delivery, in one place:
 *
 * <ul>
 *   <li>Startup recovery — one claim pass on boot, so a delivery that committed to PostgreSQL
 *       while Redis (or the whole backend) was unavailable isn't stranded until the next signal.
 *   <li>The retry-due check — a lightweight, Redis-only poll (every few seconds) that only
 *       touches PostgreSQL when the retry sorted set says something is actually due.
 *   <li>Stream pending-entry recovery — reclaims messages a crashed consumer read but never
 *       ACKed, so they don't stay stuck against a consumer identity that will never come back.
 *   <li>Hourly reconciliation — the rare safety-net sweep for anything all of the above missed
 *       (a lost signal, a Redis restart that dropped stream state, etc).
 * </ul>
 *
 * <p>Every method here is a no-op when {@code notification.delivery.redis.enabled=false} — in
 * that configuration the legacy 30-second {@link NotificationDeliveryWorker#poll()} already
 * provides continuous recovery on its own, so none of this needs to also run.
 */
@Component
public class NotificationDeliveryRedisMaintenanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryRedisMaintenanceScheduler.class);

    private final NotificationDeliveryWorker worker;
    private final NotificationDeliveryRetryCoordinator retryCoordinator;
    private final StringRedisTemplate redisTemplate;

    @Value("${notification.delivery.redis.enabled:false}")
    private boolean redisEnabled;
    @Value("${notification.delivery.redis.stream-key:notification:delivery:stream}")
    private String streamKey;
    @Value("${notification.delivery.redis.consumer-group:notification-delivery-workers}")
    private String consumerGroup;
    @Value("${notification.delivery.redis.pending-claim-min-idle-ms:60000}")
    private long pendingClaimMinIdleMs;

    public NotificationDeliveryRedisMaintenanceScheduler(NotificationDeliveryWorker worker,
                                                          NotificationDeliveryRetryCoordinator retryCoordinator,
                                                          StringRedisTemplate redisTemplate) {
        this.worker = worker;
        this.retryCoordinator = retryCoordinator;
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!redisEnabled) return;
        log.info("Notification delivery Redis mode enabled — running startup recovery claim pass");
        worker.runOnce();
    }

    /** Redis-only check; only reaches PostgreSQL (via runOnce) when something is actually due. */
    @Scheduled(fixedDelayString = "${notification.delivery.redis.retry-check-interval-ms:20000}")
    public void checkDueRetriesAndPendingEntries() {
        if (!redisEnabled) return;
        if (retryCoordinator.hasDueRetries()) {
            worker.runOnce();
            retryCoordinator.clearDueEntries();
        }
        reclaimStalePendingMessages();
    }

    @Scheduled(fixedDelayString = "${notification.delivery.reconciliation-interval-ms:3600000}")
    public void hourlyReconciliation() {
        if (!redisEnabled) return;
        log.info("Notification delivery hourly reconciliation claim pass starting");
        worker.runOnce();
    }

    /** Reclaims stream messages a crashed/replaced consumer read but never ACKed, so they don't
     * stay pending forever against a consumer identity that will never reconnect. Purely a
     * latency guard: even an entry that's never reclaimed poses no durability risk, since the
     * underlying PostgreSQL row is still recovered by the next real signal, retry check, or the
     * hourly sweep above — this only shortens how long that can take. */
    private void reclaimStalePendingMessages() {
        try {
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(streamKey, consumerGroup);
            if (summary == null || summary.getTotalPendingMessages() == 0) return;

            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(streamKey, consumerGroup, Range.unbounded(), 50);
            List<RecordId> staleIds = pending.stream()
                    .filter(m -> m.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofMillis(pendingClaimMinIdleMs)) >= 0)
                    .map(PendingMessage::getId)
                    .toList();
            if (staleIds.isEmpty()) return;

            List<MapRecord<String, String, String>> claimed = redisTemplate.<String, String>opsForStream().claim(
                    streamKey, consumerGroup, worker.instanceId(),
                    Duration.ofMillis(pendingClaimMinIdleMs), staleIds.toArray(new RecordId[0]));
            if (claimed.isEmpty()) return;

            log.info("Reclaimed {} stale notification delivery stream message(s)", claimed.size());
            worker.runOnce();
            for (MapRecord<String, String, String> record : claimed) {
                redisTemplate.opsForStream().acknowledge(consumerGroup, record);
            }
        } catch (Exception e) {
            log.debug("Notification delivery pending-entry recovery check failed: {}", e.getClass().getSimpleName());
        }
    }
}
