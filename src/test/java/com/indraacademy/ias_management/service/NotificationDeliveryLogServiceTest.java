package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.controller.NotificationDeliveryLogController;
import com.indraacademy.ias_management.dto.NotificationDeliveryLogDtos.*;
import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationDeliveryStatus;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryLogServiceTest {
    @Mock NotificationDeliveryLogQuery query;
    @Mock NotificationDeliveryRepository deliveries;
    @Mock UserNotificationRepository inbox;
    @Mock SchoolRepository schools;
    @Mock UserRepository users;
    @Mock NotificationRetryPolicy retryPolicy;
    @Mock NotificationDeliverySummaryQuery summaryQuery;

    NotificationDeliveryLogService service;
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 20, 10, 0);

    @BeforeEach
    void setup() {
        service = new NotificationDeliveryLogService(query, deliveries, inbox, schools, users, retryPolicy, summaryQuery);
        School school = new School();
        school.setId(1L);
        school.setName("Indra Academy");
        lenient().when(schools.findAllById(any())).thenReturn(List.of(school));
        lenient().when(schools.findById(1L)).thenReturn(Optional.of(school));
        User student = new User();
        student.setUserId("S1");
        student.setRole("STUDENT");
        lenient().when(users.findBySchoolIdAndUserIdIn(eq(1L), any())).thenReturn(List.of(student));
        lenient().when(retryPolicy.maxAttempts()).thenReturn(5);
    }

    // ─── Access ─────────────────────────────────────────────────────────

    @Test
    void theWholeControllerIsRestrictedToSuperAdmin() {
        PreAuthorize guard = NotificationDeliveryLogController.class.getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("hasRole('SUPER_ADMIN')");
        assertThat(NotificationDeliveryLogController.class.getDeclaredMethods())
                .filteredOn(m -> m.isAnnotationPresent(PreAuthorize.class))
                .as("no method may override (broaden) the class-level SUPER_ADMIN guard")
                .isEmpty();
    }

    // ─── Search ─────────────────────────────────────────────────────────

    @Test
    void searchEnrichesRowsWithSchoolNameAndRecipientRoleAndMasksEmailsInErrors() {
        when(query.search(any(), anyInt(), anyInt())).thenReturn(List.of(row(10, "EMAIL", "FAILED_FINAL",
                "Retry limit reached: 550 5.1.1 <priya.sharma@gmail.com> mailbox unavailable")));

        RowPage page = service.search(0, 25, null, null, null, null, null, null, null, null);

        Row r = page.content().get(0);
        assertThat(r.schoolName()).isEqualTo("Indra Academy");
        assertThat(r.recipientRole()).isEqualTo("STUDENT");
        assertThat(r.lastError()).contains("pr***@gmail.com").doesNotContain("priya.sharma");
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void searchFetchesOneExtraRowToDetectANextPageWithoutCounting() {
        List<NotificationDeliveryLogQuery.Row> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) three.add(row(i, "PUSH", "SENT", null));
        when(query.search(any(), eq(3), eq(2))).thenReturn(three);

        RowPage page = service.search(1, 2, null, null, null, null, null, null, null, null);

        assertThat(page.content()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void searchCapsPageSizeAndPassesTrimmedFiltersAndInclusiveDateRange() {
        when(query.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.search(0, 500, "FAILED_RETRYABLE", "PUSH", "FEE_REMINDER", 1L, "  S1 ",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);

        ArgumentCaptor<NotificationDeliveryLogQuery.Filter> filter = ArgumentCaptor.forClass(NotificationDeliveryLogQuery.Filter.class);
        verify(query).search(filter.capture(), eq(NotificationDeliveryLogService.MAX_PAGE_SIZE + 1), eq(0));
        assertThat(filter.getValue().recipientUserId()).isEqualTo("S1");
        assertThat(filter.getValue().from()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(filter.getValue().to()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void searchAcceptsTheInAppStoredStatus() {
        when(query.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        service.search(0, 25, "STORED", "IN_APP", null, null, null, null, null, null);
        verify(query).search(argThat(f -> "STORED".equals(f.status()) && "IN_APP".equals(f.channel())), anyInt(), anyInt());
    }

    @Test
    void searchRejectsUnknownFilterValuesAndReversedDates() {
        assertThatThrownBy(() -> service.search(0, 25, "DELIVERED", null, null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("status");
        assertThatThrownBy(() -> service.search(0, 25, null, "SMS", null, null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("channel");
        assertThatThrownBy(() -> service.search(0, 25, null, null, "NOT_A_CODE", null, null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("eventCode");
        assertThatThrownBy(() -> service.search(0, 25, null, null, null, null, null,
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(query);
    }

    @Test
    void searchPassesTheNotificationIdDrillDownFilter() {
        when(query.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        service.search(0, 25, null, null, null, null, null, null, null, 4812L);
        verify(query).search(argThat(f -> Long.valueOf(4812L).equals(f.notificationId())), anyInt(), anyInt());
    }

    // ─── Summary ────────────────────────────────────────────────────────

    @Test
    void summaryMapsAggregatedCountsPerChannelWithAcceptedNotDelivered() {
        LocalDateTime recent = LocalDateTime.now().minusDays(1);
        when(summaryQuery.groups(any(), anyInt(), anyInt())).thenReturn(List.of(
                new NotificationDeliverySummaryQuery.Group(4812L, 1L, "NOTICE_PUBLISHED", "Exam Notice", "x".repeat(400), recent)));
        when(summaryQuery.inboxCounts(List.of(4812L))).thenReturn(Map.of(4812L, new NotificationDeliverySummaryQuery.InboxCounts(500, 212)));
        when(summaryQuery.deliveryCounts(Set.of(1L), List.of(4812L))).thenReturn(Map.of(4812L, new NotificationDeliverySummaryQuery.DeliveryCounts(Map.of(
                "PUSH", Map.of("SENT", 487L, "FAILED_FINAL", 10L, "FAILED_RETRYABLE", 2L, "PROCESSING", 1L),
                "EMAIL", Map.of("SENT", 492L, "FAILED_FINAL", 5L, "SKIPPED", 3L)))));

        SummaryRow r = service.summary(0, 25, null, null, null, null, null).content().get(0);

        assertThat(r.schoolName()).isEqualTo("Indra Academy");
        assertThat(r.totalRecipients()).isEqualTo(500);
        assertThat(r.inApp()).isEqualTo(new InAppCounts(500, 212, 288));
        assertThat(r.push()).isEqualTo(new ChannelCounts(500, 487, 10, 2, 0, 1));
        assertThat(r.email()).isEqualTo(new ChannelCounts(500, 492, 5, 0, 3, 0));
        assertThat(r.messagePreview()).hasSize(NotificationDeliveryLogService.MESSAGE_PREVIEW_LENGTH + 1).endsWith("…");
    }

    @Test
    void summaryLeavesAnUnusedChannelNullAndHandlesPlatformWideNotificationsWithNoRecipients() {
        when(summaryQuery.groups(any(), anyInt(), anyInt())).thenReturn(List.of(
                new NotificationDeliverySummaryQuery.Group(1L, 1L, "HOLIDAY_PUBLISHED", "Holiday", "Closed.", LocalDateTime.now().minusDays(10)),
                new NotificationDeliverySummaryQuery.Group(2L, null, "LEGACY_NOTIFICATION", "Platform", "Hi.", LocalDateTime.now())));
        when(summaryQuery.inboxCounts(any())).thenReturn(Map.of(1L, new NotificationDeliverySummaryQuery.InboxCounts(40, 0)));
        when(summaryQuery.deliveryCounts(any(), any())).thenReturn(Map.of());

        SummaryPage page = service.summary(0, 25, null, null, null, null, null);

        SummaryRow old = page.content().get(0);
        assertThat(old.push()).isNull();
        assertThat(old.email()).isNull();
        SummaryRow platform = page.content().get(1);
        assertThat(platform.schoolName()).isNull();
        assertThat(platform.totalRecipients()).isZero();
        assertThat(platform.inApp()).isEqualTo(new InAppCounts(0, 0, 0));
        verify(summaryQuery).deliveryCounts(Set.of(1L), List.of(1L, 2L));
    }

    @Test
    void summaryPagesWithoutCountingAndPassesFilters() {
        List<NotificationDeliverySummaryQuery.Group> three = new ArrayList<>();
        for (long i = 1; i <= 3; i++) three.add(new NotificationDeliverySummaryQuery.Group(i, 1L, "FEE_REMINDER", "t", "m", T0));
        when(summaryQuery.groups(any(), eq(3), eq(2))).thenReturn(three);

        SummaryPage page = service.summary(1, 2, "FEE_REMINDER", 1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "  exam ");

        assertThat(page.content()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        verify(summaryQuery).groups(argThat(f -> "FEE_REMINDER".equals(f.eventCode()) && Long.valueOf(1L).equals(f.schoolId())
                && "exam".equals(f.search()) && LocalDateTime.of(2026, 10, 1, 0, 0).equals(f.to())), eq(3), eq(2));
    }

    @Test
    void summaryRejectsUnknownEventCodesAndReversedDates() {
        assertThatThrownBy(() -> service.summary(0, 25, "NOPE", null, null, null, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("eventCode");
        assertThatThrownBy(() -> service.summary(0, 25, null, null, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(summaryQuery);
    }

    // ─── Detail ─────────────────────────────────────────────────────────

    @Test
    void emailDetailMasksTheDestinationAndListsTheOtherChannelsForTheSameRecipient() {
        UserNotification inboxRow = inboxRow(true);
        NotificationDelivery email = delivery(20L, ExternalDeliveryChannel.EMAIL, NotificationDeliveryStatus.FAILED_FINAL,
                inboxRow, "priya.sharma@gmail.com", "Retry limit reached: <priya.sharma@gmail.com> rejected");
        NotificationDelivery push = delivery(21L, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.SENT,
                inboxRow, "S1", null);
        push.setProviderMessageId("projects/p/messages/abc");
        when(deliveries.findById(20L)).thenReturn(Optional.of(email));
        when(deliveries.findByUserNotificationId(inboxRow.getId())).thenReturn(List.of(email, push));

        Detail d = service.detail("EMAIL", 20L);

        assertThat(d.maskedDestination()).isEqualTo("pr***@gmail.com");
        assertThat(d.lastError()).doesNotContain("priya.sharma");
        assertThat(d.maxAttempts()).isEqualTo(5);
        assertThat(d.recipientRole()).isEqualTo("STUDENT");
        assertThat(d.relatedChannels()).extracting(RelatedChannel::channel).containsExactly("IN_APP", "PUSH");
        assertThat(d.relatedChannels().get(0).read()).isTrue();
        assertThat(d.relatedChannels().get(1).status()).isEqualTo("SENT");
    }

    @Test
    void pushDetailNeverExposesADestination() {
        UserNotification inboxRow = inboxRow(false);
        NotificationDelivery push = delivery(21L, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.SENT, inboxRow, "S1", null);
        push.setProviderMessageId("projects/p/messages/abc");
        when(deliveries.findById(21L)).thenReturn(Optional.of(push));
        when(deliveries.findByUserNotificationId(inboxRow.getId())).thenReturn(List.of(push));

        Detail d = service.detail("PUSH", 21L);

        assertThat(d.maskedDestination()).isNull();
        assertThat(d.providerMessageId()).isEqualTo("projects/p/messages/abc");
        assertThat(d.relatedChannels()).extracting(RelatedChannel::channel).containsExactly("IN_APP");
    }

    @Test
    void inAppDetailShowsInboxReadStateAndTheExternalDeliveries() {
        UserNotification inboxRow = inboxRow(true);
        NotificationDelivery push = delivery(21L, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.FAILED_RETRYABLE, inboxRow, "S1", "Push provider temporarily failed for 1 device(s)");
        when(inbox.findById(inboxRow.getId())).thenReturn(Optional.of(inboxRow));
        when(deliveries.findByUserNotificationId(inboxRow.getId())).thenReturn(List.of(push));

        Detail d = service.detail("IN_APP", inboxRow.getId());

        assertThat(d.status()).isEqualTo("STORED");
        assertThat(d.read()).isTrue();
        assertThat(d.readAt()).isEqualTo(T0.plusMinutes(5));
        assertThat(d.relatedChannels()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("FAILED_RETRYABLE"));
    }

    @Test
    void detailIsNotFoundWhenTheChannelDoesNotMatchTheRowOrIsUnknown() {
        NotificationDelivery push = delivery(21L, ExternalDeliveryChannel.PUSH, NotificationDeliveryStatus.SENT, inboxRow(false), "S1", null);
        when(deliveries.findById(21L)).thenReturn(Optional.of(push));

        assertThatThrownBy(() -> service.detail("EMAIL", 21L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not found");
        when(deliveries.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail("PUSH", 99L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.detail("SMS", 1L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void emailMaskingHandlesShortAndMalformedAddresses() {
        assertThat(NotificationDeliveryLogService.maskEmail("a@x.com")).isEqualTo("a***@x.com");
        assertThat(NotificationDeliveryLogService.maskEmail("no-at-sign")).isEqualTo("***");
        assertThat(NotificationDeliveryLogService.maskEmail(null)).isNull();
    }

    // ─── Fixtures ───────────────────────────────────────────────────────

    private NotificationDeliveryLogQuery.Row row(long id, String channel, String status, String error) {
        return new NotificationDeliveryLogQuery.Row(id, channel, status, 1L, "S1", 7L, "FEE_REMINDER", "Fee due",
                1, error, null, T0, null, null, null, null);
    }

    private UserNotification inboxRow(boolean read) {
        Notification n = new Notification();
        n.setId(7L);
        n.setSchoolId(1L);
        n.setTitle("Fee due");
        n.setMessage("Your fee is due.");
        n.setEventCode(NotificationEventCode.FEE_REMINDER);
        n.setCategory(NotificationCategory.SYSTEM_ADMIN);
        UserNotification u = new UserNotification();
        u.setId(70L);
        u.setSchoolId(1L);
        u.setUserId("S1");
        u.setNotification(n);
        u.setIsRead(read);
        u.setReadAt(read ? T0.plusMinutes(5) : null);
        u.setCreatedAt(T0);
        return u;
    }

    private NotificationDelivery delivery(long id, ExternalDeliveryChannel channel, NotificationDeliveryStatus status,
                                          UserNotification inboxRow, String destination, String error) {
        NotificationDelivery d = new NotificationDelivery();
        d.setId(id);
        d.setSchoolId(1L);
        d.setNotification(inboxRow.getNotification());
        d.setUserNotification(inboxRow);
        d.setRecipientUserId("S1");
        d.setChannel(channel);
        d.setStatus(status);
        d.setDestination(destination);
        d.setLastError(error);
        d.setAttemptCount(status == NotificationDeliveryStatus.FAILED_FINAL ? 5 : 1);
        d.setCreatedAt(T0);
        return d;
    }
}
