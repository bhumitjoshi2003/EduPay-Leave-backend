package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real UNION SQL (H2) across notification_deliveries + user_notifications. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = Notification.class)
@Import(NotificationDeliveryLogQuery.class)
class NotificationDeliveryLogQueryTest {

    @Autowired private TestEntityManager em;
    @Autowired private NotificationDeliveryLogQuery query;

    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 10, 0);

    private final NotificationDeliveryLogQuery.Filter none =
            new NotificationDeliveryLogQuery.Filter(null, null, null, null, null, null, null);

    @BeforeEach
    void seed() {
        // School 1: fee reminder to S1 — in-app (read), push SENT, email FAILED_FINAL.
        Notification fee = notification(1L, NotificationEventCode.FEE_REMINDER, "Fee due", T0);
        UserNotification feeInbox = inbox(fee, 1L, "S1", T0, T0.plusMinutes(30));
        delivery(fee, feeInbox, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.SENT, 1, null,
                "projects/p/messages/abc", T0.plusSeconds(1));
        delivery(fee, feeInbox, ExternalDeliveryChannel.EMAIL, NotificationDeliveryStatus.FAILED_FINAL, 5,
                "Retry limit reached: MailSendException", null, T0.plusSeconds(2));

        // School 2: support ticket update to T9 a day later — in-app (unread), push retrying.
        Notification support = notification(2L, NotificationEventCode.SUPPORT_TICKET_RESOLVED, "Resolved", T0.plusDays(1));
        UserNotification supportInbox = inbox(support, 2L, "T9", T0.plusDays(1), null);
        delivery(support, supportInbox, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.FAILED_RETRYABLE, 2,
                "Push provider temporarily failed for 1 device(s)", null, T0.plusDays(1).plusSeconds(1));
        em.flush();
    }

    @Test
    void listsEveryChannelNewestFirstIncludingInAppInboxRows() {
        List<NotificationDeliveryLogQuery.Row> rows = query.search(none, 50, 0);

        assertThat(rows).extracting(NotificationDeliveryLogQuery.Row::channel)
                .containsExactly("PUSH", "IN_APP", "EMAIL", "PUSH", "IN_APP");
        NotificationDeliveryLogQuery.Row inApp = rows.get(4);
        assertThat(inApp.status()).isEqualTo("STORED");
        assertThat(inApp.read()).isTrue();
        assertThat(inApp.readAt()).isEqualTo(T0.plusMinutes(30));
        assertThat(inApp.eventCode()).isEqualTo("FEE_REMINDER");
        NotificationDeliveryLogQuery.Row push = rows.get(3);
        assertThat(push.status()).isEqualTo("SENT");
        assertThat(push.providerMessageId()).isEqualTo("projects/p/messages/abc");
        assertThat(push.read()).isNull();
    }

    @Test
    void filtersByStatusChannelSchoolRecipientAndEventCode() {
        assertThat(query.search(filter("FAILED_FINAL", null, null, null, null), 50, 0))
                .singleElement().satisfies(r -> {
                    assertThat(r.channel()).isEqualTo("EMAIL");
                    assertThat(r.attemptCount()).isEqualTo(5);
                    assertThat(r.lastError()).startsWith("Retry limit reached");
                });
        assertThat(query.search(filter(null, "IN_APP", null, null, null), 50, 0))
                .extracting(NotificationDeliveryLogQuery.Row::recipientUserId).containsExactly("T9", "S1");
        assertThat(query.search(filter(null, "PUSH", null, 2L, null), 50, 0))
                .singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("FAILED_RETRYABLE"));
        assertThat(query.search(filter(null, null, null, null, "S1"), 50, 0)).hasSize(3);
        assertThat(query.search(filter(null, null, "SUPPORT_TICKET_RESOLVED", null, null), 50, 0)).hasSize(2);
    }

    @Test
    void inAppOnlyStatusExcludesDeliveryRowsAndDeliveryStatusExcludesInAppRows() {
        assertThat(query.search(filter("STORED", null, null, null, null), 50, 0))
                .allSatisfy(r -> assertThat(r.channel()).isEqualTo("IN_APP")).hasSize(2);
        assertThat(query.search(filter("SENT", null, null, null, null), 50, 0))
                .allSatisfy(r -> assertThat(r.channel()).isEqualTo("PUSH")).hasSize(1);
        assertThat(query.search(filter("SENT", "IN_APP", null, null, null), 50, 0)).isEmpty();
    }

    @Test
    void drillDownByNotificationIdReturnsEveryChannelForThatNotificationOnly() {
        long feeId = query.search(filter(null, null, "FEE_REMINDER", null, null), 1, 0).get(0).notificationId();
        NotificationDeliveryLogQuery.Filter byNotification =
                new NotificationDeliveryLogQuery.Filter(null, null, null, null, null, null, null, feeId);

        assertThat(query.search(byNotification, 50, 0)).extracting(NotificationDeliveryLogQuery.Row::channel)
                .containsExactlyInAnyOrder("PUSH", "EMAIL", "IN_APP");
        assertThat(query.search(byNotification, 50, 0)).allSatisfy(r -> assertThat(r.notificationId()).isEqualTo(feeId));
        assertThat(query.search(new NotificationDeliveryLogQuery.Filter("FAILED_FINAL", null, null, null, null, null, null, feeId), 50, 0))
                .singleElement().satisfies(r -> assertThat(r.channel()).isEqualTo("EMAIL"));
    }

    @Test
    void filtersByDateRangeAndPaginates() {
        NotificationDeliveryLogQuery.Filter firstDay = new NotificationDeliveryLogQuery.Filter(
                null, null, null, null, null, T0.toLocalDate().atStartOfDay(), T0.toLocalDate().plusDays(1).atStartOfDay());
        assertThat(query.search(firstDay, 50, 0)).extracting(NotificationDeliveryLogQuery.Row::recipientUserId)
                .containsOnly("S1").hasSize(3);

        assertThat(query.search(none, 2, 0)).hasSize(2);
        assertThat(query.search(none, 2, 4)).hasSize(1);
    }

    private NotificationDeliveryLogQuery.Filter filter(String status, String channel, String eventCode,
                                                       Long schoolId, String recipient) {
        return new NotificationDeliveryLogQuery.Filter(status, channel, eventCode, schoolId, recipient, null, null);
    }

    private Notification notification(Long schoolId, NotificationEventCode code, String title, LocalDateTime at) {
        Notification n = new Notification();
        n.setSchoolId(schoolId);
        n.setTitle(title);
        n.setMessage(title + " message");
        n.setType("SYSTEM");
        n.setEventCode(code);
        n.setCategory(NotificationCategory.SYSTEM_ADMIN);
        n.setCreatedAt(at);
        return em.persist(n);
    }

    private UserNotification inbox(Notification n, Long schoolId, String userId, LocalDateTime at, LocalDateTime readAt) {
        UserNotification u = new UserNotification();
        u.setSchoolId(schoolId);
        u.setUserId(userId);
        u.setNotification(n);
        u.setIsRead(readAt != null);
        u.setReadAt(readAt);
        u.setCreatedAt(at);
        return em.persist(u);
    }

    private void delivery(Notification n, UserNotification u, ExternalDeliveryChannel channel,
                          NotificationDeliveryStatus status, int attempts, String error, String providerId,
                          LocalDateTime at) {
        NotificationDelivery d = new NotificationDelivery();
        d.setSchoolId(n.getSchoolId());
        d.setNotification(n);
        d.setUserNotification(u);
        d.setRecipientUserId(u.getUserId());
        d.setChannel(channel);
        d.setStatus(status);
        d.setAttemptCount(attempts);
        d.setLastError(error);
        d.setProviderMessageId(providerId);
        d.setCreatedAt(at);
        if (status == NotificationDeliveryStatus.SENT) d.setSentAt(at);
        em.persist(d);
    }
}
