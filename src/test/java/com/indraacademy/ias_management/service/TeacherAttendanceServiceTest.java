package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AdminMarkTeacherAttendanceRequest;
import com.indraacademy.ias_management.dto.TeacherAttendanceResponse;
import com.indraacademy.ias_management.dto.TeacherAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.TeacherAttendanceSessionSummaryDTO;
import com.indraacademy.ias_management.dto.TeacherAttendanceTodaySummaryDTO;
import com.indraacademy.ias_management.entity.LeaveStatus;
import com.indraacademy.ias_management.entity.School;
import com.indraacademy.ias_management.entity.SchoolHoliday;
import com.indraacademy.ias_management.entity.Teacher;
import com.indraacademy.ias_management.entity.TeacherAttendance;
import com.indraacademy.ias_management.entity.TeacherLeave;
import com.indraacademy.ias_management.repository.SchoolHolidayRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.TeacherAttendanceRepository;
import com.indraacademy.ias_management.repository.TeacherLeaveRepository;
import com.indraacademy.ias_management.repository.TeacherRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers TeacherAttendanceService's calendar-driven reconciliation: which days count, which
 * missing days resolve to ABSENT, which never do, and that every date/time decision is made in
 * the school's own configured timezone rather than the JVM's.
 *
 * <p>A real (not mocked) {@link Clock#fixed} is used throughout — Mockito can't meaningfully mock
 * "the current instant," and a fixed clock is what makes the timezone tests deterministic: the
 * same instant is asserted to resolve to different local dates/statuses depending on which zone
 * the school under test is configured with.
 */
@ExtendWith(MockitoExtension.class)
class TeacherAttendanceServiceTest {

    @Mock private TeacherAttendanceRepository teacherAttendanceRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private SchoolHolidayRepository schoolHolidayRepository;
    @Mock private TeacherRepository teacherRepository;
    @Mock private TeacherLeaveRepository teacherLeaveRepository;
    @Mock private SecurityUtil securityUtil;
    @Mock private AuditService auditService;
    @Mock private TeacherAttendanceScheduleService teacherAttendanceScheduleService;
    @Mock private HttpServletRequest request;

    private TeacherAttendanceService service;

    private static final Long SCHOOL_ID = 2L;

    @BeforeEach
    void setUp() {
        service = new TeacherAttendanceService();
        ReflectionTestUtils.setField(service, "teacherAttendanceRepository", teacherAttendanceRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "schoolHolidayRepository", schoolHolidayRepository);
        ReflectionTestUtils.setField(service, "teacherRepository", teacherRepository);
        ReflectionTestUtils.setField(service, "teacherLeaveRepository", teacherLeaveRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "teacherAttendanceScheduleService", teacherAttendanceScheduleService);
        // Default: no approved leave in play. Individual tests override this where the leave
        // integration itself is under test.
        lenient().when(teacherLeaveRepository.findApprovedOverlapping(any(), any(), any())).thenReturn(List.of());
        lenient().when(teacherAttendanceScheduleService.schedulesByTeacher(any(), any(), any()))
                .thenReturn(Map.of());
        lenient().when(teacherAttendanceScheduleService.workingDaysFor(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    private TeacherLeave approvedLeave(String teacherId, LocalDate start, LocalDate end) {
        TeacherLeave l = new TeacherLeave();
        l.setSchoolId(SCHOOL_ID);
        l.setTeacherId(teacherId);
        l.setTeacherName(teacherId);
        l.setStartDate(start);
        l.setEndDate(end);
        l.setReason("Test leave");
        l.setStatus(LeaveStatus.APPROVED);
        return l;
    }

    private void useClock(Instant instant) {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }

    private School school(String timezone) {
        School s = new School();
        s.setId(SCHOOL_ID);
        s.setTimezone(timezone);
        s.setWorkingDays("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY");
        s.setSchoolLatitude(12.9716);
        s.setSchoolLongitude(77.5946);
        s.setGeofenceRadius(200);
        s.setSchoolStartTime(LocalTime.of(9, 0));
        s.setLateThresholdMinutes(5);
        return s;
    }

    private Teacher teacher(String id, String name, LocalDate joiningDate) {
        Teacher t = new Teacher();
        t.setTeacherId(id);
        t.setSchoolId(SCHOOL_ID);
        t.setName(name);
        t.setJoiningDate(joiningDate);
        return t;
    }

    private TeacherAttendance row(String teacherId, LocalDate date, String status) {
        TeacherAttendance ta = new TeacherAttendance();
        ta.setTeacherId(teacherId);
        ta.setSchoolId(SCHOOL_ID);
        ta.setDate(date);
        ta.setStatus(status);
        return ta;
    }

    // ─── Timezone correctness ────────────────────────────────────────────────

    /**
     * 2026-08-17T19:30:00Z is 2026-08-18 01:00 in Asia/Kolkata (UTC+5:30) — already the next
     * calendar day — but 2026-08-17 12:30 in America/Los_Angeles (UTC-7). The same real instant
     * must resolve to different "today"s depending purely on the school's configured zone.
     */
    @Test
    void checkIn_resolvesTodayInTheSchoolsOwnTimezone_notTheJvmDefault() {
        Instant instant = Instant.parse("2026-08-17T19:30:00Z");
        useClock(instant);

        School kolkata = school("Asia/Kolkata");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(kolkata));
        when(schoolHolidayRepository.existsBySchoolIdAndDateInRange(SCHOOL_ID, LocalDate.of(2026, 8, 18))).thenReturn(false);
        when(teacherAttendanceRepository.findByTeacherIdAndDateAndSchoolId("T1", LocalDate.of(2026, 8, 18), SCHOOL_ID))
                .thenReturn(Optional.empty());
        when(teacherAttendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Teacher One", LocalDate.of(2026, 1, 1))));

        TeacherAttendanceResponse resp = service.checkIn(12.9716, 77.5946, request);

        // Aug 18 in Kolkata, not Aug 17 as a JVM-default (e.g. UTC) reading of the same instant would give.
        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 8, 18));
    }

    @Test
    void checkIn_sameInstant_resolvesADifferentCalendarDayInADifferentSchoolTimezone() {
        Instant instant = Instant.parse("2026-08-17T19:30:00Z");
        useClock(instant);

        School losAngeles = school("America/Los_Angeles");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(losAngeles));
        when(schoolHolidayRepository.existsBySchoolIdAndDateInRange(SCHOOL_ID, LocalDate.of(2026, 8, 17))).thenReturn(false);
        when(teacherAttendanceRepository.findByTeacherIdAndDateAndSchoolId("T1", LocalDate.of(2026, 8, 17), SCHOOL_ID))
                .thenReturn(Optional.empty());
        when(teacherAttendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Teacher One", LocalDate.of(2026, 1, 1))));

        TeacherAttendanceResponse resp = service.checkIn(12.9716, 77.5946, request);

        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    void checkIn_lateStatus_computedAgainstTheSchoolsLocalClockTime_notJvmDefault() {
        // 09:10 in Asia/Kolkata (UTC+5:30) — 10 minutes after a 09:00 start with a 5-minute grace
        // period, so this must be LATE. The same instant in UTC would read as 03:40 — long before
        // the 09:00 threshold — so a JVM-default (non-zone-aware) reading would wrongly say ON_TIME.
        Instant instant = Instant.parse("2026-08-17T03:40:00Z");
        useClock(instant);

        School kolkata = school("Asia/Kolkata");
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(kolkata));
        when(schoolHolidayRepository.existsBySchoolIdAndDateInRange(eq(SCHOOL_ID), any())).thenReturn(false);
        when(teacherAttendanceRepository.findByTeacherIdAndDateAndSchoolId(eq("T1"), any(), eq(SCHOOL_ID)))
                .thenReturn(Optional.empty());
        when(teacherAttendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Teacher One", LocalDate.of(2026, 1, 1))));

        TeacherAttendanceResponse resp = service.checkIn(12.9716, 77.5946, request);

        assertThat(resp.getStatus()).isEqualTo(TeacherAttendanceService.STATUS_LATE);
    }

    @Test
    void checkIn_blankTimezone_fallsBackToDefault_ratherThanThrowing() {
        Instant instant = Instant.parse("2026-08-17T04:00:00Z"); // 09:30 IST
        useClock(instant);

        School noZone = school(null);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(noZone));
        when(schoolHolidayRepository.existsBySchoolIdAndDateInRange(eq(SCHOOL_ID), any())).thenReturn(false);
        when(teacherAttendanceRepository.findByTeacherIdAndDateAndSchoolId(eq("T1"), any(), eq(SCHOOL_ID)))
                .thenReturn(Optional.empty());
        when(teacherAttendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Teacher One", LocalDate.of(2026, 1, 1))));

        TeacherAttendanceResponse resp = service.checkIn(12.9716, 77.5946, request);

        assertThat(resp.getDate()).isEqualTo(LocalDate.of(2026, 8, 17)); // resolved as Asia/Kolkata, the default
    }

    // ─── Holiday-aware working-day checks ───────────────────────────────────

    @Test
    void checkIn_blockedOnADeclaredHoliday_evenOnAnOrdinarilyWorkingWeekday() {
        // Monday, a working day per the weekly pattern — but a declared holiday.
        Instant instant = Instant.parse("2026-08-17T04:00:00Z"); // 09:30 IST, Monday Aug 17 2026
        useClock(instant);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Teacher One", LocalDate.of(2026, 1, 1))));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(schoolHolidayRepository.existsBySchoolIdAndDateInRange(SCHOOL_ID, LocalDate.of(2026, 8, 17))).thenReturn(true);

        assertThatThrownBy(() -> service.checkIn(12.9716, 77.5946, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a working day");

        verify(teacherAttendanceRepository, never()).save(any());
    }

    // ─── Missing-row resolution (getAttendanceByDate) ───────────────────────

    @Test
    void getAttendanceByDate_pastWorkingDayWithNoRow_resolvesToAbsent() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z"); // "today" = Aug 20 IST
        useClock(instant);
        LocalDate pastMonday = LocalDate.of(2026, 8, 17); // a settled past working day

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastMonday)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastMonday, pastMonday)).thenReturn(List.of());

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastMonday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TeacherAttendanceService.STATUS_ABSENT);
        assertThat(result.get(0).getTeacherId()).isEqualTo("T1");
        assertThat(result.get(0).getId()).isNegative(); // synthesized, never persisted
    }

    @Test
    void getAttendanceByDate_beforeTrackingStart_doesNotSynthesizeAbsence() {
        useClock(Instant.parse("2026-08-20T04:00:00Z"));
        LocalDate preRolloutDate = LocalDate.of(2026, 8, 17);
        School school = school("Asia/Kolkata");
        school.setStaffAttendanceTrackingStartDate(LocalDate.of(2026, 8, 18));

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, preRolloutDate)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));

        assertThat(service.getAttendanceByDate(preRolloutDate)).isEmpty();
        verify(schoolHolidayRepository, never()).findOverlapping(any(), any(), any());
    }

    @Test
    void getAttendanceByDate_pastWeekendWithNoRow_isNotAbsent() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate pastSunday = LocalDate.of(2026, 8, 16); // Sunday — not in the working-days pattern

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastSunday)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastSunday, pastSunday)).thenReturn(List.of());

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastSunday);

        assertThat(result).isEmpty(); // nobody is "absent" from a day the school wasn't open
    }

    @Test
    void getAttendanceByDate_pastHolidayWithNoRow_isNotAbsent_evenOnAWorkingWeekday() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate holidayMonday = LocalDate.of(2026, 8, 17); // Monday, ordinarily working — declared a holiday

        SchoolHoliday holiday = new SchoolHoliday();
        holiday.setStartDate(holidayMonday);
        holiday.setEndDate(holidayMonday);
        holiday.setName("Independence Day");

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, holidayMonday)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, holidayMonday, holidayMonday)).thenReturn(List.of(holiday));

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(holidayMonday);

        assertThat(result).isEmpty();
    }

    @Test
    void getAttendanceByDate_today_isNeverResolvedToAbsent_regardlessOfTimeOfDay() {
        // Late in the working day, nobody has checked in yet — must NOT show as absent; the day
        // isn't over. The working-day/holiday check itself now runs unconditionally (it's also
        // needed to gate today's ON_LEAVE synthesis), so unlike before this only asserts the
        // ABSENT-specific outcome, not that the holiday lookup was skipped entirely.
        Instant instant = Instant.parse("2026-08-17T14:00:00Z"); // 19:30 IST, Monday
        useClock(instant);
        LocalDate today = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, today)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, today, today)).thenReturn(List.of());

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(today);

        assertThat(result).isEmpty();
    }

    @Test
    void getAttendanceByDate_pastDay_teacherWhoJoinedAfterThatDate_isNotMarkedAbsent() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate pastMonday = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastMonday)).thenReturn(List.of());
        // Joined the day AFTER the queried past date.
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", LocalDate.of(2026, 8, 18))));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastMonday, pastMonday)).thenReturn(List.of());

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastMonday);

        assertThat(result).isEmpty();
    }

    @Test
    void getAttendanceByDate_realRowAlwaysWinsOverSynthesis_neverDuplicated() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate pastMonday = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastMonday))
                .thenReturn(List.of(row("T1", pastMonday, TeacherAttendanceService.STATUS_ON_LEAVE)));
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastMonday, pastMonday)).thenReturn(List.of());

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastMonday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TeacherAttendanceService.STATUS_ON_LEAVE);
        assertThat(result.get(0).getId()).isNull(); // real (unsaved-in-this-test) row, not synthesized
    }

    // ─── Approved TeacherLeave resolution (getAttendanceByDate) ─────────────

    @Test
    void getAttendanceByDate_pastWorkingDayCoveredByApprovedLeave_resolvesToOnLeave_notAbsent() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate pastMonday = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastMonday)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastMonday, pastMonday)).thenReturn(List.of());
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, pastMonday, pastMonday))
                .thenReturn(List.of(approvedLeave("T1", LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 18))));

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastMonday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TeacherAttendanceService.STATUS_ON_LEAVE);
        assertThat(result.get(0).getId()).isNegative(); // synthesized, never persisted
    }

    @Test
    void getAttendanceByDate_pendingLeave_doesNotResolveToOnLeave_stillResolvesToAbsent() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate pastMonday = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastMonday)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastMonday, pastMonday)).thenReturn(List.of());
        // findApprovedOverlapping is defined (at the repository level) to only ever return APPROVED
        // rows, so a PENDING/REJECTED leave simply never appears here — nothing to stub differently;
        // the default empty-list stub from setUp already represents this case.

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastMonday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TeacherAttendanceService.STATUS_ABSENT);
    }

    @Test
    void getAttendanceByDate_realRowWinsOverApprovedLeave_neverDuplicatedOrOverridden() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate pastMonday = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        // A real, actually-worked row exists (e.g. leave was approved but the teacher still checked in).
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, pastMonday))
                .thenReturn(List.of(row("T1", pastMonday, TeacherAttendanceService.STATUS_ON_TIME)));
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, pastMonday, pastMonday)).thenReturn(List.of());
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, pastMonday, pastMonday))
                .thenReturn(List.of(approvedLeave("T1", pastMonday, pastMonday)));

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(pastMonday);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TeacherAttendanceService.STATUS_ON_TIME);
        assertThat(result.get(0).getId()).isNull(); // real row, not synthesized
    }

    /**
     * The one deliberate asymmetry: ABSENT is never synthesized for today (the day isn't over),
     * but ON_LEAVE is — an approved leave is already a settled, human-decided fact.
     */
    @Test
    void getAttendanceByDate_today_coveredByApprovedLeave_stillResolvesToOnLeave() {
        Instant instant = Instant.parse("2026-08-17T04:00:00Z"); // "today" = Aug 17 IST
        useClock(instant);
        LocalDate today = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, today)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, today, today)).thenReturn(List.of());
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, today, today))
                .thenReturn(List.of(approvedLeave("T1", today, today)));

        List<TeacherAttendanceResponse> result = service.getAttendanceByDate(today);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TeacherAttendanceService.STATUS_ON_LEAVE);
    }

    // ─── Today summary (admin dashboard widget) ─────────────────────────────

    @Test
    void todaySummary_bucketsEachStatus_fromTheAuthoritativeGetAttendanceByDateCall() {
        Instant instant = Instant.parse("2026-08-17T14:00:00Z"); // 19:30 IST, Monday — a working day
        useClock(instant);
        LocalDate today = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, today, today)).thenReturn(List.of());
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, today)).thenReturn(List.of(
                row("T1", today, TeacherAttendanceService.STATUS_ON_TIME),
                row("T2", today, TeacherAttendanceService.STATUS_LATE),
                row("T3", today, TeacherAttendanceService.STATUS_ABSENT), // explicit admin-marked, not synthesized
                row("T4", today, TeacherAttendanceService.STATUS_HALF_DAY)
        ));
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(
                teacher("T1", "Alex", null), teacher("T2", "Sam", null),
                teacher("T3", "Jo", null), teacher("T4", "Lee", null), teacher("T5", "Kim", null)
        ));
        when(teacherLeaveRepository.findApprovedOverlapping(SCHOOL_ID, today, today))
                .thenReturn(List.of(approvedLeave("T5", today, today)));

        TeacherAttendanceTodaySummaryDTO dto = service.getTodaySummary();

        assertThat(dto.getDate()).isEqualTo(today);
        assertThat(dto.getPresentCount()).isEqualTo(1);
        assertThat(dto.getLateCount()).isEqualTo(1);
        assertThat(dto.getAbsentCount()).isEqualTo(1);
        assertThat(dto.getHalfDayCount()).isEqualTo(1);
        assertThat(dto.getOnLeaveCount()).isEqualTo(1);
    }

    @Test
    void todaySummary_neverSynthesizesAbsentForToday_unmarkedTeachersAreSimplyExcluded() {
        Instant instant = Instant.parse("2026-08-17T04:00:00Z"); // 09:30 IST, Monday — early in the day
        useClock(instant);
        LocalDate today = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findBySchoolIdAndDate(SCHOOL_ID, today)).thenReturn(List.of());
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(SCHOOL_ID, today, today)).thenReturn(List.of());

        TeacherAttendanceTodaySummaryDTO dto = service.getTodaySummary();

        assertThat(dto.getAbsentCount()).isZero();
        assertThat(dto.getPresentCount()).isZero();
        assertThat(dto.getLateCount()).isZero();
        assertThat(dto.getHalfDayCount()).isZero();
        assertThat(dto.getOnLeaveCount()).isZero();
    }

    // ─── Monthly summary correctness ────────────────────────────────────────

    @Test
    void summary_totalWorkingDays_equalsTheSumOfAllStatusCounts_forASingleTeacher() {
        // A month that has fully elapsed relative to "today" — every working day is settled.
        Instant instant = Instant.parse("2026-09-01T04:00:00Z"); // Sept 1 IST — August is fully over
        useClock(instant);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        // Only a couple of real rows — everything else in August must be filled as ABSENT.
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(
                        row("T1", LocalDate.of(2026, 8, 3), TeacherAttendanceService.STATUS_ON_TIME),
                        row("T1", LocalDate.of(2026, 8, 4), TeacherAttendanceService.STATUS_LATE)
                ));

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        assertThat(dto.getTotalWorkingDays())
                .isEqualTo(dto.getPresentDays() + dto.getAbsentDays() + dto.getHalfDayDays() + dto.getOnLeaveDays());
        assertThat(dto.getPresentDays()).isEqualTo(2); // ON_TIME + LATE both count as present
        assertThat(dto.getLateDays()).isEqualTo(1);
        // August 2026 has 26 weekdays under a Mon-Sat pattern; 2 are real rows, the rest ABSENT.
        assertThat(dto.getAbsentDays()).isEqualTo(dto.getTotalWorkingDays() - 2);
    }

    @Test
    void summary_midMonthTrackingStart_excludesEarlierWorkingDaysFromAbsences() {
        useClock(Instant.parse("2026-09-01T04:00:00Z"));
        School school = school("Asia/Kolkata");
        LocalDate trackingStart = LocalDate.of(2026, 8, 18);
        school.setStaffAttendanceTrackingStartDate(trackingStart);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", LocalDate.of(2020, 1, 1))));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("T1", trackingStart, TeacherAttendanceService.STATUS_ON_TIME)));

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        assertThat(dto.getTrackingStartDate()).isEqualTo(trackingStart);
        assertThat(dto.getTotalWorkingDays()).isEqualTo(12);
        assertThat(dto.getPresentDays()).isEqualTo(1);
        assertThat(dto.getAbsentDays()).isEqualTo(11);
        assertThat(dto.getRecords()).allMatch(r -> !r.getDate().isBefore(trackingStart));
    }

    @Test
    void summary_currentMonth_todayWithNoRow_isExcludedEntirely_notCountedAsAbsentOrWorking() {
        Instant instant = Instant.parse("2026-08-17T04:00:00Z"); // "today" = Aug 17, mid-month
        useClock(instant);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of()); // no rows at all, including none for today

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        boolean todayCounted = dto.getRecords().stream().anyMatch(r -> LocalDate.of(2026, 8, 17).equals(r.getDate()));
        assertThat(todayCounted).isFalse();
        assertThat(dto.getTotalWorkingDays())
                .isEqualTo(dto.getPresentDays() + dto.getAbsentDays() + dto.getHalfDayDays() + dto.getOnLeaveDays());
    }

    @Test
    void summary_currentMonth_todayWithARealRow_isIncludedAndCounted() {
        Instant instant = Instant.parse("2026-08-17T04:00:00Z");
        useClock(instant);
        LocalDate today = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("T1", today, TeacherAttendanceService.STATUS_ON_TIME)));

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        boolean todayCounted = dto.getRecords().stream().anyMatch(r -> today.equals(r.getDate()) && "ON_TIME".equals(r.getStatus()));
        assertThat(todayCounted).isTrue();
        assertThat(dto.getTotalWorkingDays())
                .isEqualTo(dto.getPresentDays() + dto.getAbsentDays() + dto.getHalfDayDays() + dto.getOnLeaveDays());
    }

    @Test
    void summary_respectsJoiningDate_daysBeforeJoiningAreExcludedFromTotalAndNeverFilled() {
        Instant instant = Instant.parse("2026-09-01T04:00:00Z");
        useClock(instant);
        LocalDate joinedMidAugust = LocalDate.of(2026, 8, 17);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", joinedMidAugust)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of());

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        assertThat(dto.getRecords()).allSatisfy(r -> assertThat(r.getDate()).isAfterOrEqualTo(joinedMidAugust));
        assertThat(dto.getTotalWorkingDays())
                .isEqualTo(dto.getPresentDays() + dto.getAbsentDays() + dto.getHalfDayDays() + dto.getOnLeaveDays());
    }

    @Test
    void schoolWideSummary_countsAcrossMultipleTeachers_eachRespectingTheirOwnJoiningDate() {
        Instant instant = Instant.parse("2026-09-01T04:00:00Z");
        useClock(instant);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findBySchoolId(SCHOOL_ID)).thenReturn(List.of(
                teacher("T1", "Alex", null),
                teacher("T2", "Sam", LocalDate.of(2026, 8, 20)) // joined late in the month
        ));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findBySchoolIdAndDateBetweenOrderByDateAsc(eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of());

        TeacherAttendanceSummaryDTO dto = service.getSummary(8, 2026);

        // T2 only contributes absences from Aug 20 onward — never before they joined.
        long t2Records = dto.getRecords().stream().filter(r -> "T2".equals(r.getTeacherId())).count();
        assertThat(dto.getRecords()).filteredOn(r -> "T2".equals(r.getTeacherId()))
                .allSatisfy(r -> assertThat(r.getDate()).isAfterOrEqualTo(LocalDate.of(2026, 8, 20)));
        long t1Records = dto.getRecords().stream().filter(r -> "T1".equals(r.getTeacherId())).count();
        assertThat(t1Records).isGreaterThan(t2Records); // T1 has no join-date restriction, more assessed days
    }

    @Test
    void summary_approvedLeaveOnASettledDay_countsAsOnLeave_notAbsent_andPreservesTheTotalWorkingDaysInvariant() {
        Instant instant = Instant.parse("2026-09-01T04:00:00Z"); // August is fully over
        useClock(instant);
        LocalDate leaveDay = LocalDate.of(2026, 8, 5);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherLeaveRepository.findApprovedOverlapping(eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(approvedLeave("T1", leaveDay, leaveDay)));
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of()); // no real row on leaveDay — must resolve from the leave, not ABSENT

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        assertThat(dto.getOnLeaveDays()).isEqualTo(1);
        boolean leaveDayIsOnLeave = dto.getRecords().stream()
                .anyMatch(r -> leaveDay.equals(r.getDate()) && "ON_LEAVE".equals(r.getStatus()));
        assertThat(leaveDayIsOnLeave).isTrue();
        assertThat(dto.getTotalWorkingDays())
                .isEqualTo(dto.getPresentDays() + dto.getAbsentDays() + dto.getHalfDayDays() + dto.getOnLeaveDays());
    }

    @Test
    void summary_realRowOnALeaveCoveredDay_winsOverTheLeave_neverOverridden() {
        Instant instant = Instant.parse("2026-09-01T04:00:00Z");
        useClock(instant);
        LocalDate day = LocalDate.of(2026, 8, 5);

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("T1");
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherLeaveRepository.findApprovedOverlapping(eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(approvedLeave("T1", day, day)));
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("T1", day, TeacherAttendanceService.STATUS_ON_TIME)));

        TeacherAttendanceSummaryDTO dto = service.getMyAttendance(8, 2026);

        assertThat(dto.getOnLeaveDays()).isZero();
        boolean dayIsOnTime = dto.getRecords().stream()
                .anyMatch(r -> day.equals(r.getDate()) && "ON_TIME".equals(r.getStatus()));
        assertThat(dayIsOnTime).isTrue();
    }

    @Test
    void adminTeacherSummary_isScopedToTheSelectedTeacher() {
        useClock(Instant.parse("2026-09-01T04:00:00Z"));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any())).thenReturn(List.of());

        TeacherAttendanceSummaryDTO dto = service.getTeacherSummary("T1", 8, 2026);

        assertThat(dto.getRecords()).isNotEmpty().allSatisfy(record ->
                assertThat(record.getTeacherId()).isEqualTo("T1"));
        assertThat(dto.getAttendancePercentage()).isZero();
        verify(teacherAttendanceRepository, never())
                .findBySchoolIdAndDateBetweenOrderByDateAsc(any(), any(), any());
    }

    @Test
    void adminSessionSummary_containsTwelveAcademicMonths_inConfiguredOrder() {
        useClock(Instant.parse("2027-04-02T04:00:00Z"));
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        School configuredSchool = school("Asia/Kolkata");
        configuredSchool.setAcademicYearStartMonth(4);
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(configuredSchool));
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolHolidayRepository.findOverlapping(eq(SCHOOL_ID), any(), any())).thenReturn(List.of());
        when(teacherAttendanceRepository.findByTeacherIdAndSchoolIdAndDateBetweenOrderByDateAsc(
                eq("T1"), eq(SCHOOL_ID), any(), any())).thenReturn(List.of());

        TeacherAttendanceSessionSummaryDTO dto = service.getTeacherSessionSummary("T1", "2026-2027");

        assertThat(dto.getTeacherName()).isEqualTo("Alex");
        assertThat(dto.getMonths()).hasSize(12);
        assertThat(dto.getMonths().getFirst().getMonth()).isEqualTo(4);
        assertThat(dto.getMonths().getFirst().getYear()).isEqualTo(2026);
        assertThat(dto.getMonths().getLast().getMonth()).isEqualTo(3);
        assertThat(dto.getMonths().getLast().getYear()).isEqualTo(2027);
        assertThat(dto.getTotalWorkingDays()).isEqualTo(dto.getAbsentDays());
    }

    // ─── workDurationMinutes ─────────────────────────────────────────────────

    @Test
    void response_computesWorkDuration_whenBothTimestampsPresent() {
        TeacherAttendance ta = row("T1", LocalDate.of(2026, 8, 17), TeacherAttendanceService.STATUS_ON_TIME);
        ta.setCheckInTime(LocalDateTime.of(2026, 8, 17, 9, 0));
        ta.setCheckOutTime(LocalDateTime.of(2026, 8, 17, 16, 30));

        TeacherAttendanceResponse resp = TeacherAttendanceResponse.from(ta, "Alex");

        assertThat(resp.getWorkDurationMinutes()).isEqualTo(7 * 60 + 30);
    }

    @Test
    void response_workDuration_isNull_whenCheckOutMissing() {
        TeacherAttendance ta = row("T1", LocalDate.of(2026, 8, 17), TeacherAttendanceService.STATUS_ON_TIME);
        ta.setCheckInTime(LocalDateTime.of(2026, 8, 17, 9, 0));

        TeacherAttendanceResponse resp = TeacherAttendanceResponse.from(ta, "Alex");

        assertThat(resp.getWorkDurationMinutes()).isNull();
    }

    // ─── Admin override remains unrestricted and audited ────────────────────

    @Test
    void adminMark_allowedOnAHolidayOrWeekend_unrestrictedUnlikeSelfCheckIn() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate sunday = LocalDate.of(2026, 8, 16);

        AdminMarkTeacherAttendanceRequest req = new AdminMarkTeacherAttendanceRequest();
        req.setTeacherId("T1");
        req.setDate(sunday);
        req.setStatus("ON_TIME");
        req.setCheckInTime("09:00");

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findByTeacherIdAndDateAndSchoolId("T1", sunday, SCHOOL_ID))
                .thenReturn(Optional.empty());
        when(teacherAttendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TeacherAttendanceResponse resp = service.adminMarkAttendance(req, request);

        assertThat(resp.getStatus()).isEqualTo("ON_TIME");
        verify(auditService).log(eq("admin1"), eq("ADMIN"), eq("ADMIN_MARK_TEACHER_ATTENDANCE"),
                eq("TeacherAttendance"), any(), any(), any(), any());
    }

    @Test
    void adminMark_overwritingAnExistingStatus_capturesTheOldValueInTheAudit() {
        Instant instant = Instant.parse("2026-08-20T04:00:00Z");
        useClock(instant);
        LocalDate date = LocalDate.of(2026, 8, 17);

        TeacherAttendance existing = row("T1", date, TeacherAttendanceService.STATUS_ABSENT);
        existing.setId(99L);

        AdminMarkTeacherAttendanceRequest req = new AdminMarkTeacherAttendanceRequest();
        req.setTeacherId("T1");
        req.setDate(date);
        req.setStatus("ON_LEAVE");

        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        when(securityUtil.getUsername()).thenReturn("admin1");
        when(securityUtil.getRole()).thenReturn("ADMIN");
        when(teacherRepository.findByTeacherIdAndSchoolId("T1", SCHOOL_ID))
                .thenReturn(Optional.of(teacher("T1", "Alex", null)));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.of(school("Asia/Kolkata")));
        when(teacherAttendanceRepository.findByTeacherIdAndDateAndSchoolId("T1", date, SCHOOL_ID))
                .thenReturn(Optional.of(existing));
        when(teacherAttendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adminMarkAttendance(req, request);

        verify(auditService).log(eq("admin1"), eq("ADMIN"), eq("ADMIN_MARK_TEACHER_ATTENDANCE"),
                eq("TeacherAttendance"), eq("99"), eq("status=ABSENT"), eq("status=ON_LEAVE"), any());
    }
}
