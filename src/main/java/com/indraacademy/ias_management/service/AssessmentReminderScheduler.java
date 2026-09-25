package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.Assessment;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationAudience;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.AssessmentRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The day-before reminder for assessments: a single scanner, every 30 minutes, that reminds a
 * class/section's enrolled students once — from {@link #REMIND_FROM} school-local time on the day
 * before the assessment (in-app + FCM push via the existing pipeline; no email).
 *
 * <h2>Idempotency</h2>
 * Each due assessment is first <em>claimed</em> by a conditional update that sets
 * {@code reminder_sent_at} only if it is still unset and the date still matches; only the caller
 * whose claim succeeds publishes. The publication itself also carries the tenant-scoped
 * idempotency key {@code assessment:<id>:reminder:<date>} (V48's unique index), so even a retry
 * after a partial failure can never produce a second reminder for the same date. If publishing
 * fails, the claim is released so the next tick retries.
 *
 * <p>Past assessments are never reminded (only "tomorrow" is due); deleted ones have no row to
 * claim; rescheduling clears {@code reminder_sent_at} (see AssessmentService), and an assessment
 * scheduled for today/tomorrow is created already-reminded because its "scheduled" notification
 * serves as the reminder.
 */
@Component
public class AssessmentReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(AssessmentReminderScheduler.class);
    static final LocalTime REMIND_FROM = LocalTime.of(16, 0);
    static final int BATCH = 1000;

    private final AssessmentRepository assessments;
    private final SchoolRepository schools;
    private final BusinessNotificationService notifications;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AssessmentReminderScheduler(AssessmentRepository assessments, SchoolRepository schools,
                                       BusinessNotificationService notifications, PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this.assessments = assessments;
        this.schools = schools;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void run() {
        try {
            int sent = sendDueReminders();
            if (sent > 0) log.info("Assessment reminders sent: {}", sent);
        } catch (RuntimeException failure) {
            log.error("Assessment reminder scan failed", failure);
        }
    }

    /** Returns the number of reminders published in this pass. */
    public int sendDueReminders() {
        Instant now = clock.instant();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        // Every school's local "tomorrow" lies within [UTC today, UTC today + 2].
        List<Assessment> candidates = assessments.findReminderCandidates(utcToday, utcToday.plusDays(2), PageRequest.of(0, BATCH));
        Map<Long, ZoneId> zones = new HashMap<>();
        int sent = 0;
        for (Assessment a : candidates) {
            ZoneId zone = zones.computeIfAbsent(a.getSchoolId(),
                    id -> SchoolTimeUtil.zoneId(schools.findById(id).orElse(null)));
            ZonedDateTime local = now.atZone(zone);
            if (!a.getAssessmentDate().equals(local.toLocalDate().plusDays(1))) continue;
            if (local.toLocalTime().isBefore(REMIND_FROM)) continue;
            if (remind(a, now)) sent++;
        }
        return sent;
    }

    private boolean remind(Assessment a, Instant now) {
        Integer claimed = tx.execute(s -> assessments.markReminderSent(a.getId(), a.getAssessmentDate(), now));
        if (claimed == null || claimed == 0) return false;   // deleted, rescheduled or already reminded
        try {
            notifications.publish(a.getSchoolId(), NotificationEventCode.ASSESSMENT_REMINDER, NotificationCategory.ACADEMICS_RESULTS,
                    AssessmentNotifications.reminderTitle(a.getSubjectName(), a.getAssessmentType()),
                    AssessmentNotifications.reminderBody(a.getTitle(), a.getStartTime()),
                    NotificationAudience.classSectionStudents(a.getAcademicSessionId(), a.getClassId(), a.getSectionId()),
                    "Assessment", String.valueOf(a.getId()), AssessmentNotifications.ACTION_ROUTE, null,
                    "assessment:" + a.getId() + ":reminder:" + a.getAssessmentDate(),
                    Set.of(ExternalDeliveryChannel.PUSH));
            return true;
        } catch (RuntimeException failure) {
            log.error("Assessment {} reminder could not be published; releasing the claim for retry", a.getId(), failure);
            tx.executeWithoutResult(s -> assessments.releaseReminderClaim(a.getId(), now));
            return false;
        }
    }
}
