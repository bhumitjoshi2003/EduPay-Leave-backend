package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ClassAttendanceSummaryDTO;
import com.indraacademy.ias_management.util.SecurityUtil;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * Phase E6C: real-PostgreSQL coverage for enrollment-authoritative historical attendance.
 * Synthetic fixtures use negative identifiers and every test transaction rolls back
 * (@DataJpaTest default), so no cleanup step is required.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceService.class, StudentTemporalMembershipResolver.class, AcademicSessionService.class,
        AttendanceServiceHistoricalPostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AttendanceServiceHistoricalPostgresIT {

    private static final long SCHOOL = -98501L;
    private static final long OTHER_SCHOOL = -98502L;
    private static final long SESSION_PRIOR = -98601L;
    private static final long SESSION_CURRENT = -98602L;
    private static final long SESSION_OTHER = -98603L;
    private static final long CLASS_9 = -98701L;
    private static final long CLASS_10 = -98702L;
    private static final long CLASS_OTHER = -98703L;
    private static final long SECTION_A = -98801L;
    private static final long SECTION_10A = -98802L;
    private static final long SECTION_10B = -98803L;
    private static final long SECTION_OTHER = -98804L;
    private static final String SESSION_PRIOR_LABEL = "2025-2026";
    private static final String SESSION_CURRENT_LABEL = "2026-2027";
    private static final String STUDENT = "E6C-PG-S1";
    private static final String LEGACY_STUDENT = "E6C-PG-LEGACY";
    private static final String OTHER_STUDENT = "E6C-PG-OTHER";

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceService attendanceService;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeEach
    void fixtures() {
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");

        insertSchool(SCHOOL, "e6c-attendance-it");
        insertSchool(OTHER_SCHOOL, "e6c-attendance-it-other");
        insertSession(SESSION_PRIOR, SCHOOL, SESSION_PRIOR_LABEL, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertSession(SESSION_CURRENT, SCHOOL, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertSession(SESSION_OTHER, OTHER_SCHOOL, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertClass(CLASS_9, SCHOOL, "9");
        insertClass(CLASS_10, SCHOOL, "10");
        insertClass(CLASS_OTHER, OTHER_SCHOOL, "9");
        insertSection(SECTION_A, SCHOOL, CLASS_9, "A");
        insertSection(SECTION_10A, SCHOOL, CLASS_10, "A");
        insertSection(SECTION_10B, SCHOOL, CLASS_10, "B");
        insertSection(SECTION_OTHER, OTHER_SCHOOL, CLASS_OTHER, "A");
        // Live Student row is deliberately stale/current — className "10" post-promotion, section
        // 10B — every test below proves the historical figures come from enrollment/attendance
        // rows for the requested period, never this live snapshot.
        insertStudent(STUDENT, SCHOOL, "10", CLASS_10, SECTION_10B);
        insertStudent(LEGACY_STUDENT, SCHOOL, "9", CLASS_9, SECTION_A);
        insertStudent(OTHER_STUDENT, OTHER_SCHOOL, "9", CLASS_OTHER, SECTION_OTHER);
    }

    @Test
    void promotedStudentReadingPriorSessionShowsHistoricalClassWithInclusiveClosedBoundaries() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 4, 1));

        insertAttendance(STUDENT, "9", CLASS_9, SECTION_A, LocalDate.of(2025, 4, 1), "ABSENT");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 4, 1), null);
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 15), null); // present, no personal row
        insertAttendance(STUDENT, "9", CLASS_9, SECTION_A, LocalDate.of(2026, 3, 31), "ABSENT");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2026, 3, 31), null);

        AttendanceSummaryDTO dto = attendanceService.getStudentSummary(STUDENT, "year", null, null, SESSION_PRIOR_LABEL);

        assertThat(dto.getClassName()).isEqualTo("9"); // historical, NOT the live "10"
        assertThat(dto.getTotalWorkingDays()).isEqualTo(3); // first day, mid-session day, last day — inclusive
        assertThat(dto.getDaysAbsent()).isEqualTo(2.0);
        assertThat(dto.getDaysPresent()).isEqualTo(1.0);
    }

    @Test
    void midSessionClassAndSectionChangePartitionTheDenominatorAndExcludeTheGap() {
        // 9/A through Aug 10, 10/A Aug 11-20, gap Aug 21-22, 10/B from Aug 23 — a class change,
        // a section change within the new class, and an exit/readmission-style gap, all in one
        // requested range.
        insertClosedEnrollment(STUDENT, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "CLASS_CHANGE");
        insertClosedEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10A,
                LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 20), "SECTION_CHANGE");
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 8, 23));

        // Both classes hold school every day all month (for other students) — proves the fix:
        // a naive whole-range union of "every class this student's rows ever show" would count
        // up to 31 days; the enrollment-partitioned calculation must not.
        for (int d = 1; d <= 31; d++) {
            LocalDate day = LocalDate.of(2026, 8, d);
            insertAttendance("X", "9", CLASS_9, SECTION_A, day, null);
            insertAttendance("X", "10", CLASS_10, SECTION_10A, day, null);
        }
        // Stray personal evidence INSIDE the authoritative gap (Aug 21) — must be preserved in
        // the database but excluded from the calculation, not fabricate membership.
        insertAttendance(STUDENT, "10", CLASS_10, SECTION_10A, LocalDate.of(2026, 8, 21), "ABSENT");

        AttendanceSummaryDTO dto = attendanceService.getStudentAttendanceForDateRange(
                STUDENT, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        // 10 (class 9, Aug 1-10) + 10 (class 10/A, Aug 11-20) + 9 (class 10/B, Aug 23-31) = 29.
        // NOT 31 (whole range) and NOT double-counting the Aug 10/11 transition date.
        assertThat(dto.getTotalWorkingDays()).isEqualTo(29);
        // The Aug 21 stray ABSENT row falls inside the authoritative gap and must not count.
        assertThat(dto.getDaysAbsent()).isEqualTo(0.0);
        assertThat(dto.getDaysPresent()).isEqualTo(29.0);
        // Display class = the segment effective at the end of the requested range.
        assertThat(dto.getClassName()).isEqualTo("10");

        // Confirmed via the same fixture from the class-level view (getStudentSummary "month"
        // covering only the tail end) reaches the same conclusion for June — i.e. this is not an
        // artifact of the report-card-facing entry point alone.
        AttendanceSummaryDTO monthDto = attendanceService.getStudentSummary(STUDENT, "month", 8, 2026, null);
        assertThat(monthDto.getTotalWorkingDays()).isEqualTo(29);
    }

    @Test
    void plannedAndCancelledEnrollmentsAreIgnoredAndAttendanceFallsBackToLegacyEvidence() {
        // A future PLANNED row and a CANCELLED row for the requested session — neither is
        // realized membership, so the whole period must resolve as legacy-uncovered (this
        // student has zero realized enrollment anywhere), using the attendance-row bridge.
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from) " +
                        "VALUES (?,?,?,?,?,?,?,'PLANNED',DATE '2027-01-01')",
                SCHOOL, STUDENT, SESSION_CURRENT, CLASS_10, "10", SECTION_10B, "B");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,'CANCELLED',DATE '2026-09-01',DATE '2026-09-01','CANCELLED_BEFORE_START')",
                SCHOOL, STUDENT, SESSION_CURRENT, CLASS_10, "10", SECTION_10B, "B");

        insertAttendance(STUDENT, "10", CLASS_10, SECTION_10B, LocalDate.of(2026, 9, 2), "ABSENT");
        insertAttendance("X", "10", CLASS_10, SECTION_10B, LocalDate.of(2026, 9, 2), null);
        insertAttendance("X", "10", CLASS_10, SECTION_10B, LocalDate.of(2026, 9, 3), null);

        AttendanceSummaryDTO dto = attendanceService.getStudentSummary(STUDENT, "month", 9, 2026, null);

        assertThat(dto.getClassName()).isEqualTo("10");
        assertThat(dto.getTotalWorkingDays()).isEqualTo(2);
        assertThat(dto.getDaysAbsent()).isEqualTo(1.0);
    }

    @Test
    void legacyStudentWithNoEnrollmentAtAllAndPreAdoptionDatesStillWork() {
        // LEGACY_STUDENT has zero StudentEnrollment rows anywhere — the entire request must
        // resolve as legacy-uncovered and use the pre-E6C attendance-row bridge unchanged.
        insertAttendance(LEGACY_STUDENT, "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 4), "ABSENT");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 4), null);
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 5), null);

        AttendanceSummaryDTO dto = attendanceService.getStudentSummary(LEGACY_STUDENT, "month", 8, 2025, null);
        assertThat(dto.getClassName()).isEqualTo("9");
        assertThat(dto.getTotalWorkingDays()).isEqualTo(2);
        assertThat(dto.getDaysAbsent()).isEqualTo(1.0);

        // Now give STUDENT a realized enrollment starting later in the same session — a request
        // for dates strictly BEFORE that boundary must still resolve as legacy (pre-adoption),
        // not as an authoritative gap.
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 9, 1));
        insertAttendance(STUDENT, "9", CLASS_9, SECTION_A, LocalDate.of(2026, 5, 4), "ABSENT");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2026, 5, 4), null);

        AttendanceSummaryDTO preAdoption = attendanceService.getStudentSummary(STUDENT, "month", 5, 2026, null);
        assertThat(preAdoption.getClassName()).isEqualTo("9");
        assertThat(preAdoption.getTotalWorkingDays()).isEqualTo(1);
        assertThat(preAdoption.getDaysAbsent()).isEqualTo(1.0);
    }

    @Test
    void historicalClassSummaryIncludesAPromotedStudentUsingTheirHistoricalSection() {
        // STUDENT's live row is class "10" / section 10B (post-promotion) — findByClassNameAndStatus
        // for "9" would never find them. The class-9 summary for the PRIOR session must still
        // surface them via their realized enrollment, filtered by their HISTORICAL section (A),
        // not their current live section.
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 15), null);

        List<ClassAttendanceSummaryDTO> classNineSectionA =
                attendanceService.getClassSummary("9", "year", null, null, SESSION_PRIOR_LABEL, SECTION_A);
        assertThat(classNineSectionA).extracting(ClassAttendanceSummaryDTO::getStudentId).contains(STUDENT);

        // The student was never in section A of class 10 — filtering class 10 (their CURRENT
        // class) by their live section must not spuriously attribute this historical row.
        List<ClassAttendanceSummaryDTO> classNineOtherSection =
                attendanceService.getClassSummary("9", "year", null, null, SESSION_PRIOR_LABEL, SECTION_10A);
        assertThat(classNineOtherSection).extracting(ClassAttendanceSummaryDTO::getStudentId).doesNotContain(STUDENT);
    }

    @Test
    void historicalSchoolSummaryIncludesAClassWithNoCurrentlyActiveStudents() {
        // Every student currently live-ACTIVE is in class "10" — class "9" has realized
        // enrollment history in the PRIOR session only. A school-wide summary for that session
        // must not silently drop class "9" just because findDistinctActiveClassNamesBySchoolId
        // (current-only) would never surface it.
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 15), null);

        List<ClassAttendanceSummaryDTO> schoolSummary =
                attendanceService.getSchoolSummary("year", null, null, SESSION_PRIOR_LABEL);

        assertThat(schoolSummary).extracting(ClassAttendanceSummaryDTO::getClassName).contains("9");
        assertThat(schoolSummary).extracting(ClassAttendanceSummaryDTO::getStudentId).contains(STUDENT);
    }

    @Test
    void tenantIsolationHoldsAcrossEnrollmentAndAttendanceLookups() {
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 4, 1));
        // OTHER_SCHOOL happens to name its class "9" too — a same-named, different-tenant class
        // that must never be confused with SCHOOL's own CLASS_9.
        insertClosedEnrollment(OTHER_SCHOOL, OTHER_STUDENT, SESSION_OTHER, CLASS_OTHER, SECTION_OTHER,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "SESSION_COMPLETED");
        jdbc.update("INSERT INTO attendance (school_id,student_id,class_name,class_id,section_id,date,status,charge_paid) " +
                        "VALUES (?,?,?,?,?,?,?,false)",
                OTHER_SCHOOL, "X", "9", CLASS_OTHER, SECTION_OTHER, LocalDate.of(2026, 4, 10), null);

        // SCHOOL's own "9"/"2026-2027" resolution must never surface OTHER_SCHOOL's same-named
        // class or its student, even though both schools share the session label.
        List<ClassAttendanceSummaryDTO> classSummary =
                attendanceService.getClassSummary("9", "year", null, null, SESSION_CURRENT_LABEL, null);
        assertThat(classSummary).extracting(ClassAttendanceSummaryDTO::getStudentId).doesNotContain(OTHER_STUDENT);

        List<ClassAttendanceSummaryDTO> schoolSummary =
                attendanceService.getSchoolSummary("year", null, null, SESSION_CURRENT_LABEL);
        assertThat(schoolSummary).extracting(ClassAttendanceSummaryDTO::getStudentId).doesNotContain(OTHER_STUDENT);

        // A cross-tenant studentId must resolve as not-found, never leak the other school's data.
        assertThatThrownBy(() -> attendanceService.getStudentAttendanceForDateRange(
                OTHER_STUDENT, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)))
                .isInstanceOf(java.util.NoSuchElementException.class);

        AttendanceSummaryDTO dto = attendanceService.getStudentAttendanceForDateRange(
                STUDENT, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
        assertThat(dto.getStudentId()).isEqualTo(STUDENT);
    }

    @Test
    void historicalReadsPerformZeroWritesAndLeaveTableSignaturesUnchanged() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 4, 1));
        insertAttendance(STUDENT, "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 4), "ABSENT");
        insertAttendance("X", "9", CLASS_9, SECTION_A, LocalDate.of(2025, 8, 4), null);

        TableSignature before = signatures();

        attendanceService.getStudentSummary(STUDENT, "year", null, null, SESSION_PRIOR_LABEL);
        attendanceService.getStudentAttendanceForDateRange(STUDENT, LocalDate.of(2025, 8, 1), LocalDate.of(2025, 8, 31));
        attendanceService.getDailyAttendance(STUDENT, 8, 2025);
        attendanceService.getAttendanceCounts(STUDENT, 2025, 8);
        attendanceService.getClassSummary("9", "year", null, null, SESSION_PRIOR_LABEL, null);
        attendanceService.getSchoolSummary("year", null, null, SESSION_PRIOR_LABEL);

        assertThat(signatures()).isEqualTo(before);
    }

    private record TableSignature(long students, long enrollments, long attendance) {}

    private TableSignature signatures() {
        return new TableSignature(count("student"), count("student_enrollment"), count("attendance"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day) " +
                "VALUES (?,true,CURRENT_TIMESTAMP,?,'TRIAL',?,4,8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId, String label, LocalDate from, LocalDate to) {
        jdbc.update("INSERT INTO academic_session " +
                        "(id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,?,?,?,false,CURRENT_TIMESTAMP)",
                id, schoolId, label, from, to);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,?,true,false)",
                id, schoolId, name);
    }

    private void insertSection(long id, long schoolId, long classId, String name) {
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,?,true)",
                id, schoolId, classId, name);
    }

    private void insertStudent(String id, long schoolId, String className, long classId, long sectionId) {
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,name) " +
                        "VALUES (?,?,'ACTIVE',?,?,?,?)",
                id, schoolId, classId, className, sectionId, "Student " + id);
    }

    private void insertActiveEnrollment(String studentId, long sessionId, long classId, long sectionId, LocalDate from) {
        insertEnrollment(SCHOOL, studentId, sessionId, classId, sectionId, "ACTIVE", from, null, null);
    }

    private void insertClosedEnrollment(String studentId, long sessionId, long classId, long sectionId,
                                        LocalDate from, LocalDate to, String reason) {
        insertEnrollment(SCHOOL, studentId, sessionId, classId, sectionId, "CLOSED", from, to, reason);
    }

    private void insertClosedEnrollment(long schoolId, String studentId, long sessionId, long classId, long sectionId,
                                        LocalDate from, LocalDate to, String reason) {
        insertEnrollment(schoolId, studentId, sessionId, classId, sectionId, "CLOSED", from, to, reason);
    }

    private void insertEnrollment(long schoolId, String studentId, long sessionId, long classId, long sectionId,
                                  String status, LocalDate from, LocalDate to, String reason) {
        String className = (classId == CLASS_9 || classId == CLASS_OTHER) ? "9" : "10";
        String sectionName = sectionId == SECTION_A ? "A" : (sectionId == SECTION_10A ? "A" : "B");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                schoolId, studentId, sessionId, classId, className, sectionId, sectionName,
                status, from, to, reason);
    }

    private void insertAttendance(String studentId, String className, long classId, long sectionId,
                                  LocalDate date, String status) {
        jdbc.update("INSERT INTO attendance (school_id,student_id,class_name,class_id,section_id,date,status,charge_paid) " +
                        "VALUES (?,?,?,?,?,?,?,false)",
                SCHOOL, studentId, className, classId, sectionId, date, status);
    }
}
