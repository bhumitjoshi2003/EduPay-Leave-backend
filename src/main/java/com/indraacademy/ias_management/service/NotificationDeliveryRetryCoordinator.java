package com.indraacademy.ias_management.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * Redis-only bookkeeping for "when does the next FAILED_RETRYABLE delivery become due" — a hint,
 * never authoritative. PostgreSQL's {@code notification_deliveries.next_attempt_at} column
 * (written by {@link NotificationDeliveryStateService#markRetryable}) remains the real retry
 * schedule; this sorted set exists only so {@link NotificationDeliveryRedisMaintenanceScheduler}
 * can cheaply ask Redis "is anything due yet?" every ~20s without ever touching PostgreSQL when
 * the answer is no.
 *
 * <p>Every method is a no-op when Redis mode is disabled, and every Redis call is wrapped so a
 * Redis failure here can never affect a delivery's PostgreSQL state or the caller's control flow
 * — the worst case of losing a zset entry is a delayed rediscovery via the hourly reconciliation
 * sweep, never a lost delivery.
 */
@Component
public class NotificationDeliveryRetryCoordinator {
    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryRetryCoordinator.class);

    private final StringRedisTemplate redisTemplate;

    @Value("${notification.delivery.redis.enabled:false}")
    private boolean redisEnabled;
    @Value("${notification.delivery.redis.retry-zset-key:notification:delivery:retry}")
    private String retryZsetKey;

    public NotificationDeliveryRetryCoordinator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Registers (or re-registers, on a repeated failure) when this delivery next becomes due. */
    public void scheduleRetry(long deliveryId, LocalDateTime retryAt) {
        if (!redisEnabled) return;
        try {
            double score = retryAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            redisTemplate.opsForZSet().add(retryZsetKey, String.valueOf(deliveryId), score);
        } catch (Exception e) {
            log.warn("Failed to schedule Redis retry signal for delivery {}: {} — hourly reconciliation will recover it.",
                    deliveryId, e.getClass().getSimpleName());
        }
    }

    /** Removes a delivery from retry tracking once it reaches a terminal state (SENT, SKIPPED,
     * or FAILED_FINAL) — a delivery that will never be retried again must not linger here. */
    public void clearRetry(long deliveryId) {
        if (!redisEnabled) return;
        try {
            redisTemplate.opsForZSet().remove(retryZsetKey, String.valueOf(deliveryId));
        } catch (Exception e) {
            log.debug("Failed to clear Redis retry signal for delivery {}: {}", deliveryId, e.getClass().getSimpleName());
        }
    }

    /** The ONLY Redis touch the maintenance scheduler's tick makes when deciding whether to wake
     * the worker — never touches PostgreSQL itself. Fails closed (reports "nothing due") on a
     * Redis error, since a Redis hiccup is not a reason to force an extra DB round trip; the
     * hourly reconciliation sweep is the backstop. */
    public boolean hasDueRetries() {
        if (!redisEnabled) return false;
        try {
            double nowScore = System.currentTimeMillis();
            Set<String> due = redisTemplate.opsForZSet()
                    .rangeByScore(retryZsetKey, Double.NEGATIVE_INFINITY, nowScore, 0, 1);
            return due != null && !due.isEmpty();
        } catch (Exception e) {
            log.debug("Failed to check Redis retry due-set: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Trims entries that were due as of the last {@link #hasDueRetries()} check — called after
     * the maintenance scheduler has already triggered a claim pass covering them. A delivery that
     * fails again will simply be re-added with a new future score by the next markRetryable. */
    public void clearDueEntries() {
        if (!redisEnabled) return;
        try {
            redisTemplate.opsForZSet().removeRangeByScore(retryZsetKey, Double.NEGATIVE_INFINITY, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Failed to trim Redis retry due-set: {}", e.getClass().getSimpleName());
        }
    }
}
