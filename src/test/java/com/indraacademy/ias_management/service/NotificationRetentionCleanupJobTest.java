package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the retention cleanup against a real database with the FKs Hibernate generates (H2):
 * user_notifications → notifications has no cascade, exactly like production (V1 baseline).
 * Not wrapped in a test transaction, so each cleanup batch really commits.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = Notification.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationRetentionCleanupJobTest {

    @Autowired NotificationRepository notifications;
    @Autowired UserNotificationRepository inbox;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    TransactionTemplate tx;
    static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 2, 0);
    static final LocalDateTime CUTOFF = NOW.minus(NotificationRetentionCleanupJob.RETENTION);

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.execute("DELETE FROM notification_deliveries");
        jdbc.execute("DELETE FROM user_notifications");
        jdbc.execute("DELETE FROM notifications");
    }

    private NotificationRetentionCleanupJob job(int batchSize) {
        return new NotificationRetentionCleanupJob(notifications, inbox, deliveries, transactionManager, batchSize);
    }

    @Test
    void rootCause_thePreviousDeleteAllApproachIsRejectedByTheInboxForeignKeyAndRollsBackEverything() {
        long old = publish(1L, NOW.minusDays(70), "S1", "S2");
        long alsoOld = publish(1L, NOW.minusDays(75));

        // What cleanupOldNotifications used to do: load old notifications, deleteAll, commit.
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                notifications.deleteAll(notifications.findAllById(List.of(old, alsoOld)))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The whole batch rolled back — even the old notification with no inbox children survived.
        assertThat(notifications.count()).isEqualTo(2);
        assertThat(inbox.count()).isEqualTo(2);
    }

    @Test
    void removesOldNotificationsWithTheirInboxRowsAndDeliveriesAndKeepsRecentOnesIntact() {
        long old = publish(1L, NOW.minusDays(70), "S1", "S2");
        long recent = publish(1L, NOW.minusDays(45), "S1", "S2");

        int removed = job(500).purgeCreatedBefore(CUTOFF);

        assertThat(removed).isEqualTo(1);
        assertThat(notifications.existsById(old)).isFalse();
        assertThat(countWhere("user_notifications", old)).isZero();
        assertThat(countWhere("notification_deliveries", old)).isZero();

        assertThat(notifications.existsById(recent)).isTrue();
        assertThat(countWhere("user_notifications", recent)).isEqualTo(2);
        assertThat(countWhere("notification_deliveries", recent)).isEqualTo(4);
    }

    @Test
    void theCutoffIsExclusiveSoANotificationExactlyAtTheBoundaryStays() {
        long boundary = publish(1L, CUTOFF, "S1");
        long justOlder = publish(1L, CUTOFF.minusSeconds(1), "S1");

        job(500).purgeCreatedBefore(CUTOFF);

        assertThat(notifications.existsById(boundary)).isTrue();
        assertThat(notifications.existsById(justOlder)).isFalse();
    }

    @Test
    void sixtyDayBoundary_59and60DaysOldStay_61DaysOldIsRemoved() {
        assertThat(CUTOFF).isEqualTo(NOW.minusDays(60));
        long day59 = publish(1L, NOW.minusDays(59), "S1");
        long day60 = publish(1L, NOW.minusDays(60), "S1");
        long day61 = publish(1L, NOW.minusDays(61), "S1");

        assertThat(job(500).purgeCreatedBefore(CUTOFF)).isEqualTo(1);

        assertThat(notifications.existsById(day59)).isTrue();
        assertThat(notifications.existsById(day60)).isTrue();
        assertThat(notifications.existsById(day61)).isFalse();
        assertThat(countWhere("user_notifications", day61)).isZero();
        assertThat(countWhere("user_notifications", day60)).isEqualTo(1);
    }

    @Test
    void removesAPlatformWideNotificationWhoseInboxRowsSpanSeveralSchools() {
        long global = tx.execute(s -> {
            Notification n = notifications.save(notification(null, NOW.minusDays(90)));
            inbox.save(inboxRow(n, 1L, "S1"));
            inbox.save(inboxRow(n, 2L, "T9"));
            return n.getId();
        });

        job(500).purgeCreatedBefore(CUTOFF);

        assertThat(notifications.existsById(global)).isFalse();
        assertThat(inbox.count()).isZero();
    }

    @Test
    void worksThroughALargeBacklogInBatches() {
        for (int i = 0; i < 5; i++) publish(1L, NOW.minusDays(70 + i), "S" + i);
        long recent = publish(1L, NOW.minusDays(1), "S1");

        int removed = job(2).purgeCreatedBefore(CUTOFF);

        assertThat(removed).isEqualTo(5);
        assertThat(notifications.findAll()).extracting(Notification::getId).containsExactly(recent);
        assertThat(inbox.count()).isEqualTo(1);
    }

    @Test
    void repeatedRunsAreIdempotent() {
        publish(1L, NOW.minusDays(70), "S1");
        long recent = publish(1L, NOW.minusDays(2), "S1");
        NotificationRetentionCleanupJob job = job(500);

        assertThat(job.purgeCreatedBefore(CUTOFF)).isEqualTo(1);
        assertThat(job.purgeCreatedBefore(CUTOFF)).isZero();
        assertThat(job.purgeCreatedBefore(CUTOFF)).isZero();
        assertThat(notifications.findAll()).extracting(Notification::getId).containsExactly(recent);
    }

    @Test
    void neverTouchesDeliveryRowsOfRecentNotificationsWhateverTheirStatus() {
        long recent = publish(1L, NOW.minusDays(55), "S1");
        jdbc.update("UPDATE notification_deliveries SET status = 'FAILED_FINAL' WHERE notification_id = ?", recent);

        job(500).purgeCreatedBefore(CUTOFF);

        // Terminal delivery rows keep their own 90-day retention (NotificationDeliveryWorker.cleanupTerminalRows).
        assertThat(countWhere("notification_deliveries", recent)).isEqualTo(2);
    }

    @Test
    void anEmptyTableIsANoOp() {
        assertThat(job(500).purgeCreatedBefore(CUTOFF)).isZero();
    }

    // ─── Fixtures ───────────────────────────────────────────────────────

    /** One publication: notification + one inbox row per recipient + PUSH and EMAIL delivery rows each. */
    private long publish(Long schoolId, LocalDateTime at, String... recipients) {
        return tx.execute(s -> {
            Notification n = notifications.save(notification(schoolId, at));
            for (String recipient : recipients) {
                UserNotification row = inbox.save(inboxRow(n, schoolId, recipient));
                for (ExternalDeliveryChannel channel : ExternalDeliveryChannel.values()) {
                    NotificationDelivery d = new NotificationDelivery();
                    d.setSchoolId(schoolId);
                    d.setNotification(n);
                    d.setUserNotification(row);
                    d.setRecipientUserId(recipient);
                    d.setChannel(channel);
                    d.setStatus(NotificationDeliveryStatus.SENT);
                    d.setCreatedAt(at);
                    d.setSentAt(at);
                    deliveries.save(d);
                }
            }
            return n.getId();
        });
    }

    private Notification notification(Long schoolId, LocalDateTime at) {
        Notification n = new Notification();
        n.setSchoolId(schoolId);
        n.setTitle("Notice");
        n.setMessage("Message");
        n.setType("SYSTEM");
        n.setEventCode(NotificationEventCode.NOTICE_PUBLISHED);
        n.setCategory(NotificationCategory.SYSTEM_ADMIN);
        n.setCreatedAt(at);
        return n;
    }

    private UserNotification inboxRow(Notification n, Long schoolId, String userId) {
        UserNotification u = new UserNotification();
        u.setSchoolId(schoolId);
        u.setUserId(userId);
        u.setNotification(n);
        u.setIsRead(false);
        u.setCreatedAt(n.getCreatedAt());
        return u;
    }

    private long countWhere(String table, long notificationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE notification_id = ?", Long.class, notificationId);
    }
}
