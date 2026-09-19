package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.NotificationDeliveryWorkAvailableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Fast-path wake-up for {@link NotificationDeliveryWorker}: after a notification publication's
 * enclosing business transaction commits, drop a lightweight signal onto a Redis Stream so a
 * consumer can immediately re-run the existing generic PostgreSQL claim query instead of waiting
 * for the next scheduled poll.
 *
 * <p>Deliberately fires on {@link TransactionPhase#AFTER_COMMIT} — never before. If the signal
 * were published before (or independent of) commit, a consumer could wake up and query
 * PostgreSQL for a delivery row that was never actually committed (or was rolled back), or worse,
 * a Redis failure could be mistaken for a reason to fail the business action. Neither is
 * acceptable: PostgreSQL is the durable source of truth, Redis is only a hint that speeds up
 * noticing new work. See NotificationDeliveryRedisMaintenanceScheduler for the hourly/startup
 * fallback that recovers work if this signal is ever lost.
 */
@Component
public class NotificationDeliveryRedisSignaler {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryRedisSignaler.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${notification.delivery.redis.enabled:false}")
    private boolean redisEnabled;
    @Value("${notification.delivery.redis.stream-key:notification:delivery:stream}")
    private String streamKey;

    public NotificationDeliveryRedisSignaler(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkAvailable(NotificationDeliveryWorkAvailableEvent event) {
        if (!redisEnabled) return;
        try {
            redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(streamKey)
                    .ofMap(Map.of("schoolId", String.valueOf(event.schoolId()))));
        } catch (Exception e) {
            // The publishing transaction already committed by the time this method runs — a
            // Redis failure here must never surface to the caller or affect the business action.
            // The delivery row(s) remain durably PENDING in PostgreSQL and will be picked up by
            // the next scheduled reconciliation pass (or the classic poll, if Redis mode is off).
            log.warn("Failed to publish notification delivery Redis signal for schoolId={}: {} — " +
                    "delivery remains durable in PostgreSQL and will be recovered by reconciliation.",
                    event.schoolId(), e.getClass().getSimpleName());
        }
    }
}
