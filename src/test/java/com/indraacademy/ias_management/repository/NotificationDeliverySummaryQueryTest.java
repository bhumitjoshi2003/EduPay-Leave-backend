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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real aggregation SQL (H2) over notifications, user_notifications and notification_deliveries. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
})
@EntityScan(basePackageClasses = Notification.class)
@Import(NotificationDeliverySummaryQuery.class)
class NotificationDeliverySummaryQueryTest {

    @Autowired private TestEntityManager em;
    @Autowired private NotificationDeliverySummaryQuery query;

    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 10, 0);
    private final NotificationDeliverySummaryQuery.Filter none = new NotificationDeliverySummaryQuery.Filter(null, null, null, null, null);
    private long exam;
    private long fee;
    private long empty;

    @BeforeEach
    void seed() {
        // School 1 exam notice to 4 recipients: push 3 accepted + 1 failed; email 2 accepted, 1 retrying, 1 skipped; 2 opened.
        Notification examNotice = notification(1L, NotificationEventCode.NOTICE_PUBLISHED, "Exam Notice", "Exams start Monday", T0);
        String[] push = {"SENT", "SENT", "SENT", "FAILED_FINAL"};
        String[] email = {"SENT", "SENT", "FAILED_RETRYABLE", "SKIPPED"};
        for (int i = 0; i < 4; i++) {
            UserNotification inbox = inbox(examNotice, 1L, "S" + i, i < 2);
            delivery(examNotice, inbox, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.valueOf(push[i]));
            delivery(examNotice, inbox, ExternalDeliveryChannel.EMAIL, NotificationDeliveryStatus.valueOf(email[i]));
        }
        // School 2 fee reminder a day later, push only, still queued; 100% discount in the text for LIKE-escaping.
        Notification feeReminder = notification(2L, NotificationEventCode.FEE_REMINDER, "Fee due", "Pay now for 100% access", T0.plusDays(1));
        UserNotification feeInbox = inbox(feeReminder, 2L, "P1", false);
        delivery(feeReminder, feeInbox, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.PENDING);
        // A publication that resolved to nobody.
        Notification nobody = notification(1L, NotificationEventCode.HOLIDAY_PUBLISHED, "Holiday", "Closed", T0.minusDays(1));
        em.flush();
        exam = examNotice.getId();
        fee = feeReminder.getId();
        empty = nobody.getId();
    }

    @Test
    void groupsAreNotificationsNewestFirstIncludingOnesWithNoRecipients() {
        assertThat(query.groups(none, 50, 0)).extracting(NotificationDeliverySummaryQuery.Group::notificationId)
                .containsExactly(fee, exam, empty);
    }

    @Test
    void inboxCountsAreAggregatedPerNotification() {
        Map<Long, NotificationDeliverySummaryQuery.InboxCounts> counts = query.inboxCounts(List.of(exam, fee, empty));
        assertThat(counts.get(exam)).isEqualTo(new NotificationDeliverySummaryQuery.InboxCounts(4, 2));
        assertThat(counts.get(fee)).isEqualTo(new NotificationDeliverySummaryQuery.InboxCounts(1, 0));
        assertThat(counts).doesNotContainKey(empty);
    }

    @Test
    void deliveryCountsAreAggregatedPerChannelAndStatus() {
        Map<Long, NotificationDeliverySummaryQuery.DeliveryCounts> counts = query.deliveryCounts(Set.of(1L, 2L), List.of(exam, fee));
        assertThat(counts.get(exam).byChannel()).isEqualTo(Map.of(
                "PUSH", Map.of("SENT", 3L, "FAILED_FINAL", 1L),
                "EMAIL", Map.of("SENT", 2L, "FAILED_RETRYABLE", 1L, "SKIPPED", 1L)));
        assertThat(counts.get(fee).byChannel()).isEqualTo(Map.of("PUSH", Map.of("PENDING", 1L)));
    }

    @Test
    void deliveryCountsAreScopedToTheGivenSchools() {
        assertThat(query.deliveryCounts(Set.of(1L), List.of(exam, fee))).containsOnlyKeys(exam);
        assertThat(query.deliveryCounts(Set.of(), List.of(exam))).isEmpty();
        assertThat(query.inboxCounts(List.of())).isEmpty();
    }

    @Test
    void filtersByEventTypeSchoolDateAndSearchWithLikeWildcardsEscaped() {
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter("FEE_REMINDER", null, null, null, null))).containsExactly(fee);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, 1L, null, null, null))).containsExactly(exam, empty);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, null, T0.toLocalDate().atStartOfDay(),
                T0.toLocalDate().plusDays(1).atStartOfDay(), null))).containsExactly(exam);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, null, null, null, "exam notice"))).containsExactly(exam);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, null, null, null, "MONDAY"))).containsExactly(exam);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, null, null, null, "100%"))).containsExactly(fee);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, null, null, null, "%"))).containsExactly(fee);
        assertThat(ids(new NotificationDeliverySummaryQuery.Filter(null, null, null, null, "_"))).isEmpty();
    }

    @Test
    void paginates() {
        assertThat(query.groups(none, 2, 0)).hasSize(2);
        assertThat(query.groups(none, 2, 2)).extracting(NotificationDeliverySummaryQuery.Group::notificationId).containsExactly(empty);
    }

    private List<Long> ids(NotificationDeliverySummaryQuery.Filter filter) {
        return query.groups(filter, 50, 0).stream().map(NotificationDeliverySummaryQuery.Group::notificationId).toList();
    }

    private Notification notification(Long schoolId, NotificationEventCode code, String title, String message, LocalDateTime at) {
        Notification n = new Notification();
        n.setSchoolId(schoolId);
        n.setTitle(title);
        n.setMessage(message);
        n.setType("SYSTEM");
        n.setEventCode(code);
        n.setCategory(NotificationCategory.SYSTEM_ADMIN);
        n.setCreatedAt(at);
        return em.persist(n);
    }

    private UserNotification inbox(Notification n, Long schoolId, String userId, boolean read) {
        UserNotification u = new UserNotification();
        u.setSchoolId(schoolId);
        u.setUserId(userId);
        u.setNotification(n);
        u.setIsRead(read);
        u.setReadAt(read ? n.getCreatedAt().plusMinutes(5) : null);
        u.setCreatedAt(n.getCreatedAt());
        return em.persist(u);
    }

    private void delivery(Notification n, UserNotification u, ExternalDeliveryChannel channel, NotificationDeliveryStatus status) {
        NotificationDelivery d = new NotificationDelivery();
        d.setSchoolId(n.getSchoolId());
        d.setNotification(n);
        d.setUserNotification(u);
        d.setRecipientUserId(u.getUserId());
        d.setChannel(channel);
        d.setStatus(status);
        d.setAttemptCount(status == NotificationDeliveryStatus.PENDING ? 0 : 1);
        d.setCreatedAt(n.getCreatedAt());
        em.persist(d);
    }
}
