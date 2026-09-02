package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.NotificationDelivery;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.NotificationDeliveryRepository;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationPublisherTest {
    @Mock NotificationRepository notificationRepository;
    @Mock UserNotificationRepository inboxRepository;
    @Mock NotificationDeliveryRepository deliveryRepository;
    @Mock SchoolRepository schoolRepository;
    @Mock NotificationPublicationTransaction publicationTransaction;

    private NotificationPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new NotificationPublisher(notificationRepository, inboxRepository, deliveryRepository,
                schoolRepository, publicationTransaction, new ObjectMapper());
        lenient().when(schoolRepository.existsById(2L)).thenReturn(true);
    }

    @Test
    void returnsExistingTenantPublicationForRepeatedIdempotencyKey() {
        Notification existing = notification(41L);
        UserNotification inbox = new UserNotification();
        NotificationDelivery delivery = new NotificationDelivery();
        when(notificationRepository.findBySchoolIdAndIdempotencyKey(2L, "notice-41"))
                .thenReturn(Optional.of(existing));
        when(inboxRepository.findByNotificationIdAndSchoolId(41L, 2L)).thenReturn(List.of(inbox));
        when(deliveryRepository.findByNotificationIdAndSchoolId(41L, 2L)).thenReturn(List.of(delivery));

        NotificationPublication result = publisher.publish(request("notice-41"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.notification()).isSameAs(existing);
        assertThat(result.recipients()).containsExactly(inbox);
        verify(publicationTransaction, never()).publishNew(any());
        verify(notificationRepository, never()).findBySchoolIdAndIdempotencyKey(3L, "notice-41");
    }

    @Test
    void concurrentIdempotencyRaceReturnsWinningPublication() {
        Notification winner = notification(42L);
        when(notificationRepository.findBySchoolIdAndIdempotencyKey(2L, "notice-42"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(publicationTransaction.publishNew(any()))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));
        when(inboxRepository.findByNotificationIdAndSchoolId(42L, 2L)).thenReturn(List.of());
        when(deliveryRepository.findByNotificationIdAndSchoolId(42L, 2L)).thenReturn(List.of());

        NotificationPublication result = publisher.publish(request("notice-42"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.notification()).isSameAs(winner);
        verify(notificationRepository, times(2)).findBySchoolIdAndIdempotencyKey(2L, "notice-42");
    }

    @Test
    void rejectsUnsafeActionMetadataAndCrossTenantTarget() {
        NotificationPublishRequest badRoute = new NotificationPublishRequest(2L,
                NotificationEventCode.NOTICE_PUBLISHED, NotificationCategory.NOTICE_ANNOUNCEMENT,
                NotificationPriority.NORMAL, "Title", "Message",
                new NotificationAudience(NotificationAudienceType.WHOLE_SCHOOL, null),
                null, null, "https://evil.example", null, "admin", null, null, Set.of());

        assertThatThrownBy(() -> publisher.publish(badRoute))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("application-relative");

        NotificationPublishRequest malformedJson = new NotificationPublishRequest(2L,
                NotificationEventCode.NOTICE_PUBLISHED, NotificationCategory.NOTICE_ANNOUNCEMENT,
                NotificationPriority.NORMAL, "Title", "Message",
                new NotificationAudience(NotificationAudienceType.WHOLE_SCHOOL, null),
                null, null, "/notice", "{bad", "admin", null, null, Set.of());
        assertThatThrownBy(() -> publisher.publish(malformedJson))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid JSON");

        when(schoolRepository.existsById(3L)).thenReturn(false);
        NotificationPublishRequest missingSchool = new NotificationPublishRequest(3L,
                NotificationEventCode.NOTICE_PUBLISHED, NotificationCategory.NOTICE_ANNOUNCEMENT,
                NotificationPriority.NORMAL, "Title", "Message",
                new NotificationAudience(NotificationAudienceType.WHOLE_SCHOOL, null),
                null, null, null, null, "admin", null, null, Set.of());
        assertThatThrownBy(() -> publisher.publish(missingSchool)).hasMessageContaining("does not exist");
    }

    private NotificationPublishRequest request(String key) {
        return new NotificationPublishRequest(2L, NotificationEventCode.NOTICE_PUBLISHED,
                NotificationCategory.NOTICE_ANNOUNCEMENT, NotificationPriority.HIGH,
                "Title", "Message", new NotificationAudience(NotificationAudienceType.WHOLE_SCHOOL, null),
                "NOTICE", "12", "/dashboard/notice", "{\"noticeId\":12}", "admin-2",
                null, key, Set.of(ExternalDeliveryChannel.PUSH));
    }

    private Notification notification(Long id) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setSchoolId(2L);
        return notification;
    }
}
