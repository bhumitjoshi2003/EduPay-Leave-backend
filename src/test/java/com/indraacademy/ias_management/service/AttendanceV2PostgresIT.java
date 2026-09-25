package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SheetStudent;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SheetView;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.StudentStatus;
import com.indraacademy.ias_management.dto.AttendanceSheetDtos.SubmitRequest;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ConsecutiveAbsenceDTO;
import com.indraacademy.ias_management.entity.AttendanceStatus;
import com.indraacademy.ias_management.repository.AttendanceSessionRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Real-PostgreSQL coverage for Student Attendance V2: the V80 constraints, the insert-if-absent
 * + row-lock submission path, enrollment rosters, exact-section isolation, school-local date
 * rules, approved-leave handling, summaries and the absence-charge settlement. Synthetic fixtures
 * use negative ids; every test rolls back (@DataJpaTest default).
 *
 * <p>The clock is fixed at 2026-09-24T20:00Z = Friday 2026-09-25 01:30 in Asia/Kolkata, so the
 * school's "today" is a day ahead of UTC's.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceService.class, AbsenceChargeService.class, AcademicSessionService.class,
        TimetableSessionAccessService.class, AttendanceV2PostgresIT.FixedClock.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AttendanceV2PostgresIT {

    static final long SCHOOL = -97001L, OTHER_SCHOOL = -97002L, SESSION = -97003L, OTHER_SESSION = -97004L;
    static final long CLASS_SECTIONED = -97010L, CLASS_PLAIN = -97011L, OTHER_CLASS = -97012L;
    static final long SECTION_A = -97020L, SECTION_B = -97021L;
    static final String A1 = "V2-A1", A2 = "V2-A2", B1 = "V2-B1", P1 = "V2-P1", P2 = "V2-P2";
    static final LocalDate MON = LocalDate.of(2026, 9, 21), TUE = MON.plusDays(1), WED = MON.plusDays(2),
            THU = MON.plusDays(3), FRI = MON.plusDays(4), SAT = MON.plusDays(5);

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-24T20:00:00Z"), ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired AttendanceService service;
    @Autowired AbsenceChargeService charges;
    @Autowired AttendanceSessionRepository submissions;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean TeacherClassScopeService classScope;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,working_days) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'V2 IT','TRIAL','v2-it',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY')," +
                "(?,true,CURRENT_TIMESTAMP,'V2 Other','TRIAL','v2-other',4,8,'Asia/Kolkata','MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY')",
                SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)," +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)",
                SESSION, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES " +
                "(?,?,'8',true,false),(?,?,'9',true,false),(?,?,'8',true,false)",
                CLASS_SECTIONED, SCHOOL, CLASS_PLAIN, SCHOOL, OTHER_CLASS, OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true),(?,?,?,'B',true)",
                SECTION_A, SCHOOL, CLASS_SECTIONED, SECTION_B, SCHOOL, CLASS_SECTIONED);
        student(A1, "Aarav", CLASS_SECTIONED, "8", SECTION_A, "A");
        student(A2, "Bina", CLASS_SECTIONED, "8", SECTION_A, "A");
        student(B1, "Chetan", CLASS_SECTIONED, "8", SECTION_B, "B");
        student(P1, "Divya", CLASS_PLAIN, "9", null, null);
        student(P2, "Esha", CLASS_PLAIN, "9", null, null);

        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getRole()).thenReturn("ADMIN");
        when(security.getUsername()).thenReturn("admin-v2");
    }

    // ─── Storage & constraints ───────────────────────────────────────────

    @Test
    void submitStoresOneExplicitRowPerRosteredStudent() {
        SheetView view = service.submit(request(CLASS_SECTIONED, SECTION_A, THU, Map.of(A1, "PRESENT", A2, "ABSENT")), "ip");

        assertThat(view.submitted()).isTrue();
        assertThat(view.students()).extracting(SheetStudent::studentId).containsExactly(A1, A2);
        assertThat(rowsFor(SECTION_A, THU)).containsExactlyInAnyOrderEntriesOf(Map.of(A1, "PRESENT", A2, "ABSENT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_attendance WHERE student_id IN ('X', ?)", Long.class, B1)).isZero();
        assertThat(jdbc.queryForObject("SELECT marked_by_user_id FROM attendance_session WHERE school_id=? AND section_id=?",
                String.class, SCHOOL, SECTION_A)).isEqualTo("admin-v2");
    }

    // Each constraint test ends on its single violation: a failed statement aborts the
    // surrounding Postgres transaction.

    @Test
    void uniqueIndexRejectsDuplicateSectionlessSubmission() {
        AttendanceV2Fixtures.submission(jdbc, SCHOOL, CLASS_PLAIN, null, THU);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attendance_session (school_id,academic_session_id,class_id,section_id," +
                "attendance_date,marked_by_user_id,marked_at,updated_at) VALUES (?,?,?,NULL,?,'x',now(),now())",
                SCHOOL, SESSION, CLASS_PLAIN, THU)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueIndexRejectsDuplicateSectionSubmission() {
        AttendanceV2Fixtures.submission(jdbc, SCHOOL, CLASS_SECTIONED, SECTION_A, THU);
        AttendanceV2Fixtures.submission(jdbc, SCHOOL, CLASS_SECTIONED, SECTION_B, THU); // other section: allowed
        assertThatThrownBy(() -> jdbc.update("INSERT INTO attendance_session (school_id,academic_session_id,class_id,section_id," +
                "attendance_date,marked_by_user_id,marked_at,updated_at) VALUES (?,?,?,?,?,'x',now(),now())",
                SCHOOL, SESSION, CLASS_SECTIONED, SECTION_A, THU)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void studentRowIsUniquePerSubmission() {
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, THU, "PRESENT");
        assertThatThrownBy(() -> AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, THU, "ABSENT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusIsLimitedToPresentOrAbsent() {
        long submission = AttendanceV2Fixtures.submission(jdbc, SCHOOL, CLASS_PLAIN, null, THU);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO student_attendance (attendance_session_id,student_id,status,created_at,updated_at) " +
                "VALUES (?,?,'LATE',now(),now())", submission, P2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void insertIfAbsentIsIdempotentForBothScopes() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 24, 20, 0);
        assertThat(submissions.insertSectionSubmissionIfAbsent(SCHOOL, SESSION, CLASS_SECTIONED, SECTION_A, THU, "u", now)).isOne();
        assertThat(submissions.insertSectionSubmissionIfAbsent(SCHOOL, SESSION, CLASS_SECTIONED, SECTION_A, THU, "u", now)).isZero();
        assertThat(submissions.insertSectionSubmissionIfAbsent(SCHOOL, SESSION, CLASS_SECTIONED, SECTION_B, THU, "u", now)).isOne();
        assertThat(submissions.insertClassSubmissionIfAbsent(SCHOOL, SESSION, CLASS_PLAIN, THU, "u", now)).isOne();
        assertThat(submissions.insertClassSubmissionIfAbsent(SCHOOL, SESSION, CLASS_PLAIN, THU, "u", now)).isZero();
        assertThat(submissions.lockSubmission(SCHOOL, SESSION, CLASS_PLAIN, null, THU)).isPresent();
        assertThat(submissions.lockSubmission(SCHOOL, SESSION, CLASS_SECTIONED, null, THU)).isEmpty();
    }

    @Test
    void deletingASubmissionCascadesToItsRowsAndSettlements() {
        service.submit(request(CLASS_PLAIN, null, THU, Map.of(P1, "ABSENT", P2, "PRESENT")), "ip");
        charges.settleAfterPayment(P1, "2026-2027", SCHOOL, "ip");
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM absence_charge_settlement WHERE school_id=?", Long.class, SCHOOL)).isOne();

        jdbc.update("DELETE FROM attendance_session WHERE school_id=?", SCHOOL);
        assertThat(AttendanceV2Fixtures.countRows(jdbc, SCHOOL)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM absence_charge_settlement WHERE school_id=?", Long.class, SCHOOL)).isZero();
    }

    // ─── Resubmission & retries ──────────────────────────────────────────

    @Test
    void resubmissionUpdatesRowsInPlaceWithoutDuplicates() {
        service.submit(request(CLASS_SECTIONED, SECTION_A, THU, Map.of(A1, "PRESENT", A2, "ABSENT")), "ip");
        em.flush();
        Map<String, Long> idsBefore = rowIds(SECTION_A, THU);

        service.submit(request(CLASS_SECTIONED, SECTION_A, THU, Map.of(A1, "ABSENT", A2, "PRESENT")), "ip");
        em.flush();

        assertThat(rowsFor(SECTION_A, THU)).containsExactlyInAnyOrderEntriesOf(Map.of(A1, "ABSENT", A2, "PRESENT"));
        assertThat(rowIds(SECTION_A, THU)).isEqualTo(idsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_session WHERE school_id=?", Long.class, SCHOOL)).isOne();
    }

    @Test
    void repeatedIdenticalSubmitIsSafe() {
        SubmitRequest same = request(CLASS_PLAIN, null, THU, Map.of(P1, "PRESENT", P2, "ABSENT"));
        service.submit(same, "ip");
        service.submit(same, "ip");
        em.flush();
        assertThat(AttendanceV2Fixtures.countRows(jdbc, SCHOOL)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_session WHERE school_id=?", Long.class, SCHOOL)).isOne();
    }

    // ─── Roster validation ───────────────────────────────────────────────

    @Test
    void rosterComesFromEnrollmentAndRejectsMissingUnknownAndDuplicateStudents() {
        assertThatThrownBy(() -> service.submit(request(CLASS_SECTIONED, SECTION_A, THU, Map.of(A1, "PRESENT")), "ip"))
                .hasMessageContaining("missing for 1 student");
        assertThatThrownBy(() -> service.submit(request(CLASS_SECTIONED, SECTION_A, THU,
                Map.of(A1, "PRESENT", A2, "PRESENT", B1, "PRESENT")), "ip"))
                .hasMessageContaining("Not enrolled").hasMessageContaining(B1);
        assertThatThrownBy(() -> service.submit(new SubmitRequest(CLASS_SECTIONED, SECTION_A, THU, List.of(
                new StudentStatus(A1, AttendanceStatus.PRESENT), new StudentStatus(A1, AttendanceStatus.ABSENT),
                new StudentStatus(A2, AttendanceStatus.PRESENT))), "ip"))
                .hasMessageContaining("more than once");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_session WHERE school_id=?", Long.class, SCHOOL)).isZero();
    }

    @Test
    void studentWhoJoinedLaterIsNotOnEarlierRosters() {
        jdbc.update("UPDATE student_enrollment SET effective_from=? WHERE school_id=? AND student_id=?", THU, SCHOOL, A2);
        SheetView wed = service.getSheet(WED, CLASS_SECTIONED, SECTION_A);
        assertThat(wed.students()).extracting(SheetStudent::studentId).containsExactly(A1);
        service.submit(request(CLASS_SECTIONED, SECTION_A, WED, Map.of(A1, "PRESENT")), "ip");
    }

    // ─── Scopes & sections ───────────────────────────────────────────────

    @Test
    void sectionsAreIsolatedAndClassSummaryFiltersExactSection() {
        service.submit(request(CLASS_SECTIONED, SECTION_A, THU, Map.of(A1, "ABSENT", A2, "PRESENT")), "ip");
        service.submit(request(CLASS_SECTIONED, SECTION_B, THU, Map.of(B1, "PRESENT")), "ip");
        em.flush();

        assertThat(service.getSheet(THU, CLASS_SECTIONED, SECTION_B).students())
                .extracting(SheetStudent::studentId).containsExactly(B1);
        List<ClassAttendanceSummaryDTO> sectionA = service.getClassSummary("8", "month", 9, 2026, null, SECTION_A);
        assertThat(sectionA).extracting(ClassAttendanceSummaryDTO::getStudentId).containsExactlyInAnyOrder(A1, A2);
        List<ClassAttendanceSummaryDTO> wholeClass = service.getClassSummary("8", "month", 9, 2026, null, null);
        assertThat(wholeClass).extracting(ClassAttendanceSummaryDTO::getStudentId).containsExactlyInAnyOrder(A1, A2, B1);
    }

    @Test
    void adminMustPickSectionForSectionedClassAndNoneForPlainClass() {
        assertThatThrownBy(() -> service.getSheet(THU, CLASS_SECTIONED, null)).hasMessageContaining("Choose a section");
        assertThatThrownBy(() -> service.getSheet(THU, CLASS_PLAIN, SECTION_A)).hasMessageContaining("no sections");
    }

    @Test
    void teacherIsLimitedToOwnClassTeacherSection() {
        when(security.getRole()).thenReturn("TEACHER");
        when(security.getUsername()).thenReturn("teacher-v2");
        when(classScope.resolveOwnScope("teacher-v2", SCHOOL))
                .thenReturn(new TeacherClassScopeService.TeacherScope("8", SECTION_A, false));

        assertThat(service.getSheet(THU, null, null).sectionId()).isEqualTo(SECTION_A);
        assertThatThrownBy(() -> service.getSheet(THU, CLASS_SECTIONED, SECTION_B)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.getSheet(THU, CLASS_PLAIN, null)).isInstanceOf(AccessDeniedException.class);
        service.submit(request(null, null, THU, Map.of(A1, "PRESENT", A2, "PRESENT")), "ip");
    }

    @Test
    void subAdminCannotMark() {
        when(security.getRole()).thenReturn("SUB_ADMIN");
        assertThatThrownBy(() -> service.getSheet(THU, CLASS_PLAIN, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anotherSchoolsClassIsNotFoundAndItsDataNeverLeaks() {
        assertThatThrownBy(() -> service.getSheet(THU, OTHER_CLASS, null)).hasMessageContaining("Class not found");
        AttendanceV2Fixtures.mark(jdbc, OTHER_SCHOOL, P1, OTHER_CLASS, null, THU, "ABSENT");
        assertThat(service.getStudentSummary(P1, "month", 9, 2026, null).getTotalWorkingDays()).isZero();
        assertThat(charges.countChargeable(SCHOOL, P1, "2026-2027")).isZero();
    }

    // ─── Date rules (school-local) ───────────────────────────────────────

    @Test
    void schoolLocalTodayIsMarkableEvenThoughUtcIsStillYesterday() {
        SheetView today = service.getSheet(null, CLASS_PLAIN, null);
        assertThat(today.date()).isEqualTo(FRI);
        assertThat(today.markable()).isTrue();
        service.submit(request(CLASS_PLAIN, null, FRI, Map.of(P1, "PRESENT", P2, "PRESENT")), "ip");
    }

    @Test
    void futureWeekendHolidayAndOutOfSessionDatesAreBlocked() {
        jdbc.update("INSERT INTO school_holidays (school_id,name,start_date,end_date,affects_all) VALUES (?,'Festival',?,?,true)",
                SCHOOL, TUE, TUE);
        assertThat(service.getSheet(SAT.plusDays(2), CLASS_PLAIN, null).blockedReason()).contains("future");
        assertThat(service.getSheet(SAT, CLASS_PLAIN, null).blockedReason()).contains("future");
        assertThat(service.getSheet(MON.minusDays(2), CLASS_PLAIN, null).blockedReason()).contains("not a working day");
        assertThat(service.getSheet(TUE, CLASS_PLAIN, null).blockedReason()).contains("holiday");
        assertThat(service.getSheet(LocalDate.of(2026, 3, 30), CLASS_PLAIN, null).blockedReason()).contains("outside");
        assertThatThrownBy(() -> service.submit(request(CLASS_PLAIN, null, TUE, Map.of(P1, "PRESENT", P2, "PRESENT")), "ip"))
                .hasMessageContaining("holiday");
    }

    // ─── Leave, percentages, consecutive absences, fee ───────────────────

    @Test
    void onlyApprovedLeaveIsFlaggedAndItStillCountsAsAbsent() {
        leave(P1, WED, "APPROVED");
        leave(P2, WED, "PENDING");
        SheetView sheet = service.getSheet(WED, CLASS_PLAIN, null);
        Map<String, Boolean> flags = sheet.students().stream()
                .collect(Collectors.toMap(SheetStudent::studentId, SheetStudent::approvedLeave));
        assertThat(flags).containsEntry(P1, true).containsEntry(P2, false);

        for (LocalDate d = MON; !d.isAfter(THU); d = d.plusDays(1)) {
            AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, d, d.equals(WED) ? "ABSENT" : "PRESENT");
        }
        AttendanceSummaryDTO summary = service.getStudentSummary(P1, "month", 9, 2026, null);
        assertThat(summary.getTotalWorkingDays()).isEqualTo(4);
        assertThat(summary.getDaysPresent()).isEqualTo(3);
        assertThat(summary.getDaysAbsent()).isEqualTo(1);
        assertThat(summary.getApprovedLeaveDays()).isEqualTo(1);
        assertThat(summary.getAttendancePercentage()).isEqualTo(75.0);
        // Approved leave waives the absence fee.
        assertThat(charges.countChargeable(SCHOOL, P1, "2026-2027")).isZero();
    }

    @Test
    void consecutiveAbsenceStreakUsesExplicitRowsOnly() {
        for (LocalDate d : List.of(MON, TUE, WED, THU)) {
            AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, d, d.equals(MON) ? "PRESENT" : "ABSENT");
            if (!d.equals(TUE)) AttendanceV2Fixtures.mark(jdbc, SCHOOL, P2, CLASS_PLAIN, null, d, "ABSENT");
        }
        leave(P1, THU, "APPROVED");
        List<ConsecutiveAbsenceDTO> result = service.getConsecutiveAbsentees("9", 3, 10, null, null);
        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.getStudentId()).isEqualTo(P1);
            assertThat(r.getConsecutiveAbsentDays()).isEqualTo(3);
            assertThat(r.getApprovedLeaveDates()).containsExactly(THU.toString());
        });
    }

    @Test
    void absenceChargeCountsUnexplainedAbsencesAndSettlesIdempotently() {
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, MON, "ABSENT");
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, TUE, "ABSENT");
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, WED, "PRESENT");
        leave(P1, TUE, "REJECTED");
        assertThat(charges.countChargeable(SCHOOL, P1, "2026-2027")).isEqualTo(2);

        assertThat(charges.settleAfterPayment(P1, "2026-2027", SCHOOL, "ip")).isEqualTo(2);
        assertThat(charges.settleAfterPayment(P1, "2026-2027", SCHOOL, "ip")).isZero();
        assertThat(charges.countChargeable(SCHOOL, P1, "2026-2027")).isZero();

        AttendanceV2Fixtures.mark(jdbc, SCHOOL, P1, CLASS_PLAIN, null, THU, "ABSENT");
        assertThat(charges.countChargeable(SCHOOL, P1, "2026-2027")).isOne();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void student(String id, String name, long classId, String className, Long sectionId, String sectionName) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,?,?,?,DATE '2026-04-01')", id, SCHOOL, name, classId, className, sectionId, sectionName);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id," +
                "section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',DATE '2026-04-01')",
                SCHOOL, id, SESSION, classId, className, sectionId, sectionName);
    }

    private void leave(String studentId, LocalDate date, String status) {
        jdbc.update("INSERT INTO leaves (school_id,student_id,student_name,leave_date,class_name,reason,applied_date,status) " +
                "VALUES (?,?,?,?,'9','Unwell',CURRENT_TIMESTAMP,?)", SCHOOL, studentId, studentId, date.toString(), status);
    }

    private static SubmitRequest request(Long classId, Long sectionId, LocalDate date, Map<String, String> statuses) {
        return new SubmitRequest(classId, sectionId, date, statuses.entrySet().stream()
                .map(e -> new StudentStatus(e.getKey(), AttendanceStatus.valueOf(e.getValue()))).toList());
    }

    private Map<String, String> rowsFor(Long sectionId, LocalDate date) {
        em.flush();
        return jdbc.queryForList("SELECT a.student_id, a.status FROM student_attendance a JOIN attendance_session s " +
                        "ON s.id=a.attendance_session_id WHERE s.school_id=? AND s.section_id IS NOT DISTINCT FROM CAST(? AS BIGINT) " +
                        "AND s.attendance_date=?", SCHOOL, sectionId, date).stream()
                .collect(Collectors.toMap(r -> (String) r.get("student_id"), r -> (String) r.get("status")));
    }

    private Map<String, Long> rowIds(Long sectionId, LocalDate date) {
        return jdbc.queryForList("SELECT a.student_id, a.id FROM student_attendance a JOIN attendance_session s " +
                        "ON s.id=a.attendance_session_id WHERE s.school_id=? AND s.section_id IS NOT DISTINCT FROM CAST(? AS BIGINT) " +
                        "AND s.attendance_date=?", SCHOOL, sectionId, date).stream()
                .collect(Collectors.toMap(r -> (String) r.get("student_id"), r -> ((Number) r.get("id")).longValue()));
    }
}
