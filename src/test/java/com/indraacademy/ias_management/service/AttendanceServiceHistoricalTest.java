package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.DailyAttendanceDTO;
import com.indraacademy.ias_management.entity.AcademicSession;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.SchoolClassRepository;
import com.indraacademy.ias_management.repository.SchoolRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase: Attendance session authority and historical correctness.
 *
 * Covers two independent fixes to AttendanceService:
 *  1. Explicit session labels (getStudentSummary/getClassSummary "year" type,
 *     getTotalUnappliedLeaveCount, updateChargePaidAfterPayment) now resolve via
 *     AcademicSessionService.getSessionByLabel — the school's real, authoritative
 *     AcademicSession — instead of reconstructing start/end dates from
 *     School.academicYearStartMonth + assumed 12-month arithmetic.
 *  2. The historical-class bug: getStudentSummary/getDailyAttendance/getAttendanceCounts used
 *     to compute "working days" by searching for the student's CURRENT className against
 *     historical dates. Attendance rows snapshot className at mark time and are never
 *     rewritten by promotion, so this silently broke a promoted student's old attendance
 *     figures. All three now resolve the class from the student's OWN historical rows.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceHistoricalTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SchoolRepository schoolRepository;
    @Mock private SchoolClassRepository schoolClassRepository;
    @Mock private AuditService auditService;
    @Mock private SecurityUtil securityUtil;
    @Mock private AcademicSessionService academicSessionService;
    @Mock private StudentTemporalMembershipResolver temporalMembershipResolver;
    @Mock private com.indraacademy.ias_management.repository.StudentEnrollmentRepository studentEnrollmentRepository;
    @Mock private com.indraacademy.ias_management.repository.AcademicSessionRepository academicSessionRepository;

    private AttendanceService service;

    private static final Long SCHOOL_ID = 5L;
    private static final String STUDENT_ID = "S1";

    @BeforeEach
    void setUp() {
        service = new AttendanceService();
        ReflectionTestUtils.setField(service, "attendanceRepository", attendanceRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "schoolRepository", schoolRepository);
        ReflectionTestUtils.setField(service, "schoolClassRepository", schoolClassRepository);
        ReflectionTestUtils.setField(service, "auditService", auditService);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(service, "academicSessionService", academicSessionService);
        ReflectionTestUtils.setField(service, "temporalMembershipResolver", temporalMembershipResolver);
        ReflectionTestUtils.setField(service, "studentEnrollmentRepository", studentEnrollmentRepository);
        ReflectionTestUtils.setField(service, "academicSessionRepository", academicSessionRepository);
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
        // No StudentEnrollment/AcademicSession fixtures exist for any of these tests — they
        // represent a school/period with no E6B enrollment coverage at all, so every call must
        // fall back to the pre-E6C legacy attendance-row bridge exactly as before. Stubbed
        // lenient() since not every test exercises a code path that consults the resolver.
        lenient().when(temporalMembershipResolver.resolveEffectiveRealizedEnrollment(anyLong(), anyString(), any(LocalDate.class)))
                .thenThrow(new java.util.NoSuchElementException("no session configured for this test"));
        lenient().when(temporalMembershipResolver.resolveRealizedEnrollmentRange(anyLong(), anyString(), anyLong(), any(), any()))
                .thenThrow(new java.util.NoSuchElementException("no session configured for this test"));
    }

    private Student promotedStudent() {
        // The student's CURRENT state, post-promotion: className is now "10". No join/leave
        // dates set, so effectiveStart/effectiveEnd never clip the requested period — isolates
        // the historical-class behavior under test from that separate concern.
        Student s = new Student();
        s.setStudentId(STUDENT_ID);
        s.setName("Promoted Pat");
        s.setSchoolId(SCHOOL_ID);
        s.setClassName("10");
        s.setJoiningDate(LocalDate.of(2020, 1, 1)); // well before any period these tests query
        return s;
    }

    private Attendance row(String studentId, String className, LocalDate date) {
        Attendance a = new Attendance();
        a.setStudentId(studentId);
        a.setClassName(className);
        a.setDate(date);
        a.setSchoolId(SCHOOL_ID);
        return a;
    }

    private AcademicSession session(String label, LocalDate start, LocalDate end) {
        AcademicSession s = new AcademicSession();
        s.setId(1L);
        s.setSchoolId(SCHOOL_ID);
        s.setLabel(label);
        s.setStartDate(start);
        s.setEndDate(end);
        s.setCurrent(false);
        return s;
    }

    // ─── Explicit session label resolution ──────────────────────────────────────

    @Test
    void yearSummaryResolvesTheExplicitSessionLabelViaAcademicSessionService() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        AcademicSession currentSession = session("2026-2027", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2026-2027")).thenReturn(Optional.of(currentSession));
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(eq(STUDENT_ID), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of());
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(any(), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of());

        service.getStudentSummary(STUDENT_ID, "year", null, null, "2026-2027");

        verify(academicSessionService).getSessionByLabel(SCHOOL_ID, "2026-2027");
    }

    @Test
    void currentSessionYearSummaryUsesTheSessionsRealStartAndEndDatesNotAssumedTwelveMonthArithmetic() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        // A deliberately non-standard session (not exactly April 1 - March 31) — proves the
        // service uses the session's OWN dates, not LocalDate.of(startYear, startMonth,1)
        // .plusYears(1).minusDays(1) arithmetic derived from academicYearStartMonth.
        LocalDate start = LocalDate.of(2026, 6, 15);
        LocalDate end = LocalDate.of(2027, 5, 20);
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2026-2027"))
                .thenReturn(Optional.of(session("2026-2027", start, end)));
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(STUDENT_ID, SCHOOL_ID, start, end))
                .thenReturn(List.of());
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(any(), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of());

        service.getStudentSummary(STUDENT_ID, "year", null, null, "2026-2027");

        // The exact non-round-number dates were passed straight through — no arithmetic reconstruction.
        verify(attendanceRepository).findByStudentIdAndSchoolIdAndDateBetween(STUDENT_ID, SCHOOL_ID, start, end);
    }

    @Test
    void historicalSessionLabelResolvesToItsOwnPastAcademicSession() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        LocalDate pastStart = LocalDate.of(2024, 4, 1);
        LocalDate pastEnd = LocalDate.of(2025, 3, 31);
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2024-2025"))
                .thenReturn(Optional.of(session("2024-2025", pastStart, pastEnd)));
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(STUDENT_ID, SCHOOL_ID, pastStart, pastEnd))
                .thenReturn(List.of());
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(any(), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of());

        AttendanceSummaryDTO dto = service.getStudentSummary(STUDENT_ID, "year", null, null, "2024-2025");

        assertThat(dto).isNotNull();
        verify(academicSessionService).getSessionByLabel(SCHOOL_ID, "2024-2025");
    }

    @Test
    void noMatchingAcademicSessionThrowsRatherThanFallingBackToGuessedDates() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2099-2100")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStudentSummary(STUDENT_ID, "year", null, null, "2099-2100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2099-2100");
    }

    @Test
    void tenantIsolation_aSessionLabelIsAlwaysResolvedAgainstTheCallersOwnSchool() {
        // The label "2026-2027" might genuinely exist for a DIFFERENT school — the service must
        // ask AcademicSessionService with THIS caller's schoolId, never a bare label lookup that
        // could cross tenants. A cross-school "session exists but not for me" case is
        // indistinguishable from "doesn't exist" from AttendanceService's point of view, and
        // both correctly resolve to empty from a properly schoolId-scoped service call.
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2026-2027")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStudentSummary(STUDENT_ID, "year", null, null, "2026-2027"))
                .isInstanceOf(IllegalArgumentException.class);

        verify(academicSessionService).getSessionByLabel(SCHOOL_ID, "2026-2027");
    }

    @Test
    void updateChargePaidSkipsRatherThanCrashesWhenNoMatchingSession() {
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2099-2100")).thenReturn(Optional.empty());

        service.updateChargePaidAfterPayment(STUDENT_ID, "2099-2100", null);

        verify(attendanceRepository, never()).updateChargePaidForSession(any(), any(), any(), any());
    }

    @Test
    void unappliedLeaveCountReturnsZeroRatherThanCrashingWhenNoMatchingSession() {
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2099-2100")).thenReturn(Optional.empty());

        long count = service.getTotalUnappliedLeaveCount(STUDENT_ID, "2099-2100");

        assertThat(count).isZero();
        verify(attendanceRepository, never()).countUnappliedLeavesForAcademicYear(any(), any(), any(), any());
    }

    // ─── Historical class resolution after promotion ────────────────────────────

    @Test
    void monthSummaryAfterPromotionStillReportsClass9AndCorrectWorkingDays() {
        Student student = promotedStudent(); // currently className = "10"
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));

        LocalDate d1 = LocalDate.of(2025, 8, 4);
        LocalDate d2 = LocalDate.of(2025, 8, 5);
        LocalDate d3 = LocalDate.of(2025, 8, 6);
        // The student's own attendance rows for August 2025 — back when they were in class 9,
        // before the promotion that later changed student.getClassName() to "10".
        List<Attendance> ownRows = List.of(
                row(STUDENT_ID, "9", d1),
                row(STUDENT_ID, "9", d3)); // absent on d1 and d3, present d2 (no row = present)
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                eq(STUDENT_ID), eq(SCHOOL_ID), eq(LocalDate.of(2025, 8, 1)), eq(LocalDate.of(2025, 8, 31))))
                .thenReturn(ownRows);
        // Class 9's marked days that month (the "X" sentinel plus real rows) — three school days.
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(
                eq("9"), eq(SCHOOL_ID), eq(LocalDate.of(2025, 8, 1)), eq(LocalDate.of(2025, 8, 31))))
                .thenReturn(List.of(row("X", "9", d1), row("X", "9", d2), row("X", "9", d3)));

        AttendanceSummaryDTO dto = service.getStudentSummary(STUDENT_ID, "month", 8, 2025, null);

        assertThat(dto.getClassName()).isEqualTo("9"); // NOT "10" — the historical class, not current
        assertThat(dto.getTotalWorkingDays()).isEqualTo(3);
        assertThat(dto.getDaysAbsent()).isEqualTo(2.0);
        assertThat(dto.getDaysPresent()).isEqualTo(1.0);
        // Class 10 (the student's CURRENT class) must never be queried for a period that
        // predates the promotion — there's nothing there to find, and querying it would be
        // exactly the bug this fixes.
        verify(attendanceRepository, never())
                .findByClassNameAndSchoolIdAndDateBetween(eq("10"), any(), any(), any());
    }

    @Test
    void yearSummaryAfterPromotionReportsTheHistoricalClassForThatSession() {
        Student student = promotedStudent(); // currently className = "10"
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        LocalDate start = LocalDate.of(2024, 4, 1);
        LocalDate end = LocalDate.of(2025, 3, 31);
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2024-2025"))
                .thenReturn(Optional.of(session("2024-2025", start, end)));

        LocalDate d1 = LocalDate.of(2024, 8, 4);
        // One wildcard stub covers both the top-level year-range call and the 12 per-month
        // buildMonthBreakdown calls the loop makes — all consistently see this student's rows
        // as class "9", which is exactly what's under test (the resolved className).
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(eq(STUDENT_ID), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row(STUDENT_ID, "9", d1)));
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(eq("9"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("X", "9", d1)));

        AttendanceSummaryDTO dto = service.getStudentSummary(STUDENT_ID, "year", null, null, "2024-2025");

        assertThat(dto.getClassName()).isEqualTo("9");
    }

    @Test
    void dailyAttendanceAfterPromotionStillResolvesSchoolDaysAgainstTheHistoricalClass() {
        Student student = promotedStudent(); // currently className = "10"
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));

        LocalDate d1 = LocalDate.of(2025, 8, 4);
        LocalDate d2 = LocalDate.of(2025, 8, 5);
        List<Attendance> ownRows = List.of(row(STUDENT_ID, "9", d1));
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                eq(STUDENT_ID), eq(SCHOOL_ID), eq(LocalDate.of(2025, 8, 1)), eq(LocalDate.of(2025, 8, 31))))
                .thenReturn(ownRows);
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(
                eq("9"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("X", "9", d1), row("X", "9", d2)));
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.empty());

        DailyAttendanceDTO dto = service.getDailyAttendance(STUDENT_ID, 8, 2025);

        assertThat(dto.getSchoolDays()).containsExactly(d1.toString(), d2.toString());
        assertThat(dto.getAbsentDays()).containsExactly(d1.toString());
        verify(attendanceRepository, never())
                .findByClassNameAndSchoolIdAndDateBetween(eq("10"), any(), any(), any());
    }

    @Test
    void attendanceCountsAfterPromotionUseTheHistoricalClassForThatMonth() {
        Student student = promotedStudent(); // currently className = "10"
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));

        LocalDate d1 = LocalDate.of(2025, 8, 4);
        LocalDate d2 = LocalDate.of(2025, 8, 5);
        // The student's own row shows class "9" (their historical class); 20 distinct marked
        // days for class "9" that month is the working-day denominator.
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                eq(STUDENT_ID), eq(SCHOOL_ID), eq(LocalDate.of(2025, 8, 1)), eq(LocalDate.of(2025, 8, 31))))
                .thenReturn(List.of(row(STUDENT_ID, "9", d1)));
        List<Attendance> classNineMarkedDays = new java.util.ArrayList<>();
        classNineMarkedDays.add(row("X", "9", d1));
        classNineMarkedDays.add(row("X", "9", d2));
        for (int i = 0; i < 18; i++) {
            classNineMarkedDays.add(row("X", "9", d2.plusDays(i + 1)));
        }
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(
                eq("9"), eq(SCHOOL_ID), eq(LocalDate.of(2025, 8, 1)), eq(LocalDate.of(2025, 8, 31))))
                .thenReturn(classNineMarkedDays);

        Map<String, Long> counts = service.getAttendanceCounts(STUDENT_ID, 2025, 8);

        assertThat(counts.get("studentAbsent")).isEqualTo(1L);
        assertThat(counts.get("totalAbsent")).isEqualTo(20L); // "totalAbsent" key holds total working days
        verify(attendanceRepository, never())
                .findByClassNameAndSchoolIdAndDateBetween(eq("10"), any(), any(), any());
    }

    // ─── Boundary dates ──────────────────────────────────────────────────────────

    @Test
    void yearSummaryPassesTheSessionsExactStartAndEndDatesWithNoOffByOneAdjustment() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2027, 3, 31);
        when(academicSessionService.getSessionByLabel(SCHOOL_ID, "2026-2027"))
                .thenReturn(Optional.of(session("2026-2027", start, end)));
        // Rows dated exactly ON the boundaries — proves the service queries with these exact
        // dates as inclusive bounds, not start+1/end-1 or any other adjusted range.
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(STUDENT_ID, SCHOOL_ID, start, end))
                .thenReturn(List.of(row(STUDENT_ID, "10", start), row(STUDENT_ID, "10", end)));
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(eq("10"), eq(SCHOOL_ID), eq(start), eq(end)))
                .thenReturn(List.of(row("X", "10", start), row("X", "10", end)));

        AttendanceSummaryDTO dto = service.getStudentSummary(STUDENT_ID, "year", null, null, "2026-2027");

        assertThat(dto.getTotalWorkingDays()).isEqualTo(2);
        verify(attendanceRepository).findByStudentIdAndSchoolIdAndDateBetween(STUDENT_ID, SCHOOL_ID, start, end);
    }

    // ─── No mutation of historical attendance rows ─────────────────────────────

    @Test
    void readingSummariesNeverMutatesAttendanceRows() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(any(), any(), any(), any())).thenReturn(List.of());
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(any(), any(), any(), any())).thenReturn(List.of());
        when(schoolRepository.findById(SCHOOL_ID)).thenReturn(Optional.empty());

        service.getStudentSummary(STUDENT_ID, "month", 8, 2025, null);
        service.getDailyAttendance(STUDENT_ID, 8, 2025);
        service.getAttendanceCounts(STUDENT_ID, 2025, 8);

        verify(attendanceRepository, never()).save(any());
        verify(attendanceRepository, never()).saveAll(any());
        verify(attendanceRepository, never()).delete(any());
        verify(attendanceRepository, never()).deleteAll(any());
    }

    // ─── Genuine mid-period class change (documented, not silently guessed) ────

    @Test
    void aGenuineMidPeriodClassChangeCountsWorkingDaysAcrossBothClassesRatherThanDroppingOne() {
        Student student = promotedStudent();
        when(studentRepository.findByStudentIdAndSchoolId(STUDENT_ID, SCHOOL_ID)).thenReturn(Optional.of(student));

        // A real mid-month transfer: this one student's own rows show TWO different classes
        // within the same requested month — e.g. an admin-driven class change outside the
        // normal promotion workflow, not something student_enrollment exists yet to model.
        LocalDate d1 = LocalDate.of(2025, 8, 4);  // still in "9"
        LocalDate d2 = LocalDate.of(2025, 8, 20); // now in "10"
        when(attendanceRepository.findByStudentIdAndSchoolIdAndDateBetween(
                eq(STUDENT_ID), eq(SCHOOL_ID), eq(LocalDate.of(2025, 8, 1)), eq(LocalDate.of(2025, 8, 31))))
                .thenReturn(List.of(row(STUDENT_ID, "9", d1), row(STUDENT_ID, "10", d2)));
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(eq("9"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("X", "9", d1)));
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(eq("10"), eq(SCHOOL_ID), any(), any()))
                .thenReturn(List.of(row("X", "10", d2)));

        AttendanceSummaryDTO dto = service.getStudentSummary(STUDENT_ID, "month", 8, 2025, null);

        // Neither class's working day is dropped — the union of both is the correct total,
        // not a silent pick-one-and-discard-the-other guess.
        assertThat(dto.getTotalWorkingDays()).isEqualTo(2);
        // The most recently marked class ("10") is the one reported for display — documented,
        // not silent (AttendanceService logs a warning identifying exactly this case).
        assertThat(dto.getClassName()).isEqualTo("10");
    }
}
