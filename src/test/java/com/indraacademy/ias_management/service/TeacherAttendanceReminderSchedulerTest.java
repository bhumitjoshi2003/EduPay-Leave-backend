package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherAttendance;
import com.indraacademy.ias_management.entity.TeacherAttendanceSchedule;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.entity.TeacherStatus;
import com.indraacademy.ias_management.entity.User;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.notification.ExternalDeliveryChannel;
import com.indraacademy.ias_management.notification.NotificationCategory;
import com.indraacademy.ias_management.notification.NotificationEventCode;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 2026-09-17 (used as "today" throughout) is a Thursday — matches the school's default working
 * days so the happy path doesn't need any special-casing.
 */
@ExtendWith(MockitoExtension.class)
class TeacherAttendanceReminderSchedulerTest {

    private static final Long SCHOOL_ID = 1L;
    private static final String TEACHER_ID = "T1";
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);
    private static final String WORKING_DAYS = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY";

    @Mock private SchoolRepository schoolRepository;
    @Mock private SchoolHolidayRepository schoolHolidayRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeacherAttendanceRepository teacherAttendanceRepository;
    @Mock private TeacherLeaveRepository teacherLeaveRepository;
    @Mock private TeacherAttendanceScheduleService teacherAttendanceScheduleService;
    @Mock private BusinessNotificationService businessNotifications;

    private TeacherAttendanceReminderScheduler scheduler;

    private School school(LocalTime reminderTime, boolean enabled) {
        School s = new School();
        s.setId(SCHOOL_ID);
        s.setActive(true);
        s.setTimezone("Asia/Kolkata");
        s.setWorkingDays(WORKING_DAYS);
        s.setTeacherAttendanceReminderEnabled(enabled);
        s.setTeacherAttendanceReminderTime(reminderTime);
        return s;
    }

    private Teacher teacher(String id) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setSchoolId(SCHOOL_ID);
        t.setStatus(TeacherStatus.ACTIVE);
        return t;
    }

    private User activeUser(String id) {
        User u = new User();
        u.setUserId(id);
        u.setSchoolId(SCHOOL_ID);
        u.setActive(true);
        return u;
    }

    private Clock clockAt(LocalDate date, LocalTime time, String zone) {
        return Clock.fixed(ZonedDateTime.of(date, time, ZoneId.of(zone)).toInstant(), ZoneId.of("UTC"));
    }

    /** Wires the happy-path defaults: one active teacher, no attendance/leave/holiday, expected
     * to work today via the plain school working-days pattern. Individual tests override just
     * the mock(s) relevant to what they're proving. */
    private void wireHappyPathDefaults(School school, Teacher t) {
        lenient().when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, SCHOOL_ID))
                .thenReturn(List.of(t));
        lenient().when(userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(SCHOOL_ID, List.of(t.getTeacherId())))
                .thenReturn(List.of(activeUser(t.getTeacherId())));
        lenient().when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, TODAY, TODAY)).thenReturn(List.of());
        lenient().when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, TODAY, TODAY)).thenReturn(List.of());
        lenient().when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, TODAY)).thenReturn(List.of());
        lenient().when(teacherAttendanceScheduleService.schedulesByTeacher(SCHOOL_ID, TODAY, TODAY))
                .thenReturn(Map.of());
        lenient().when(teacherAttendanceScheduleService.workingDaysFor(
                        eq(t.getTeacherId()), eq(TODAY), anyString(), any()))
                .thenReturn(school.getWorkingDays());
    }

    private void useClock(Clock clock) {
        scheduler = new TeacherAttendanceReminderScheduler();
        ReflectionTestUtils.setField(scheduler, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(scheduler, "schoolHolidayRepository", schoolHolidayRepository);
        ReflectionTestUtils.setField(scheduler, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(scheduler, "userRepository", userRepository);
        ReflectionTestUtils.setField(scheduler, "teacherAttendanceRepository", teacherAttendanceRepository);
        ReflectionTestUtils.setField(scheduler, "teacherLeaveRepository", teacherLeaveRepository);
        ReflectionTestUtils.setField(scheduler, "teacherAttendanceScheduleService", teacherAttendanceScheduleService);
        ReflectionTestUtils.setField(scheduler, "businessNotifications", businessNotifications);
        ReflectionTestUtils.setField(scheduler, "clock", clock);
    }

    @BeforeEach
    void setUp() {
        useClock(clockAt(TODAY, LocalTime.of(7, 47), "Asia/Kolkata")); // 2 min into the catch-up window by default
    }

    private String expectedKey() {
        return "teacher-attendance-reminder:" + SCHOOL_ID + ":" + TODAY + ":" + TEACHER_ID;
    }

    // A — expected today + no attendance + no leave → one reminder
    @Test
    void teacherExpectedToday_noAttendance_noLeave_getsOneReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications).direct(eq(SCHOOL_ID), eq(TEACHER_ID), eq(NotificationEventCode.ATTENDANCE_REMINDER),
                eq(NotificationCategory.ATTENDANCE), eq("Attendance reminder"), anyString(),
                eq("Teacher"), eq(TEACHER_ID), eq("/dashboard/teacher-checkin"), any(),
                eq(expectedKey()), eq(Set.of(ExternalDeliveryChannel.PUSH)));
    }

    // B — attendance already exists → no reminder
    @Test
    void attendanceAlreadyExists_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        TeacherAttendance record = new TeacherAttendance();
        record.setTeacherId(TEACHER_ID);
        record.setSchoolId(SCHOOL_ID);
        record.setDate(TODAY);
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, TODAY)).thenReturn(List.of(record));

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // C — admin-marked attendance exists → no reminder (markedByAdmin must not matter)
    @Test
    void adminMarkedAttendanceExists_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        TeacherAttendance record = new TeacherAttendance();
        record.setTeacherId(TEACHER_ID);
        record.setSchoolId(SCHOOL_ID);
        record.setDate(TODAY);
        record.setMarkedByAdmin(true);
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, TODAY)).thenReturn(List.of(record));

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // D — approved leave covers date → no reminder
    @Test
    void approvedLeaveCoversToday_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        TeacherLeave leave = new TeacherLeave();
        leave.setTeacherId(TEACHER_ID);
        leave.setSchoolId(SCHOOL_ID);
        leave.setStartDate(TODAY.minusDays(1));
        leave.setEndDate(TODAY.plusDays(1));
        leave.setStatus(LeaveStatus.APPROVED);
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, TODAY, TODAY)).thenReturn(List.of(leave));

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // E — pending leave never reaches findApprovedOverlapping → reminder still sent
    @Test
    void pendingLeave_doesNotSuppressTheReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        // findApprovedOverlapping only ever returns APPROVED rows — a pending leave never
        // appears here, exactly like TeacherAttendanceService's own approved-leave check.
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, TODAY, TODAY)).thenReturn(List.of());

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications).direct(eq(SCHOOL_ID), eq(TEACHER_ID), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(expectedKey()), anySet());
    }

    // F — rejected leave — same reasoning as pending
    @Test
    void rejectedLeave_doesNotSuppressTheReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, TODAY, TODAY)).thenReturn(List.of());

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications).direct(eq(SCHOOL_ID), eq(TEACHER_ID), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(expectedKey()), anySet());
    }

    // G — school holiday → no reminder
    @Test
    void schoolHolidayToday_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        SchoolHoliday holiday = new SchoolHoliday();
        holiday.setSchoolId(SCHOOL_ID);
        holiday.setStartDate(TODAY);
        holiday.setEndDate(TODAY);
        holiday.setName("Test Holiday");
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, TODAY, TODAY)).thenReturn(List.of(holiday));

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // H — teacher's custom schedule excludes today → no reminder
    @Test
    void teacherCustomScheduleExcludesToday_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        // Custom schedule working days deliberately excludes THURSDAY (today).
        when(teacherAttendanceScheduleService.workingDaysFor(eq(TEACHER_ID), eq(TODAY), anyString(), any()))
                .thenReturn("MONDAY,TUESDAY,WEDNESDAY,FRIDAY,SATURDAY");

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // I — teacher inactive → no reminder (never even offered to the user-active check)
    @Test
    void inactiveTeacher_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        // findByStatusAndSchoolId(ACTIVE, ...) naturally excludes a LEFT teacher — simulate by
        // returning no active teachers at all.
        when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, SCHOOL_ID)).thenReturn(List.of());

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void activeTeacherRecord_butInactiveLoginUser_noReminder() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);
        // The teacher row is ACTIVE, but the corresponding login account is not.
        when(userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(SCHOOL_ID, List.of(TEACHER_ID)))
                .thenReturn(List.of());

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // J — reminder disabled → no reminder, and the school is never even evaluated
    @Test
    void reminderDisabled_schoolNeverProcessed() {
        School school = school(LocalTime.of(7, 45), false);
        when(schoolRepository.findAll()).thenReturn(List.of(school));

        scheduler.sendTeacherAttendanceReminders();

        verify(teacherRepository, never()).findByStatusAndSchoolId(any(), any());
        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // K — before configured time → no reminder
    @Test
    void beforeConfiguredTime_noReminder() {
        useClock(clockAt(TODAY, LocalTime.of(7, 44), "Asia/Kolkata"));
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // L — inside catch-up window → reminder sent
    @Test
    void insideCatchUpWindow_reminderSent() {
        useClock(clockAt(TODAY, LocalTime.of(8, 10), "Asia/Kolkata")); // 25 min after 07:45, within 30-min window
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications).direct(eq(SCHOOL_ID), eq(TEACHER_ID), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(expectedKey()), anySet());
    }

    // M — after catch-up window → no late reminder
    @Test
    void afterCatchUpWindow_noLateReminder() {
        useClock(clockAt(TODAY, LocalTime.of(8, 16), "Asia/Kolkata")); // 31 min after 07:45, past the 30-min window
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, never()).direct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // N — scheduler executes twice with the same idempotency key: the DB-level guarantee itself
    // is proven in the Postgres IT; here we prove the scheduler always derives the exact same
    // deterministic key on every tick (a prerequisite for that guarantee to actually dedupe).
    @Test
    void repeatedTicksWithinTheWindow_alwaysUseTheSameIdempotencyKey() {
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);

        scheduler.sendTeacherAttendanceReminders();
        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications, times(2)).direct(eq(SCHOOL_ID), eq(TEACHER_ID), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(expectedKey()), anySet());
    }

    // O — different schools/timezones evaluated independently
    @Test
    void differentSchoolsAndTimezones_evaluatedIndependently() {
        // School A: Asia/Kolkata, reminder due now (07:47 IST is inside its 07:45 window).
        School schoolA = school(LocalTime.of(7, 45), true);
        schoolA.setId(10L);
        schoolA.setTimezone("Asia/Kolkata");
        Teacher teacherA = teacher("TA");
        teacherA.setSchoolId(10L);

        // School B: America/New_York. At the fixed instant (07:47 IST), New York local time is
        // the previous day, ~22:17 — nowhere near its own 07:45 reminder, so it must not fire.
        School schoolB = school(LocalTime.of(7, 45), true);
        schoolB.setId(20L);
        schoolB.setTimezone("America/New_York");
        Teacher teacherB = teacher("TB");
        teacherB.setSchoolId(20L);

        when(schoolRepository.findAll()).thenReturn(List.of(schoolA, schoolB));

        lenient().when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, 10L)).thenReturn(List.of(teacherA));
        lenient().when(userRepository.findBySchoolIdAndActiveTrueAndUserIdIn(eq(10L), any())).thenReturn(List.of(activeUser("TA")));
        lenient().when(schoolHolidayRepository.findOverlapping(eq(10L), any(), any())).thenReturn(List.of());
        lenient().when(teacherLeaveRepository.findApprovedOverlapping(eq(10L), any(), any())).thenReturn(List.of());
        lenient().when(teacherAttendanceRepository.findBySchoolIdAndDate(eq(10L), any())).thenReturn(List.of());
        lenient().when(teacherAttendanceScheduleService.schedulesByTeacher(eq(10L), any(), any())).thenReturn(Map.of());
        lenient().when(teacherAttendanceScheduleService.workingDaysFor(eq("TA"), any(), anyString(), any()))
                .thenReturn(WORKING_DAYS);

        lenient().when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, 20L)).thenReturn(List.of(teacherB));

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications).direct(eq(10L), eq("TA"), any(), any(), any(), any(),
                any(), any(), any(), any(), anyString(), anySet());
        verify(businessNotifications, never()).direct(eq(20L), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    // P — one school's processing failure must not stop other schools from being processed
    @Test
    void oneSchoolsFailure_otherSchoolsStillProcess() {
        School badSchool = school(LocalTime.of(7, 45), true);
        badSchool.setId(30L);
        School goodSchool = school(LocalTime.of(7, 45), true);
        goodSchool.setId(SCHOOL_ID);
        Teacher t = teacher(TEACHER_ID);

        when(schoolRepository.findAll()).thenReturn(List.of(badSchool, goodSchool));
        // Force an exception while evaluating the bad school.
        when(teacherRepository.findByStatusAndSchoolId(TeacherStatus.ACTIVE, 30L))
                .thenThrow(new RuntimeException("simulated failure"));
        wireHappyPathDefaults(goodSchool, t);

        scheduler.sendTeacherAttendanceReminders();

        verify(businessNotifications).direct(eq(SCHOOL_ID), eq(TEACHER_ID), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(expectedKey()), anySet());
    }

    // ─── isDueNow — the catch-up window boundary itself ─────────────────────────

    // ─── Dynamic-scheduling rollout flag (scenario 30 — mandatory zero-DB-interaction check) ────

    // Q — when dynamic scheduling is enabled, the legacy 5-minute poll must return before any DB
    // access at all, mirroring NotificationDeliveryWorker.poll()'s redisEnabled gate.
    @Test
    void dynamicSchedulingEnabled_legacyPollReturnsWithoutTouchingAnyRepository() {
        ReflectionTestUtils.setField(scheduler, "dynamicSchedulingEnabled", true);

        scheduler.sendTeacherAttendanceReminders();

        verifyNoInteractions(schoolRepository, schoolHolidayRepository, teacherRepository, userRepository,
                teacherAttendanceRepository, teacherLeaveRepository, teacherAttendanceScheduleService,
                businessNotifications);
    }

    @Test
    void dynamicSchedulingDisabled_legacyPollStillScansAsBefore() {
        ReflectionTestUtils.setField(scheduler, "dynamicSchedulingEnabled", false);
        School school = school(LocalTime.of(7, 45), true);
        Teacher t = teacher(TEACHER_ID);
        when(schoolRepository.findAll()).thenReturn(List.of(school));
        wireHappyPathDefaults(school, t);

        scheduler.sendTeacherAttendanceReminders();

        verify(schoolRepository).findAll();
        verify(businessNotifications).direct(eq(SCHOOL_ID), eq(TEACHER_ID), any(), any(), any(), any(),
                any(), any(), any(), any(), eq(expectedKey()), anySet());
    }

    @Test
    void isDueNow_boundaryChecks() {
        LocalTime reminder = LocalTime.of(7, 45);
        assertThat(TeacherAttendanceReminderScheduler.isDueNow(reminder, LocalTime.of(7, 44, 59))).isFalse();
        assertThat(TeacherAttendanceReminderScheduler.isDueNow(reminder, LocalTime.of(7, 45, 0))).isTrue();
        assertThat(TeacherAttendanceReminderScheduler.isDueNow(reminder, LocalTime.of(8, 14, 59))).isTrue();
        assertThat(TeacherAttendanceReminderScheduler.isDueNow(reminder, LocalTime.of(8, 15, 0))).isFalse();
    }
}
