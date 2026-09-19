package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.util.SchoolTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Replaces {@link TeacherAttendanceReminderScheduler}'s blind global 5-minute PostgreSQL scan
 * (still present, but gated off by the {@code teacher.attendance.reminder.dynamic-scheduling.enabled}
 * flag when this is active — see that class) with one dynamically-scheduled task per
 * reminder-enabled school, firing at that specific school's configured local reminder time.
 *
 * <h2>Design summary</h2>
 * <ul>
 *   <li>Startup: one targeted query ({@link SchoolRepository#findByActiveTrueAndTeacherAttendanceReminderEnabledTrue()})
 *       schedules every enabled school's next occurrence — a restart never "loses" a reminder for
 *       longer than the existing {@link TeacherAttendanceReminderScheduler#CATCH_UP_WINDOW}.</li>
 *   <li>Admin settings changes reschedule only AFTER the owning transaction commits
 *       ({@link #onScheduleChanged}), so an aborted update never mutates live scheduling.</li>
 *   <li>Execution reuses {@link TeacherAttendanceReminderScheduler#processSchool(School)} verbatim
 *       — including its own {@code isDueNow} catch-up check, which doubles as this scheduler's
 *       execution-time lateness tolerance (a task that fires late due to JVM pause/thread-pool
 *       congestion is simply a no-op if it's now outside the catch-up window, exactly like the
 *       legacy scanner would have been).</li>
 *   <li>PostgreSQL remains authoritative throughout: the schedule registry is never treated as
 *       durable truth, and every execution reloads the school fresh before acting.</li>
 * </ul>
 *
 * <p>This class does no notification delivery itself and never touches Redis or FCM — it only
 * calls the existing {@code BusinessNotificationService.direct(...)} path (via
 * {@code processSchool}), exactly as the legacy scanner already does; the existing Redis-driven
 * delivery pipeline picks up from there unchanged.
 */
@Service
public class TeacherAttendanceReminderDynamicScheduler {

    private static final Logger log = LoggerFactory.getLogger(TeacherAttendanceReminderDynamicScheduler.class);

    @Value("${teacher.attendance.reminder.dynamic-scheduling.enabled:false}")
    private boolean dynamicSchedulingEnabled;

    @Value("${teacher.attendance.reminder.dynamic-scheduling.db-retry-delay-minutes:5}")
    private long dbRetryDelayMinutes;

    @Autowired private SchoolRepository schoolRepository;
    @Autowired private TeacherAttendanceReminderScheduler legacyScheduler;
    @Autowired private TeacherAttendanceReminderScheduleRegistry registry;
    @Autowired private TeacherAttendanceReminderExecutionLock executionLock;
    // Explicit qualifier since a second TaskScheduler bean (refundReconciliationTaskScheduler)
    // now also exists in the context — @Autowired-by-type alone would be ambiguous.
    @Autowired
    @Qualifier("teacherAttendanceReminderTaskScheduler")
    private TaskScheduler taskScheduler;
    @Autowired private DataSource dataSource;
    @Autowired private Clock clock;

    // ─── Startup rebuild ────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void rebuildSchedulesOnStartup() {
        if (!dynamicSchedulingEnabled) {
            return;
        }
        List<School> enabledSchools = schoolRepository.findByActiveTrueAndTeacherAttendanceReminderEnabledTrue();
        int scheduled = 0;
        for (School school : enabledSchools) {
            if (school.getTeacherAttendanceReminderTime() == null) {
                log.warn("School {} has the teacher attendance reminder enabled but no reminder time configured " +
                        "— skipping at startup.", school.getId());
                continue;
            }
            scheduleSchool(school);
            scheduled++;
        }
        log.info("Teacher attendance reminder dynamic scheduling: rebuilt {} school schedule(s) on startup.", scheduled);
    }

    // ─── Admin settings change — reschedule only after commit ────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleChanged(TeacherAttendanceReminderScheduleChangedEvent event) {
        if (!dynamicSchedulingEnabled) {
            return;
        }
        rescheduleSchool(event.schoolId());
    }

    /**
     * Cancels any currently-scheduled task for this school, then re-evaluates its CURRENT,
     * authoritative settings from PostgreSQL and schedules a new task if still eligible. This one
     * method serves enable (nothing to cancel, then schedules), disable (cancels, then the
     * eligibility check fails so nothing new is scheduled), time-change and timezone-change
     * (cancels the stale future, schedules a fresh one using current values) uniformly — there is
     * no need to diff which field actually changed.
     */
    private void rescheduleSchool(Long schoolId) {
        registry.cancel(schoolId);
        Optional<School> schoolOpt = schoolRepository.findById(schoolId);
        if (schoolOpt.isEmpty()) {
            return;
        }
        School school = schoolOpt.get();
        if (!isEligible(school)) {
            return;
        }
        scheduleSchool(school);
    }

    private boolean isEligible(School school) {
        return school.isActive() && school.isTeacherAttendanceReminderEnabled()
                && school.getTeacherAttendanceReminderTime() != null;
    }

    // ─── Scheduling ───────────────────────────────────────────────────────────────

    private void scheduleSchool(School school) {
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        Instant nextRun = calculateNextOccurrence(clock, zone, school.getTeacherAttendanceReminderTime(),
                TeacherAttendanceReminderScheduler.CATCH_UP_WINDOW);
        Long schoolId = school.getId();
        ScheduledFuture<?> future = taskScheduler.schedule(() -> executeScheduledReminder(schoolId, false), nextRun);
        registry.put(schoolId, future);
    }

    /**
     * The next instant at which this school's reminder task should run: today at the reminder
     * time if that's still in the future; "now" (fire immediately) if we're currently inside the
     * catch-up window past it; otherwise tomorrow at the reminder time. Used identically for
     * startup rebuild, after-commit rescheduling, and the normal "schedule the next day" step
     * after every execution — one calculation, reused everywhere, so there is no special-cased
     * "always tomorrow" path to accidentally diverge from the catch-up rule.
     */
    static Instant calculateNextOccurrence(Clock clock, ZoneId zone, LocalTime reminderTime, Duration catchUpWindow) {
        ZonedDateTime nowZoned = ZonedDateTime.now(clock.withZone(zone));
        ZonedDateTime todayAtReminderTime = nowZoned.toLocalDate().atTime(reminderTime).atZone(zone);

        if (nowZoned.isBefore(todayAtReminderTime)) {
            return todayAtReminderTime.toInstant();
        }
        // Window end is exclusive, matching TeacherAttendanceReminderScheduler.isDueNow exactly.
        if (nowZoned.isBefore(todayAtReminderTime.plus(catchUpWindow))) {
            return nowZoned.toInstant();
        }
        return todayAtReminderTime.plusDays(1).toInstant();
    }

    // ─── Execution ──────────────────────────────────────────────────────────────

    private void executeScheduledReminder(Long schoolId, boolean isRetry) {
        Optional<School> schoolOpt;
        try {
            schoolOpt = schoolRepository.findById(schoolId);
        } catch (Exception dbFailure) {
            handleFailure(schoolId, isRetry, dbFailure, null);
            return;
        }

        if (schoolOpt.isEmpty()) {
            log.info("Teacher attendance reminder: school {} no longer exists — removing its schedule.", schoolId);
            registry.remove(schoolId);
            return;
        }

        School school = schoolOpt.get();
        if (!isEligible(school)) {
            log.info("Teacher attendance reminder: school {} is no longer eligible at execution time " +
                    "— removing its schedule.", schoolId);
            registry.remove(schoolId);
            return;
        }

        try {
            runWithExecutionLock(school);
        } catch (Exception failure) {
            handleFailure(schoolId, isRetry, failure, school);
            return;
        }

        scheduleSchool(school);
    }

    private void runWithExecutionLock(School school) throws java.sql.SQLException {
        ZoneId zone = SchoolTimeUtil.zoneId(school);
        LocalDate today = ZonedDateTime.now(clock.withZone(zone)).toLocalDate();
        try (Connection lockConnection = dataSource.getConnection()) {
            boolean acquired = executionLock.tryAcquire(lockConnection, school.getId(), today);
            if (!acquired) {
                log.info("Teacher attendance reminder for schoolId={}, date={} is already owned by " +
                        "another instance — skipping this pass.", school.getId(), today);
                return;
            }
            try {
                legacyScheduler.processSchool(school);
            } finally {
                executionLock.release(lockConnection, school.getId(), today);
            }
        }
    }

    /**
     * Bounded, single-retry failure handling (section 22 of the spec): never a tight loop, never
     * unbounded tasks. The first failure schedules exactly one retry after a short delay; if that
     * retry also fails, we give up on today and fall back to the normal daily schedule — using the
     * already-loaded {@code lastKnownSchool} (not a fresh DB read, which would just fail again if
     * PostgreSQL is still down) to compute tomorrow's occurrence.
     */
    private void handleFailure(Long schoolId, boolean isRetry, Exception failure, School lastKnownSchool) {
        log.error("Teacher attendance reminder execution failed for schoolId={} (retry={}): {}",
                schoolId, isRetry, failure.getMessage(), failure);

        if (!isRetry) {
            Instant retryAt = Instant.now(clock).plus(Duration.ofMinutes(dbRetryDelayMinutes));
            ScheduledFuture<?> retryFuture = taskScheduler.schedule(
                    () -> executeScheduledReminder(schoolId, true), retryAt);
            registry.put(schoolId, retryFuture);
            return;
        }

        log.error("Teacher attendance reminder retry also failed for schoolId={} — resuming on the " +
                "normal daily schedule.", schoolId);
        if (lastKnownSchool != null) {
            scheduleSchool(lastKnownSchool);
        } else {
            log.error("Teacher attendance reminder: schoolId={} could not be reloaded even for fallback " +
                    "scheduling — no further automatic reminder will be scheduled for this school until " +
                    "a backend restart or its next settings change.", schoolId);
            registry.remove(schoolId);
        }
    }
}
