package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;

/**
 * Nightly platform-wide notification retention: removes notifications older than 60 days.
 *
 * <p>user_notifications.notification_id references notifications WITHOUT a DB cascade, so each
 * expired notification's children are deleted first — delivery rows, then inbox rows, then the
 * notification — exactly as NotificationService's single-notice delete already does. Works in
 * bounded batches, each its own transaction: a failed batch rolls back only itself, earlier
 * batches stay committed, and the next run simply resumes (the job is idempotent).
 */
@Component
public class NotificationRetentionCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(NotificationRetentionCleanupJob.class);
    static final Period RETENTION = Period.ofDays(60);

    private final NotificationRepository notifications;
    private final UserNotificationRepository inbox;
    private final NotificationDeliveryRepository deliveries;
    private final TransactionTemplate transaction;
    private final int batchSize;

    public NotificationRetentionCleanupJob(NotificationRepository notifications, UserNotificationRepository inbox,
                                           NotificationDeliveryRepository deliveries,
                                           PlatformTransactionManager transactionManager,
                                           @Value("${notification.cleanup.batch-size:500}") int batchSize) {
        this.notifications = notifications;
        this.inbox = inbox;
        this.deliveries = deliveries;
        this.transaction = new TransactionTemplate(transactionManager);
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldNotifications() {
        purgeCreatedBefore(LocalDateTime.now().minus(RETENTION));
    }

    /** @return the number of notifications deleted. Never throws — a failed batch is logged. */
    public int purgeCreatedBefore(LocalDateTime cutoff) {
        int total = 0;
        while (true) {
            Integer deleted;
            try {
                deleted = transaction.execute(status -> deleteBatch(cutoff));
            } catch (RuntimeException failure) {
                log.error("Notification retention cleanup stopped after {} notification(s); the failed batch was "
                        + "rolled back and will be retried on the next run", total, failure);
                break;
            }
            if (deleted == null || deleted == 0) break;
            total += deleted;
            if (deleted < batchSize) break;
        }
        log.info("Notification retention cleanup removed {} notification(s) created before {}", total, cutoff);
        return total;
    }

    private int deleteBatch(LocalDateTime cutoff) {
        List<Long> ids = notifications.findIdsCreatedBefore(cutoff, PageRequest.of(0, batchSize));
        if (ids.isEmpty()) return 0;
        deliveries.deleteByNotificationIdIn(ids);
        inbox.deleteByNotificationIdIn(ids);
        return notifications.deleteByIdIn(ids);
    }
}
