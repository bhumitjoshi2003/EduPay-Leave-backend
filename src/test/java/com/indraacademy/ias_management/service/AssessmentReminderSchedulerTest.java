package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Assessment;
import com.indraacademy.ias_management.entity.AssessmentType;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.notification.*;
import com.indraacademy.ias_management.repository.AssessmentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssessmentReminderSchedulerTest {
    @Mock AssessmentRepository assessments;
    @Mock SchoolRepository schools;
    @Mock BusinessNotificationService notifications;
    @Mock PlatformTransactionManager transactionManager;

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    private AssessmentReminderScheduler at(LocalTime istTime) {
        Instant now = TODAY.atTime(istTime).atZone(IST).toInstant();
        return new AssessmentReminderScheduler(assessments, schools, notifications, transactionManager, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void school(long id, String zone) {
        School s = new School();
        s.setId(id);
        s.setTimezone(zone);
        lenient().when(schools.findById(id)).thenReturn(Optional.of(s));
    }

    private Assessment assessment(long id, long schoolId, LocalDate date) {
        Assessment a = new Assessment();
        a.setId(id);
        a.setSchoolId(schoolId);
        a.setAcademicSessionId(11L);
        a.setClassId(8L);
        a.setSectionId(3L);
        a.setSubjectName("Science");
        a.setAssessmentType(AssessmentType.UNIT_TEST);
        a.setTitle("Unit Test 2");
        a.setAssessmentDate(date);
        a.setStartTime(LocalTime.of(10, 0));
        return a;
    }

    @Test
    void remindsTomorrowsAssessmentOnceAfterFourPmWithADateScopedIdempotencyKey() {
        school(1L, "Asia/Kolkata");
        when(assessments.findReminderCandidates(any(), any(), any())).thenReturn(List.of(assessment(500L, 1L, TODAY.plusDays(1))));
        when(assessments.markReminderSent(eq(500L), eq(TODAY.plusDays(1)), any())).thenReturn(1);

        assertThat(at(LocalTime.of(17, 0)).sendDueReminders()).isEqualTo(1);

        verify(notifications, times(1)).publish(eq(1L), eq(NotificationEventCode.ASSESSMENT_REMINDER),
                eq(NotificationCategory.ACADEMICS_RESULTS), eq("Tomorrow: Science Unit Test"),
                eq("Unit Test 2 is tomorrow at 10:00 AM. All the best!"),
                eq(NotificationAudience.classSectionStudents(11L, 8L, 3L)), eq("Assessment"), eq("500"),
                eq("/dashboard/assessments"), isNull(), eq("assessment:500:reminder:2026-09-24"),
                eq(Set.of(ExternalDeliveryChannel.PUSH)));
    }

    @Test
    void nothingIsSentBeforeFourPmOrForAssessmentsNotDueTomorrow() {
        school(1L, "Asia/Kolkata");
        when(assessments.findReminderCandidates(any(), any(), any())).thenReturn(List.of(
                assessment(500L, 1L, TODAY.plusDays(1)), assessment(501L, 1L, TODAY), assessment(502L, 1L, TODAY.plusDays(2))));

        assertThat(at(LocalTime.of(15, 30)).sendDueReminders()).isZero();
        assertThat(at(LocalTime.of(18, 0)).sendDueReminders()).isZero();   // 500 unclaimable below; others not due

        verify(assessments, never()).markReminderSent(eq(501L), any(), any());
        verify(assessments, never()).markReminderSent(eq(502L), any(), any());
        verifyNoInteractions(notifications);
    }

    @Test
    void anAlreadyRemindedRescheduledOrDeletedAssessmentLosesTheClaimAndIsNotRemindedAgain() {
        school(1L, "Asia/Kolkata");
        when(assessments.findReminderCandidates(any(), any(), any())).thenReturn(List.of(assessment(500L, 1L, TODAY.plusDays(1))));
        when(assessments.markReminderSent(eq(500L), any(), any())).thenReturn(0);

        assertThat(at(LocalTime.of(20, 0)).sendDueReminders()).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void aFailedPublishReleasesTheClaimSoTheNextPassRetries() {
        school(1L, "Asia/Kolkata");
        when(assessments.findReminderCandidates(any(), any(), any())).thenReturn(List.of(assessment(500L, 1L, TODAY.plusDays(1))));
        when(assessments.markReminderSent(eq(500L), any(), any())).thenReturn(1);
        when(notifications.publish(anyLong(), any(), any(), anyString(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), anyString(), any())).thenThrow(new RuntimeException("fcm down"));

        assertThat(at(LocalTime.of(17, 0)).sendDueReminders()).isZero();
        verify(assessments).releaseReminderClaim(eq(500L), any());
    }

    @Test
    void eachSchoolsOwnTimezoneDecidesWhatTomorrowAndFourPmMean() {
        // 17:00 IST on 23 Sep = 07:30 in New York (still 23 Sep, before 4 PM there).
        school(1L, "Asia/Kolkata");
        school(2L, "America/New_York");
        when(assessments.findReminderCandidates(any(), any(), any())).thenReturn(List.of(
                assessment(500L, 1L, TODAY.plusDays(1)), assessment(600L, 2L, TODAY.plusDays(1))));
        when(assessments.markReminderSent(eq(500L), any(), any())).thenReturn(1);

        assertThat(at(LocalTime.of(17, 0)).sendDueReminders()).isEqualTo(1);
        verify(assessments, never()).markReminderSent(eq(600L), any(), any());
    }

    @Test
    void theScheduledEntryPointNeverThrows() {
        when(assessments.findReminderCandidates(any(), any(), any())).thenThrow(new RuntimeException("db down"));
        at(LocalTime.of(17, 0)).run();
    }
}
