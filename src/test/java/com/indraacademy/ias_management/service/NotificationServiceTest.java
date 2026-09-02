package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.entity.Notification;
import com.indraacademy.ias_management.entity.UserNotification;
import com.indraacademy.ias_management.notification.NotificationPublication;
import com.indraacademy.ias_management.notification.NotificationPublishRequest;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.repository.NotificationRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.UserNotificationRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock NotificationRepository notificationRepository;
    @Mock UserNotificationRepository inboxRepository;
    @Mock SchoolRepository schoolRepository;
    @Mock AuditService auditService;
    @Mock SecurityUtil securityUtil;
    @Mock FcmService fcmService;
    @Mock NotificationPublisher publisher;
    @Mock HttpServletRequest servletRequest;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService();
        ReflectionTestUtils.setField(service, "notificationRepository", notificationRepository);
        ReflectionTestUtils.setField(service, "userNotificationRepository", inboxRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(service, "notificationPublisher", publisher);
        lenient().when(securityUtil.getSchoolId()).thenReturn(2L);
        lenient().when(securityUtil.getUsername()).thenReturn("admin-2");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");
        lenient().when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    void readingInboxIsReadOnlyAndMapsStructuredFields() {
        var pageable = PageRequest.of(0, 20);
        Notification notification = notification(91L);
        UserNotification row = inboxRow(7L, "student-1", notification);
        when(inboxRepository.findInbox("student-1", 2L, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 1));

        var result = service.getNotificationsForUser("student-1", "STUDENT", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().getFirst().getTitle()).isEqualTo("School update");
        assertThat(result.getContent().getFirst().getInboxId()).isEqualTo(7L);
        verify(inboxRepository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void filteredInboxPreservesAuthenticatedUserSchoolAndPageable() {
        var pageable = PageRequest.of(2, 10);
        when(inboxRepository.findInbox("parent-1", 2L, false, NotificationCategory.ATTENDANCE, pageable))
                .thenReturn(Page.empty(pageable));

        service.getNotificationsForUser("parent-1", "PARENT", false,
                NotificationCategory.ATTENDANCE, pageable);

        verify(inboxRepository).findInbox("parent-1", 2L, false,
                NotificationCategory.ATTENDANCE, pageable);
    }

    @Test
    void unreadCountIsCorrectBeforeInboxIsOpened() {
        when(inboxRepository.countByUserIdAndSchoolIdAndIsReadFalse("student-1", 2L)).thenReturn(4L);

        assertThat(service.getUnreadNotificationCount("student-1", "STUDENT")).isEqualTo(4L);

        verify(inboxRepository, never()).findInbox(any(), any(), any(), any(), any());
    }

    @Test
    void markOneReadIsScopedAndSetsReadAt() {
        UserNotification row = inboxRow(7L, "student-1", notification(91L));
        when(inboxRepository.findByIdAndUserIdAndSchoolId(7L, "student-1", 2L)).thenReturn(Optional.of(row));

        service.markNotificationAsRead("student-1", 7L);

        assertThat(row.getIsRead()).isTrue();
        assertThat(row.getReadAt()).isNotNull();
        verify(inboxRepository).save(row);
    }

    @Test
    void markOneReadRejectsCrossUserOrCrossSchoolInboxId() {
        when(inboxRepository.findByIdAndUserIdAndSchoolId(7L, "student-1", 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markNotificationAsRead("student-1", 7L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void markAllReadIsScopedToAuthenticatedSchoolAndUser() {
        when(inboxRepository.markAllRead("student-1", 2L)).thenReturn(3);

        service.markAllNotificationsAsRead("student-1");

        verify(inboxRepository).markAllRead("student-1", 2L);
    }

    @Test
    void broadPublicationUsesCanonicalPublisherWithoutImmediateNetworkDelivery() {
        Notification saved = notification(91L);
        UserNotification student = inboxRow(7L, "student-1", saved);
        UserNotification parent = inboxRow(8L, "parent-1", saved);
        when(publisher.publish(any())).thenReturn(new NotificationPublication(
                saved, List.of(student, parent), List.of(), false));

        Notification result = service.createBroadNotification(legacyNotification("ALL"), servletRequest);

        assertThat(result).isSameAs(saved);
        ArgumentCaptor<NotificationPublishRequest> request = ArgumentCaptor.forClass(NotificationPublishRequest.class);
        verify(publisher).publish(request.capture());
        assertThat(request.getValue().schoolId()).isEqualTo(2L);
        assertThat(request.getValue().audience().type().name()).isEqualTo("WHOLE_SCHOOL");
        verify(fcmService, never()).sendToUsers(any(), any(), any(), any());
        verify(fcmService, never()).sendToUser(any(), any(), any(), any());
    }

    @Test
    void duplicatePublicationDoesNotRepeatAuditOrNetworkDelivery() {
        Notification saved = notification(91L);
        when(publisher.publish(any())).thenReturn(new NotificationPublication(saved, List.of(), List.of(), true));

        service.createBroadNotification(legacyNotification("ALL"), servletRequest);

        verify(fcmService, never()).sendToUsers(any(), any(), any(), any());
        verify(auditService, never()).log(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void superAdminMustProvideExplicitValidatedSchool() {
        when(securityUtil.getRole()).thenReturn("SUPER_ADMIN");

        assertThatThrownBy(() -> service.createBroadNotification(legacyNotification("ALL"), servletRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("explicit schoolId");

        when(schoolRepository.existsById(3L)).thenReturn(false);
        assertThatThrownBy(() -> service.createBroadNotification(legacyNotification("ALL"), 3L, servletRequest))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not exist");
        verify(publisher, never()).publish(any());
    }

    private Notification legacyNotification(String audience) {
        Notification notification = notification(null);
        notification.setAudience(audience);
        notification.setType("NOTICE");
        return notification;
    }

    private Notification notification(Long id) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setSchoolId(2L);
        notification.setTitle("School update");
        notification.setMessage("Important information");
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }

    private UserNotification inboxRow(Long id, String userId, Notification notification) {
        UserNotification row = new UserNotification();
        row.setId(id);
        row.setSchoolId(2L);
        row.setUserId(userId);
        row.setNotification(notification);
        row.setIsRead(false);
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }
}
