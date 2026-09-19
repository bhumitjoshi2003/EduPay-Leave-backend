package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.repository.SchoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the EDUNEXIFY teacher-attendance-reminder dynamic-scheduling spec's scenarios 27-33:
 * next-occurrence calculation (A-D), startup rebuild (28), admin after-commit rescheduling (29),
 * legacy-poll-disabled (30, covered separately in TeacherAttendanceReminderSchedulerTest),
 * execution correctness (31), failure handling (32), and idempotency-key stability under
 * duplicate/racing execution (33).
 */
@ExtendWith(MockitoExtension.class)
class TeacherAttendanceReminderDynamicSchedulerTest {

    private static final Long SCHOOL_ID = 1L;

    @Mock private SchoolRepository schoolRepository;
    @Mock private TeacherAttendanceReminderScheduler legacyScheduler;
    @Mock private TaskScheduler taskScheduler;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;

    private TeacherAttendanceReminderScheduleRegistry registry;
    private TeacherAttendanceReminderExecutionLock executionLock;
    private TeacherAttendanceReminderDynamicScheduler scheduler;

    private School school(LocalTime reminderTime, boolean enabled, String timezone) {
        School s = new School();
        s.setId(SCHOOL_ID);
        s.setActive(true);
        s.setTimezone(timezone);
        s.setTeacherAttendanceReminderEnabled(enabled);
        s.setTeacherAttendanceReminderTime(reminderTime);
        return s;
    }

    private void wire(Clock clock) {
        registry = new TeacherAttendanceReminderScheduleRegistry();
        executionLock = mock(TeacherAttendanceReminderExecutionLock.class);
        scheduler = new TeacherAttendanceReminderDynamicScheduler();
        ReflectionTestUtils.setField(scheduler, "dynamicSchedulingEnabled", true);
        ReflectionTestUtils.setField(scheduler, "dbRetryDelayMinutes", 5L);
        ReflectionTestUtils.setField(scheduler, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(scheduler, "legacyScheduler", legacyScheduler);
        ReflectionTestUtils.setField(scheduler, "registry", registry);
        ReflectionTestUtils.setField(scheduler, "executionLock", executionLock);
        ReflectionTestUtils.setField(scheduler, "taskScheduler", taskScheduler);
        ReflectionTestUtils.setField(scheduler, "dataSource", dataSource);
        ReflectionTestUtils.setField(scheduler, "clock", clock);
    }

    private Clock clockAt(LocalDate date, LocalTime time, String zone) {
        return Clock.fixed(ZonedDateTime.of(date, time, ZoneId.of(zone)).toInstant(), ZoneId.of("UTC"));
    }

    @BeforeEach
    void setUp() throws Exception {
        wire(clockAt(LocalDate.of(2026, 9, 17), LocalTime.of(8, 0), "Asia/Kolkata"));
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(executionLock.tryAcquire(any(), anyLong(), any())).thenReturn(true);
        lenient().when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(invocation -> mock(ScheduledFuture.class));
    }

    // ─── Next-occurrence calculation (scenarios A-D) ─────────────────────────────

    @Test
    void aTodayStillFuture_nextOccurrenceIsTodayAtReminderTime() {
        Clock clock = clockAt(LocalDate.of(2026, 9, 17), LocalTime.of(8, 0), "Asia/Kolkata");
        Instant next = TeacherAttendanceReminderDynamicScheduler.calculateNextOccurrence(
                clock, ZoneId.of("Asia/Kolkata"), LocalTime.of(8, 30), Duration.ofMinutes(30));

        ZonedDateTime expected = ZonedDateTime.of(2026, 9, 17, 8, 30, 0, 0, ZoneId.of("Asia/Kolkata"));
        assertThat(next).isEqualTo(expected.toInstant());
    }

    @Test
    void bPastCatchUpWindow_nextOccurrenceIsTomorrow() {
        Clock clock = clockAt(LocalDate.of(2026, 9, 17), LocalTime.of(9, 0), "Asia/Kolkata");
        Instant next = TeacherAttendanceReminderDynamicScheduler.calculateNextOccurrence(
                clock, ZoneId.of("Asia/Kolkata"), LocalTime.of(8, 30), Duration.ofMinutes(30));

        ZonedDateTime expected = ZonedDateTime.of(2026, 9, 18, 8, 30, 0, 0, ZoneId.of("Asia/Kolkata"));
        assertThat(next).isEqualTo(expected.toInstant());
    }

    @Test
    void cSchoolTimezoneIsUsed_notServerTimezone() {
        // 03:00 UTC is 08:30 IST — reminder due right now in the school's own zone, which would
        // look completely different if server-local (UTC) time were used instead.
        Clock utcClock = Clock.fixed(Instant.parse("2026-09-17T03:00:00Z"), ZoneId.of("UTC"));
        Instant next = TeacherAttendanceReminderDynamicScheduler.calculateNextOccurrence(
                utcClock, ZoneId.of("Asia/Kolkata"), LocalTime.of(8, 30), Duration.ofMinutes(30));

        assertThat(next).isEqualTo(Instant.parse("2026-09-17T03:00:00Z"));
    }

    @Test
    void withinCatchUpWindow_nextOccurrenceIsEssentiallyNow() {
        Clock clock = clockAt(LocalDate.of(2026, 9, 17), LocalTime.of(8, 40), "Asia/Kolkata"); // 10 min past 08:30
        Instant next = TeacherAttendanceReminderDynamicScheduler.calculateNextOccurrence(
                clock, ZoneId.of("Asia/Kolkata"), LocalTime.of(8, 30), Duration.ofMinutes(30));

        assertThat(next).isEqualTo(Instant.now(clock));
    }

    // ─── Startup rebuild (scenario 28) ────────────────────────────────────────────

    @Test
    void startupRebuild_doesNothingWhenDynamicSchedulingDisabled() {
        ReflectionTestUtils.setField(scheduler, "dynamicSchedulingEnabled", false);

        scheduler.rebuildSchedulesOnStartup();

        verify(schoolRepository, never()).findByActiveTrueAndTeacherAttendanceReminderEnabledTrue();
    }

    @Test
    void startupRebuild_usesTargetedQuery_notFindAll() {
        when(schoolRepository.findByActiveTrueAndTeacherAttendanceReminderEnabledTrue())
                .thenReturn(List.of(school(LocalTime.of(8, 30), true, "Asia/Kolkata")));

        scheduler.rebuildSchedulesOnStartup();

        verify(schoolRepository, never()).findAll();
        verify(schoolRepository).findByActiveTrueAndTeacherAttendanceReminderEnabledTrue();
    }

    @Test
    void startupRebuild_schedulesEveryEnabledSchool() {
        School a = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        School b = school(LocalTime.of(9, 10), true, "Asia/Kolkata");
        b.setId(2L);
        when(schoolRepository.findByActiveTrueAndTeacherAttendanceReminderEnabledTrue()).thenReturn(List.of(a, b));

        scheduler.rebuildSchedulesOnStartup();

        assertThat(registry.isScheduled(1L)).isTrue();
        assertThat(registry.isScheduled(2L)).isTrue();
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void startupRebuild_skipsASchoolMissingAReminderTime() {
        School broken = school(null, true, "Asia/Kolkata");
        when(schoolRepository.findByActiveTrueAndTeacherAttendanceReminderEnabledTrue()).thenReturn(List.of(broken));

        scheduler.rebuildSchedulesOnStartup();

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    // ─── Admin after-commit rescheduling (scenario 29) ───────────────────────────

    @Test
    void afterCommit_doesNothingWhenDynamicSchedulingDisabled() {
        ReflectionTestUtils.setField(scheduler, "dynamicSchedulingEnabled", false);

        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        verify(schoolRepository, never()).findById(any());
    }

    @Test
    void enable_afterCommit_schedulesTheSchool() {
        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(8, 30), true, "Asia/Kolkata")));

        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue();
    }

