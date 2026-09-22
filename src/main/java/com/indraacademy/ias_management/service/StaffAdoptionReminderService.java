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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Admin-triggered, explicitly-sent staff adoption reminders. This is intentionally NOT a second
 * notification system: eligibility reuses {@link StaffAdoptionService}'s existing classification
 * logic (so it can never drift from what the Staff Adoption dashboard shows), and delivery reuses
 * {@link BusinessNotificationService#direct} — the same single-recipient send path used elsewhere.
 *
 * <p>Duplicate suppression reuses the existing tenant-scoped idempotency-key mechanism in
 * {@link NotificationPublisher} (unique on schoolId+idempotencyKey) rather than a new table: the
 * key embeds the calendar day, so a teacher cannot be sent the same reminder type twice on the
 * same day. No scheduler, no automatic resend — every send here is a direct result of an admin
 * clicking "Send".
 */
@Service
public class StaffAdoptionReminderService {
    private final StaffAdoptionService staffAdoptionService;
    private final BusinessNotificationService businessNotifications;
    private final SecurityUtil security;
    private final Clock clock;

    public StaffAdoptionReminderService(StaffAdoptionService staffAdoptionService,
                                        BusinessNotificationService businessNotifications,
                                        SecurityUtil security, Clock clock) {
        this.staffAdoptionService = staffAdoptionService;
        this.businessNotifications = businessNotifications;
        this.security = security;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffAdoptionReminderPreviewResponse preview(StaffAdoptionReminderType type) {
        List<StaffAdoptionResponse.TeacherRow> eligible = eligibleTeachers(type);
        List<StaffAdoptionReminderPreviewResponse.TeacherSummary> teachers = eligible.stream()
                .map(row -> new StaffAdoptionReminderPreviewResponse.TeacherSummary(row.teacherId(), row.name()))
                .toList();
        return new StaffAdoptionReminderPreviewResponse(teachers.size(), teachers);
    }

    /** Recomputes eligible recipients itself from the authenticated admin's school — the caller
     *  supplies only a {@code type}, never recipient ids, so a stale or tampered client-side list
     *  can never expand who gets notified. */
    @Transactional
    public StaffAdoptionReminderSendResponse send(StaffAdoptionReminderType type) {
        Long schoolId = security.getSchoolId();
        String actor = security.getUsername();
        List<StaffAdoptionResponse.TeacherRow> eligible = eligibleTeachers(type);

        int sent = 0;
        int skippedRecent = 0;
        LocalDate today = LocalDate.now(clock);
        for (StaffAdoptionResponse.TeacherRow row : eligible) {
            String idempotencyKey = "staff-adoption-reminder:" + type.name() + ":" + row.teacherId() + ":" + today;
            NotificationPublication publication = businessNotifications.direct(
                    schoolId, row.teacherId(), eventCodeFor(type), NotificationCategory.SYSTEM_ADMIN,
                    titleFor(type), messageFor(type), "StaffAdoptionReminder", row.teacherId(),
                    actionRouteFor(type), actor, idempotencyKey,
                    Set.of(ExternalDeliveryChannel.PUSH, ExternalDeliveryChannel.EMAIL));
            if (publication.duplicate()) {
                skippedRecent++;
            } else {
                sent++;
            }
        }
        return new StaffAdoptionReminderSendResponse(type, eligible.size(), sent, skippedRecent);
    }

    private List<StaffAdoptionResponse.TeacherRow> eligibleTeachers(StaffAdoptionReminderType type) {
        return staffAdoptionService.getStaffAdoption().teachers().stream()
                .filter(row -> isEligible(row, type))
                .toList();
    }

    /** DISABLED and ACCOUNT_PENDING accounts are excluded for every reminder type: a disabled
     *  account should never be nudged, and an account-pending teacher has no usable login/email
     *  to reach yet. */
    private boolean isEligible(StaffAdoptionResponse.TeacherRow row, StaffAdoptionReminderType type) {
        if ("DISABLED".equals(row.accountStatus()) || "ACCOUNT_PENDING".equals(row.accountStatus())) {
            return false;
        }
        return switch (type) {
            case NOT_STARTED -> "NOT_STARTED".equals(row.accountStatus());
            case OUTDATED_APP -> "UPDATE_AVAILABLE".equals(row.appVersionStatus())
                    || "UPDATE_REQUIRED".equals(row.appVersionStatus());
            // Onboarding semantics today can only distinguish COMPLETED from UNKNOWN — UNKNOWN
            // does not mean "actively incomplete", it can also mean "never started at all". To
            // avoid conflating those, this targets only teachers who have demonstrably started
            // (STARTED) and have not yet completed onboarding.
            case ONBOARDING_INCOMPLETE -> "STARTED".equals(row.accountStatus())
                    && "UNKNOWN".equals(row.onboardingStatus());
        };
    }

    private NotificationEventCode eventCodeFor(StaffAdoptionReminderType type) {
        return switch (type) {
            case NOT_STARTED -> NotificationEventCode.STAFF_ADOPTION_NOT_STARTED_REMINDER;
            case OUTDATED_APP -> NotificationEventCode.STAFF_ADOPTION_APP_UPDATE_REMINDER;
            case ONBOARDING_INCOMPLETE -> NotificationEventCode.STAFF_ADOPTION_ONBOARDING_REMINDER;
        };
    }

    private String titleFor(StaffAdoptionReminderType type) {
        return switch (type) {
            case NOT_STARTED -> "Getting started with Edunexify";
            case OUTDATED_APP -> "Edunexify update available";
            case ONBOARDING_INCOMPLETE -> "Complete your Edunexify setup";
        };
    }

    private String messageFor(StaffAdoptionReminderType type) {
        return switch (type) {
            case NOT_STARTED -> "Your Edunexify account is ready. Please sign in when convenient to complete "
                    + "your setup and access school updates, attendance, timetable, and leave features.";
            case OUTDATED_APP -> "A newer version of the Edunexify app is available. Please update when "
                    + "convenient to receive the latest improvements and fixes.";
            case ONBOARDING_INCOMPLETE -> "A few setup steps are still incomplete in your Edunexify account. "
                    + "Open Getting Started to finish your setup.";
        };
    }

    private String actionRouteFor(StaffAdoptionReminderType type) {
        return switch (type) {
            case NOT_STARTED, ONBOARDING_INCOMPLETE -> "/dashboard/teacher-dashboard";
            case OUTDATED_APP -> null;
        };
    }
}
