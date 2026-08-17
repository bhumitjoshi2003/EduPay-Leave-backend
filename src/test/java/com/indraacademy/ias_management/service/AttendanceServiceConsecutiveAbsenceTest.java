package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.entity.Attendance;
import com.indraacademy.ias_management.entity.Student;
import com.indraacademy.ias_management.repository.AttendanceRepository;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Covers AttendanceService.getConsecutiveAbsentees — the "absent for the last N days" selection.
 *
 * <p>Every test here passes session = null on purpose, which skips the cumulative-figures merge
 * and isolates the streak logic itself. The behaviour under test is entirely about the attendance
 * table's two non-obvious conventions, both of which are silent-failure-shaped:
 * <ul>
 *   <li>a row means the student was ABSENT (there is no "present" row), and</li>
 *   <li>a {@code studentId = "X"} sentinel row is what makes an all-present day visible as a day
 *       the school was actually open.</li>
 * </ul>
 * Get either wrong and the workflow emails warnings to parents of students who attended.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceServiceConsecutiveAbsenceTest {

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SecurityUtil securityUtil;

    private AttendanceService service;

    private static final Long SCHOOL_ID = 2L;
    private static final String CLASS_NAME = "10";

    @BeforeEach
    void setUp() {
        service = new AttendanceService();
        ReflectionTestUtils.setField(service, "attendanceRepository", attendanceRepository);
        ReflectionTestUtils.setField(service, "studentRepository", studentRepository);
        ReflectionTestUtils.setField(service, "securityUtil", securityUtil);
        // lenient: the argument-validation test rejects its input before ever resolving a school.
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL_ID);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** An absence row (or, for studentId "X", the school-was-open sentinel). */
    private Attendance row(String studentId, LocalDate date) {
        Attendance a = new Attendance();
        a.setStudentId(studentId);
        a.setDate(date);
        a.setClassName(CLASS_NAME);
        a.setSchoolId(SCHOOL_ID);
        return a;
    }

    private Student student(String id, String name) {
        Student s = new Student();
        s.setStudentId(id);
        s.setName(name);
        s.setClassName(CLASS_NAME);
        return s;
    }

    private void givenClassRoster(Student... students) {
        when(studentRepository.findByClassNameAndSchoolId(CLASS_NAME, SCHOOL_ID)).thenReturn(List.of(students));
    }

    private void givenAttendanceRows(List<Attendance> rows) {
        when(attendanceRepository.findByClassNameAndSchoolIdAndDateBetween(
                org.mockito.ArgumentMatchers.eq(CLASS_NAME),
                org.mockito.ArgumentMatchers.eq(SCHOOL_ID),
                org.mockito.ArgumentMatchers.any(LocalDate.class),
                org.mockito.ArgumentMatchers.any(LocalDate.class))).thenReturn(rows);
    }

    private static final LocalDate D1 = LocalDate.now().minusDays(4);
    private static final LocalDate D2 = LocalDate.now().minusDays(3);
    private static final LocalDate D3 = LocalDate.now().minusDays(2);
    private static final LocalDate D4 = LocalDate.now().minusDays(1);

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void includesStudentAbsentOnEveryOneOfTheLastThreeMarkedDays() {
        givenClassRoster(student("S1", "Absent Amy"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D2), row("X", D3), row("X", D4),   // three days school was open
                row("S1", D2), row("S1", D3), row("S1", D4) // absent on all three
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStudentId()).isEqualTo("S1");
        assertThat(result.get(0).getConsecutiveAbsentDays()).isEqualTo(3);
        assertThat(result.get(0).getAbsentDates())
                .containsExactly(D2.toString(), D3.toString(), D4.toString());  // oldest first
    }

    @Test
    void excludesStudentWhoAttendedOnTheMostRecentDay_streakMustReachToday() {
        givenClassRoster(student("S1", "Back Today Bob"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D2), row("X", D3), row("X", D4),
                row("S1", D2), row("S1", D3)  // absent D2+D3 but PRESENT on the latest day D4
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        // Their streak is broken as of the most recent day — they came back, so no warning.
        assertThat(result).isEmpty();
    }

    @Test
    void weekendsAndHolidaysNeverBreakAStreak_becauseOnlyMarkedDaysAreCounted() {
        // Marked days deliberately span a gap: a Friday, then the following Monday and Tuesday.
        LocalDate friday = LocalDate.now().minusDays(11);
        LocalDate monday = LocalDate.now().minusDays(8);
        LocalDate tuesday = LocalDate.now().minusDays(7);

        givenClassRoster(student("S1", "Long Weekend Lee"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", friday), row("X", monday), row("X", tuesday),
                row("S1", friday), row("S1", monday), row("S1", tuesday)
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        // A naive calendar countback would see Sat/Sun in between and call this a broken streak
        // (or worse, count the weekend itself as absences). Marked days make that impossible.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getConsecutiveAbsentDays()).isEqualTo(3);
    }

    @Test
    void anAllPresentDayBreaksTheStreak_thatIsPreciselyWhatTheSentinelRowIsFor() {
        givenClassRoster(student("S1", "Interrupted Ira"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D1), row("X", D2), row("X", D3), row("X", D4),
                // Absent on the two most recent days, present on D2 (only the sentinel marks D2).
                row("S1", D1), row("S1", D3), row("S1", D4)
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        // Without the 'X' row for D2, D2 would be invisible and D1/D3/D4 would look contiguous —
        // producing a fictitious 3-day streak for a student who was in school on D2.
        assertThat(result).isEmpty();
    }

    @Test
    void reportsTheTrueStreakLength_notMerelyTheRequestedMinimum() {
        givenClassRoster(student("S1", "Long Gone Lou"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D1), row("X", D2), row("X", D3), row("X", D4),
                row("S1", D1), row("S1", D2), row("S1", D3), row("S1", D4)
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        // Asked for 3+, absent 4 — a teacher needs to see the real figure to triage.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getConsecutiveAbsentDays()).isEqualTo(4);
        assertThat(result.get(0).getAbsentDates()).hasSize(4);
    }

    @Test
    void theSentinelStudentIdXIsNeverReportedAsAStudent() {
        givenClassRoster(student("S1", "Real Student"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D2), row("X", D3), row("X", D4),
                row("S1", D2), row("S1", D3), row("S1", D4)
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        assertThat(result).extracting(ConsecutiveAbsenceDTO::getStudentId).doesNotContain("X");
    }

    @Test
    void returnsEmptyWhenFewerMarkedDaysExistThanTheRequestedStreak() {
        // No roster stub on purpose: the check short-circuits before the roster is even loaded,
        // and Mockito's strict stubbing is what proves that (an unused stub here fails the test).
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D3), row("X", D4),      // only two days marked all term
                row("S1", D3), row("S1", D4)     // absent on both
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        // Absent on every day on record, but the school has no third day to evidence a 3-day
        // streak — asserting one would claim an absence on a day never recorded.
        assertThat(result).isEmpty();
    }

    @Test
    void studentWithNoAbsenceRowsAtAllIsExcluded() {
        givenClassRoster(student("S1", "Perfect Pat"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D2), row("X", D3), row("X", D4)  // school open, nobody absent
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void ordersByLongestStreakFirst_soTheMostUrgentCasesLead() {
        givenClassRoster(student("S1", "Three Day Tam"), student("S2", "Four Day Fay"));
        givenAttendanceRows(new ArrayList<>(List.of(
                row("X", D1), row("X", D2), row("X", D3), row("X", D4),
                row("S1", D2), row("S1", D3), row("S1", D4),                 // 3
                row("S2", D1), row("S2", D2), row("S2", D3), row("S2", D4)   // 4
        )));

        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees(CLASS_NAME, 3, null, null);

        assertThat(result).extracting(ConsecutiveAbsenceDTO::getStudentId).containsExactly("S2", "S1");
    }

    @Test
    void rejectsAStreakLengthBelowOne() {
        assertThatThrownBy(() -> service.getConsecutiveAbsentees(CLASS_NAME, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }
}
