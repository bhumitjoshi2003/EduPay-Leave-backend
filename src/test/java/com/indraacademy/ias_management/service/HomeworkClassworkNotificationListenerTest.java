package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.notification.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeworkClassworkNotificationListenerTest {
    @Mock BusinessNotificationService notifications;

    static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    private HomeworkClassworkPostedEvent event(boolean classwork, boolean homework, LocalDate due, Long sectionId, String sectionName) {
        return new HomeworkClassworkPostedEvent(500L, 1L, 11L, 8L, sectionId, "8", sectionName, "Science",
                classwork, homework, DAY, due, "T1");
    }

    @Test
    void publishesOnePushAndInAppNotificationToTheClassSectionStudents() {
        new HomeworkClassworkNotificationListener(notifications).afterCommit(event(false, true, DAY.plusDays(1), 3L, "A"));

        verify(notifications, times(1)).publish(eq(1L), eq(NotificationEventCode.HOMEWORK_CLASSWORK_POSTED),
                eq(NotificationCategory.ACADEMICS_RESULTS), eq("New Science Homework"),
                eq("Homework has been posted for Class 8 A. Due tomorrow."),
                eq(NotificationAudience.classSectionStudents(11L, 8L, 3L)),
                eq("HomeworkClasswork"), eq("500"), eq("/dashboard/homework"), eq("T1"),
                eq("homework-classwork:500:posted"), eq(Set.of(ExternalDeliveryChannel.PUSH)));
    }

    @Test
    void titlesAndMessagesFollowWhatWasPosted() {
        assertThat(HomeworkClassworkNotificationListener.title(event(true, false, null, 3L, "A"))).isEqualTo("New Science Classwork");
        assertThat(HomeworkClassworkNotificationListener.message(event(true, false, null, 3L, "A")))
                .isEqualTo("Classwork has been posted for Class 8 A.");
        assertThat(HomeworkClassworkNotificationListener.title(event(true, true, DAY, null, null)))
                .isEqualTo("New Science Classwork & Homework");
        assertThat(HomeworkClassworkNotificationListener.message(event(true, true, DAY, null, null)))
                .isEqualTo("Classwork & Homework have been posted for Class 8. Due today.");
        assertThat(HomeworkClassworkNotificationListener.message(event(false, true, LocalDate.of(2026, 9, 28), 3L, "A")))
                .isEqualTo("Homework has been posted for Class 8 A. Due 28 Sep.");
        assertThat(HomeworkClassworkNotificationListener.message(event(false, true, null, 3L, "A")))
                .isEqualTo("Homework has been posted for Class 8 A.");
    }

    @Test
    void aWholeClassPostTargetsEverySection() {
        new HomeworkClassworkNotificationListener(notifications).afterCommit(event(true, false, null, null, null));
        verify(notifications).publish(anyLong(), any(), any(), anyString(), anyString(),
                eq(NotificationAudience.classSectionStudents(11L, 8L, null)), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void aNotificationFailureNeverPropagates() {
        when(notifications.publish(anyLong(), any(), any(), anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any())).thenThrow(new RuntimeException("fcm down"));

        assertThatCode(() -> new HomeworkClassworkNotificationListener(notifications).afterCommit(event(true, false, null, 3L, "A")))
                .doesNotThrowAnyException();
    }
}
