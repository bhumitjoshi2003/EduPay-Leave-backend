package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.AssessmentType;
import com.indraacademy.ias_management.notification.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssessmentNotificationListenerTest {
    @Mock BusinessNotificationService notifications;

    private AssessmentNotificationEvent event(AssessmentNotificationEvent.Kind kind, LocalTime start) {
        return new AssessmentNotificationEvent(kind, 500L, 3L, 1L, 11L, 8L, 3L, "Science", AssessmentType.UNIT_TEST,
                "Unit Test 2", LocalDate.of(2026, 9, 30), start, "T1");
    }

    @Test
    void aScheduledAssessmentNotifiesTheClassSectionOnceInAppAndPush() {
        new AssessmentNotificationListener(notifications).afterCommit(event(AssessmentNotificationEvent.Kind.SCHEDULED, null));

        verify(notifications, times(1)).publish(eq(1L), eq(NotificationEventCode.ASSESSMENT_SCHEDULED),
                eq(NotificationCategory.ACADEMICS_RESULTS), eq("Upcoming Science Unit Test"),
                eq("Science Unit Test scheduled for 30 Sep."), eq(NotificationAudience.classSectionStudents(11L, 8L, 3L)),
                eq("Assessment"), eq("500"), eq("/dashboard/assessments"), eq("T1"), eq("assessment:500:scheduled"),
                eq(Set.of(ExternalDeliveryChannel.PUSH)));
    }

    @Test
    void anUpdateNotificationIsKeyedByRevisionAndStatesTheNewSchedule() {
        new AssessmentNotificationListener(notifications).afterCommit(event(AssessmentNotificationEvent.Kind.UPDATED, LocalTime.of(9, 30)));

        verify(notifications).publish(eq(1L), eq(NotificationEventCode.ASSESSMENT_UPDATED), any(),
                eq("Updated: Science Unit Test"), eq("Unit Test 2 is now scheduled for 30 Sep, 9:30 AM."),
                any(), anyString(), anyString(), anyString(), anyString(), eq("assessment:500:updated:3"), any());
    }

    @Test
    void otherTypeReadsAsAssessment() {
        assertThat(AssessmentNotifications.scheduledTitle("Maths", AssessmentType.OTHER)).isEqualTo("Upcoming Maths Assessment");
    }

    @Test
    void aNotificationFailureNeverPropagates() {
        when(notifications.publish(anyLong(), any(), any(), anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any())).thenThrow(new RuntimeException("fcm down"));
        assertThatCode(() -> new AssessmentNotificationListener(notifications)
                .afterCommit(event(AssessmentNotificationEvent.Kind.SCHEDULED, null))).doesNotThrowAnyException();
    }
}
