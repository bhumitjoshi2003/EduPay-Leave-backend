package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassUpdateNotificationListenerTest {
    @Mock BusinessNotificationService notifications;

    private ClassUpdatePostedEvent event(Long sectionId, String sectionName, String subject) {
        return new ClassUpdatePostedEvent(500L, 1L, 11L, 8L, sectionId, "8", sectionName, subject, "T1");
    }

    @Test
    void publishesOnePushAndInAppNotificationToTheClassSectionStudents() {
        new ClassUpdateNotificationListener(notifications).afterCommit(event(3L, "A", "Science"));

        verify(notifications, times(1)).publish(eq(1L), eq(NotificationEventCode.CLASS_UPDATE_POSTED),
                eq(NotificationCategory.NOTICE_ANNOUNCEMENT), eq("Class Update — Science"),
                eq("A new class update has been posted."),
                eq(NotificationAudience.classSectionStudents(11L, 8L, 3L)),
                eq("ClassUpdate"), eq("500"), eq("/dashboard/class-updates"), eq("T1"),
                eq("class-update:500:posted"), eq(Set.of(ExternalDeliveryChannel.PUSH)));
    }

    @Test
    void aClassWideUpdateIsTitledWithTheClassAndTargetsEverySection() {
        assertThat(ClassUpdateNotificationListener.title(event(3L, "A", null))).isEqualTo("Class Update — Class 8 A");
        assertThat(ClassUpdateNotificationListener.title(event(null, null, " "))).isEqualTo("Class Update — Class 8");

        new ClassUpdateNotificationListener(notifications).afterCommit(event(null, null, null));
        verify(notifications).publish(anyLong(), any(), any(), anyString(), anyString(),
                eq(NotificationAudience.classSectionStudents(11L, 8L, null)), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void aNotificationFailureNeverPropagates() {
        when(notifications.publish(anyLong(), any(), any(), anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any())).thenThrow(new RuntimeException("fcm down"));

        assertThatCode(() -> new ClassUpdateNotificationListener(notifications).afterCommit(event(3L, "A", "Science")))
                .doesNotThrowAnyException();
    }
}
