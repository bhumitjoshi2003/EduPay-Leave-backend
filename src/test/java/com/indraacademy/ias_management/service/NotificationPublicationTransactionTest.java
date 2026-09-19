package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.*;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPublicationTransactionTest {
    @Mock NotificationRepository notificationRepository;
    @Mock UserNotificationRepository inboxRepository;
    @Mock NotificationDeliveryRepository deliveryRepository;
    @Mock UserRepository userRepository;
    @Mock NotificationRecipientResolver recipientResolver;
    @Mock NotificationChannelPolicyResolver channelPolicy;
    @Mock ApplicationEventPublisher eventPublisher;

    private NotificationPublicationTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new NotificationPublicationTransaction(notificationRepository, inboxRepository,
                deliveryRepository, userRepository, recipientResolver, channelPolicy, eventPublisher);
        when(recipientResolver.resolve(2L, new NotificationAudience(NotificationAudienceType.CLASS, "9-A")))
                .thenReturn(Set.of("student-1", "parent-1"));
        when(notificationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            notification.setId(71L);
            return notification;
        });
        when(inboxRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void atomicallyCreatesStructuredNotificationInboxSnapshotsAndExternalOutboxRows() {
        when(channelPolicy.resolve(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT,
                Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)))
                .thenReturn(Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL));
        User student = user("student-1", "student@example.com");
        User parent = user("parent-1", null);
        when(userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(any(), any()))
                .thenReturn(List.of(student, parent));
        when(deliveryRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPublication publication = transaction.publishNew(request(
                Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)));

        Notification saved = publication.notification();
        assertThat(saved.getSchoolId()).isEqualTo(2L);
        assertThat(saved.getEventCode()).isEqualTo(NotificationEventCode.NOTICE_PUBLISHED);
        assertThat(saved.getCategory()).isEqualTo(NotificationCategory.NOTICE_ANNOUNCEMENT);
        assertThat(saved.getPriority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(saved.getAudience()).isEqualTo("CLASS:9-A");
        assertThat(saved.getActionRoute()).isEqualTo("/dashboard/notice");
        assertThat(publication.recipients()).extracting(UserNotification::getUserId)
                .containsExactlyInAnyOrder("student-1", "parent-1");
        assertThat(publication.recipients()).allSatisfy(row -> {
            assertThat(row.getSchoolId()).isEqualTo(2L);
            assertThat(row.getIsRead()).isFalse();
            assertThat(row.getNotification()).isSameAs(saved);
        });
        assertThat(publication.deliveries()).hasSize(4);
        assertThat(publication.deliveries())
                .filteredOn(d -> d.getChannel() == ExternalDeliveryChannel.EMAIL
                        && d.getRecipientUserId().equals("parent-1"))
                .singleElement().satisfies(d -> {
                    assertThat(d.getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED);
                    assertThat(d.getLastError()).contains("no email");
                });
        assertThat(publication.deliveries()).filteredOn(d -> d.getChannel() == ExternalDeliveryChannel.PUSH)
                .allSatisfy(d -> assertThat(d.getDestination()).isEqualTo(d.getRecipientUserId()));

        verify(eventPublisher).publishEvent(new NotificationDeliveryWorkAvailableEvent(2L));
    }

    @Test
    void inAppOnlyPublicationCreatesInboxWithoutDeliveryRows() {
        when(channelPolicy.resolve(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT, Set.of())).thenReturn(Set.of());

        NotificationPublication publication = transaction.publishNew(request(Set.of()));

        assertThat(publication.recipients()).hasSize(2);
        assertThat(publication.deliveries()).isEmpty();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void noWorkAvailableEventWhenEveryCreatedDeliveryIsAlreadyTerminal() {
        // EMAIL-only channel, both recipients lack an email address — every created delivery row
        // is SKIPPED at creation time, so there is genuinely nothing for a worker to wake up for.
        when(channelPolicy.resolve(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT, Set.of(ExternalDeliveryChannel.EMAIL)))
                .thenReturn(Set.of(ExternalDeliveryChannel.EMAIL));
        when(userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(any(), any()))
                .thenReturn(List.of(user("student-1", null), user("parent-1", null)));
        when(deliveryRepository.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPublication publication = transaction.publishNew(request(Set.of(ExternalDeliveryChannel.EMAIL)));

        assertThat(publication.deliveries()).hasSize(2)
                .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(NotificationDeliveryStatus.SKIPPED));
        verify(eventPublisher, never()).publishEvent(any());
    }

    private NotificationPublishRequest request(Set<ExternalDeliveryChannel> channels) {
        return new NotificationPublishRequest(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT, NotificationPriority.HIGH,
                " Class update ", " Important information ",
                new NotificationAudience(NotificationAudienceType.CLASS, "9-A"),
                "NOTICE", "14", "/dashboard/notice", "{\"noticeId\":14}", "admin-2",
                null, "notice-14", channels);
    }

    private User user(String id, String email) {
        User user = new User();
        user.setUserId(id);
        user.setEmail(email);
        user.setSchoolId(2L);
        user.setActive(true);
        return user;
    }
}