    @Test
    void disable_afterCommit_cancelsTheScheduledTask() {
        // First, schedule it (as if it had been enabled before).
        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(8, 30), true, "Asia/Kolkata")));
        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));
        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue();
        ScheduledFuture<?> originalFuture = captureLastScheduledFuture();

        // Now disable it.
        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(8, 30), false, "Asia/Kolkata")));
        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        assertThat(registry.isScheduled(SCHOOL_ID)).isFalse();
        verify(originalFuture).cancel(false);
    }

    @Test
    void timeChange_afterCommit_cancelsOldAndSchedulesNew() {
        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(8, 30), true, "Asia/Kolkata")));
        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));
        ScheduledFuture<?> originalFuture = captureLastScheduledFuture();

        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(9, 15), true, "Asia/Kolkata")));
        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        verify(originalFuture).cancel(false);
        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue();
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void timezoneChange_afterCommit_rebuildsUsingNewZone() {
        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(8, 30), true, "Asia/Kolkata")));
        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        when(schoolRepository.findById(SCHOOL_ID))
                .thenReturn(Optional.of(school(LocalTime.of(8, 30), true, "America/New_York")));
        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), instantCaptor.capture());
        // The two scheduled instants must differ since the same wall-clock reminder time in two
        // different zones is a different absolute Instant.
        List<Instant> captured = instantCaptor.getAllValues();
        assertThat(captured.get(0)).isNotEqualTo(captured.get(1));
    }

    @Test
    void schoolNoLongerExists_afterCommit_doesNotScheduleAnything() {
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.empty());

        scheduler.onScheduleChanged(new TeacherAttendanceReminderScheduleChangedEvent(SCHOOL_ID));

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(SCHOOL_ID)).isFalse();
    }

    // ─── Execution correctness (scenario 31) ─────────────────────────────────────

    @Test
    void execution_reloadsSchoolAndDelegatesToLegacyProcessSchool() throws Exception {
        School fresh = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(fresh));

        invokeExecute(SCHOOL_ID, false);

        verify(legacyScheduler).processSchool(fresh);
    }

    @Test
    void execution_reschedulesTheNextOccurrenceAfterRunning() throws Exception {
        School fresh = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(fresh));

        invokeExecute(SCHOOL_ID, false);

        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue();
    }

    @Test
    void execution_schoolDisabledMidFlight_doesNotProcessAndDoesNotReschedule() throws Exception {
        School disabled = school(LocalTime.of(8, 30), false, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(disabled));

        invokeExecute(SCHOOL_ID, false);

        verify(legacyScheduler, never()).processSchool(any());
        assertThat(registry.isScheduled(SCHOOL_ID)).isFalse();
    }

    @Test
    void execution_schoolDeletedMidFlight_doesNotProcessAndRemovesSchedule() throws Exception {
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.empty());

        invokeExecute(SCHOOL_ID, false);

        verify(legacyScheduler, never()).processSchool(any());
        assertThat(registry.isScheduled(SCHOOL_ID)).isFalse();
    }

    // ─── Multi-instance / duplicate safety (scenario 33) ─────────────────────────

    @Test
    void executionLockNotAcquired_skipsProcessingButStillReschedulesNextDay() throws Exception {
        School fresh = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(fresh));
        when(executionLock.tryAcquire(any(), anyLong(), any())).thenReturn(false);

        invokeExecute(SCHOOL_ID, false);

        verify(legacyScheduler, never()).processSchool(any());
        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue(); // tomorrow's occurrence still scheduled
    }

    @Test
    void executionLockIsAlwaysReleasedAfterProcessing() throws Exception {
        School fresh = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(fresh));

        invokeExecute(SCHOOL_ID, false);

        verify(executionLock).release(eq(connection), eq(SCHOOL_ID), any(LocalDate.class));
    }

    @Test
    void executionLockIsReleasedEvenWhenProcessSchoolThrows() throws Exception {
        School fresh = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(fresh));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(legacyScheduler).processSchool(fresh);

        invokeExecute(SCHOOL_ID, false);

        verify(executionLock).release(eq(connection), eq(SCHOOL_ID), any(LocalDate.class));
    }

    // ─── Failure handling (scenario 32) ───────────────────────────────────────────

    @Test
    void dbDownAtExecution_schedulesExactlyOneRetry_notATightLoop() throws Exception {
        when(schoolRepository.findById(SCHOOL_ID)).thenThrow(new RuntimeException("db down"));

        invokeExecute(SCHOOL_ID, false);

        // Exactly one retry task scheduled — never a tight/immediate re-invocation loop.
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue();
    }

    @Test
    void dbStillDownOnRetry_givesUpAndFallsBackToLastKnownSchoolForTomorrow() throws Exception {
        School fresh = school(LocalTime.of(8, 30), true, "Asia/Kolkata");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(fresh));
        org.mockito.Mockito.doThrow(new RuntimeException("still down")).when(legacyScheduler).processSchool(fresh);

        invokeExecute(SCHOOL_ID, true); // isRetry = true

        // No second retry is scheduled from within the retry itself — only the fallback
        // "tomorrow" schedule using the already-loaded school.
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(SCHOOL_ID)).isTrue();
    }

    @Test
    void dbDownOnBothAttempts_schoolReloadItselfFails_givesUpCleanlyWithoutSchedulingAnything() throws Exception {
        when(schoolRepository.findById(SCHOOL_ID)).thenThrow(new RuntimeException("db still down"));

        invokeExecute(SCHOOL_ID, true); // isRetry = true, and even the reload fails

        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.isScheduled(SCHOOL_ID)).isFalse();
    }

    @Test
    void oneSchoolsExecutionFailure_isIsolated_doesNotAffectRegistryOfAnotherSchool() throws Exception {
        Long otherSchoolId = 2L;
        registry.put(otherSchoolId, mock(ScheduledFuture.class));

        when(schoolRepository.findById(SCHOOL_ID)).thenThrow(new RuntimeException("db down"));
        invokeExecute(SCHOOL_ID, false);

        assertThat(registry.isScheduled(otherSchoolId)).isTrue();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ScheduledFuture<?> captureLastScheduledFuture() {
        return registry.isScheduled(SCHOOL_ID) ? peekRegistryFuture() : null;
    }

    private ScheduledFuture<?> peekRegistryFuture() {
        // TaskScheduler.schedule(...) was stubbed to always return a fresh mock; retrieve the one
        // actually stored for SCHOOL_ID via reflection on the registry's internal map.
        var map = (java.util.concurrent.ConcurrentHashMap<Long, ScheduledFuture<?>>)
                ReflectionTestUtils.getField(registry, "futuresBySchoolId");
        return map.get(SCHOOL_ID);
    }

    private void invokeExecute(Long schoolId, boolean isRetry) throws Exception {
        var method = TeacherAttendanceReminderDynamicScheduler.class
                .getDeclaredMethod("executeScheduledReminder", Long.class, boolean.class);
        method.setAccessible(true);
        method.invoke(scheduler, schoolId, isRetry);
    }
}
