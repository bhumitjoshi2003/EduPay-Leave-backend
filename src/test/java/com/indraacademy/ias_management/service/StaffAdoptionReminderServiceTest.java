package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.StaffAdoptionReminderPreviewResponse;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderSendResponse;
import com.indraacademy.ias_management.dto.StaffAdoptionReminderType;
import com.indraacademy.ias_management.dto.StaffAdoptionResponse;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.notification.NotificationPublication;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffAdoptionReminderServiceTest {
    @Mock StaffAdoptionService staffAdoptionService;
    @Mock BusinessNotificationService businessNotifications;
    @Mock SecurityUtil security;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneOffset.UTC);
    private StaffAdoptionReminderService service;

    @BeforeEach
    void setUp() {
        service = new StaffAdoptionReminderService(staffAdoptionService, businessNotifications, security, clock);
        lenient().when(security.getSchoolId()).thenReturn(42L);
        lenient().when(security.getUsername()).thenReturn("admin-1");
    }

    @Test
    void notStartedTypeTargetsOnlyNotStartedAccounts() {
        withTeachers(
                row("T1", "Asha", "NOT_STARTED", "UNKNOWN", "UNKNOWN"),
                row("T2", "Bina", "STARTED", "UNKNOWN", "UP_TO_DATE"),
                row("T3", "Charu", "DISABLED", "UNKNOWN", "UNKNOWN"),
                row("T4", "Divya", "ACCOUNT_PENDING", "UNKNOWN", "UNKNOWN"));

        StaffAdoptionReminderPreviewResponse preview = service.preview(StaffAdoptionReminderType.NOT_STARTED);

        assertThat(preview.count()).isEqualTo(1);
        assertThat(preview.teachers()).extracting(StaffAdoptionReminderPreviewResponse.TeacherSummary::teacherId)
                .containsExactly("T1");
    }

    @Test
    void outdatedAppTypeIncludesUpdateAvailableAndRequiredOnly() {
        withTeachers(
                row("T1", "Asha", "STARTED", "UNKNOWN", "UPDATE_AVAILABLE"),
                row("T2", "Bina", "STARTED", "UNKNOWN", "UPDATE_REQUIRED"),
                row("T3", "Charu", "STARTED", "UNKNOWN", "UP_TO_DATE"),
                row("T4", "Divya", "STARTED", "UNKNOWN", "UNKNOWN"),
                row("T5", "Esha", "DISABLED", "UNKNOWN", "UPDATE_REQUIRED"));

        StaffAdoptionReminderPreviewResponse preview = service.preview(StaffAdoptionReminderType.OUTDATED_APP);

        assertThat(preview.teachers()).extracting(StaffAdoptionReminderPreviewResponse.TeacherSummary::teacherId)
                .containsExactlyInAnyOrder("T1", "T2");
    }

    @Test
    void onboardingIncompleteTypeOnlyTargetsStartedTeachersWithUnknownOnboarding() {
        withTeachers(
                row("T1", "Asha", "STARTED", "UNKNOWN", "UP_TO_DATE"),
                row("T2", "Bina", "STARTED", "COMPLETED", "UP_TO_DATE"),
                row("T3", "Charu", "NOT_STARTED", "UNKNOWN", "UNKNOWN"),
                row("T4", "Divya", "ACCOUNT_PENDING", "UNKNOWN", "UNKNOWN"));

        StaffAdoptionReminderPreviewResponse preview = service.preview(StaffAdoptionReminderType.ONBOARDING_INCOMPLETE);

        assertThat(preview.teachers()).extracting(StaffAdoptionReminderPreviewResponse.TeacherSummary::teacherId)
                .containsExactly("T1");
    }

    @Test
    void noEligibleTeachersProducesEmptyPreviewAndSendsNothing() {
        withTeachers(row("T1", "Asha", "STARTED", "COMPLETED", "UP_TO_DATE"));

        StaffAdoptionReminderSendResponse response = service.send(StaffAdoptionReminderType.NOT_STARTED);

        assertThat(response.eligibleCount()).isZero();
        assertThat(response.sentCount()).isZero();
        assertThat(response.skippedRecentCount()).isZero();
        verifyNoInteractions(businessNotifications);
    }

    @Test
    void sendReResolvesRecipientsServerSideAndUsesAuthenticatedSchool() {
        withTeachers(row("T1", "Asha", "NOT_STARTED", "UNKNOWN", "UNKNOWN"));
        when(businessNotifications.direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet()))
                .thenReturn(publication(false));

        service.send(StaffAdoptionReminderType.NOT_STARTED);

        verify(businessNotifications).direct(eq(42L), eq("T1"),
                eq(NotificationEventCode.STAFF_ADOPTION_NOT_STARTED_REMINDER), eq(NotificationCategory.SYSTEM_ADMIN),
                eq("Getting started with Edunexify"), anyString(), eq("StaffAdoptionReminder"), eq("T1"),
                anyString(), eq("admin-1"), eq("staff-adoption-reminder:NOT_STARTED:T1:2026-09-22"),
                eq(Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL)));
    }

    @Test
    void duplicatePublicationIsCountedAsSkippedRecentNotSent() {
        withTeachers(
                row("T1", "Asha", "NOT_STARTED", "UNKNOWN", "UNKNOWN"),
                row("T2", "Bina", "NOT_STARTED", "UNKNOWN", "UNKNOWN"));
        when(businessNotifications.direct(anyLong(), eq("T1"), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet())).thenReturn(publication(false));
        when(businessNotifications.direct(anyLong(), eq("T2"), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet())).thenReturn(publication(true));

        StaffAdoptionReminderSendResponse response = service.send(StaffAdoptionReminderType.NOT_STARTED);

        assertThat(response.eligibleCount()).isEqualTo(2);
        assertThat(response.sentCount()).isEqualTo(1);
        assertThat(response.skippedRecentCount()).isEqualTo(1);
    }

    @Test
    void eventCodeMatchesReminderTypeForAllThreeCategories() {
        withTeachers(row("T1", "Asha", "NOT_STARTED", "UNKNOWN", "UNKNOWN"));
        when(businessNotifications.direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet())).thenReturn(publication(false));
        service.send(StaffAdoptionReminderType.NOT_STARTED);
        verify(businessNotifications).direct(anyLong(), anyString(),
                eq(NotificationEventCode.STAFF_ADOPTION_NOT_STARTED_REMINDER), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet());

        reset(businessNotifications);
        withTeachers(row("T2", "Bina", "STARTED", "UNKNOWN", "UPDATE_REQUIRED"));
        when(businessNotifications.direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet())).thenReturn(publication(false));
        service.send(StaffAdoptionReminderType.OUTDATED_APP);
        verify(businessNotifications).direct(anyLong(), anyString(),
                eq(NotificationEventCode.STAFF_ADOPTION_APP_UPDATE_REMINDER), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet());

        reset(businessNotifications);
        withTeachers(row("T3", "Charu", "STARTED", "UNKNOWN", "UP_TO_DATE"));
        when(businessNotifications.direct(anyLong(), anyString(), any(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet())).thenReturn(publication(false));
        service.send(StaffAdoptionReminderType.ONBOARDING_INCOMPLETE);
        verify(businessNotifications).direct(anyLong(), anyString(),
                eq(NotificationEventCode.STAFF_ADOPTION_ONBOARDING_REMINDER), any(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), anySet());
    }

    private void withTeachers(StaffAdoptionResponse.TeacherRow... rows) {
        when(staffAdoptionService.getStaffAdoption()).thenReturn(
                new StaffAdoptionResponse(new StaffAdoptionResponse.Summary(rows.length, 0, 0, 0, 0, 0, 0),
                        List.of(rows)));
    }

    private StaffAdoptionResponse.TeacherRow row(String teacherId, String name, String accountStatus,
                                                  String onboardingStatus, String appVersionStatus) {
        return new StaffAdoptionResponse.TeacherRow(teacherId, name, accountStatus, null, false, null,
                onboardingStatus, null, "ANDROID", "1.0.0", 1, appVersionStatus);
    }

    private NotificationPublication publication(boolean duplicate) {
        return new NotificationPublication(null, List.of(), List.of(), duplicate);
    }
}
