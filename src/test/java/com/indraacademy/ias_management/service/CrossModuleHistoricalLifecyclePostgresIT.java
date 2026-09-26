package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.AttendanceSummaryDTO;
import com.indraacademy.ias_management.dto.ExamResultDTO;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardPublicationDTO;
import com.indraacademy.ias_management.entity.StudentStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Phase E6F: cross-module PostgreSQL integration coverage proving Attendance (E6C), Results/Marks
 * (E6D), and Report Cards (E6E) tell the SAME historical story for one student across every
 * lifecycle transition StudentEnrollment (E6B) models — promotion, mid-session class change,
 * section-only change, exit/readmission, legacy (pre-adoption) history, and graduation.
 *
 * Synthetic fixtures use negative identifiers and every test transaction rolls back
 * (@DataJpaTest default), so no cleanup step is required.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AttendanceService.class, MarkService.class, ReportCardDataAssembler.class,
        StudentTemporalMembershipResolver.class, AcademicSessionService.class,
        ReportCardTemplateService.class, WeightageCalculationEngine.class,
        ReportCardPublicationService.class, com.indraacademy.ias_management.config.ClockConfig.class,
        CrossModuleHistoricalLifecyclePostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class CrossModuleHistoricalLifecyclePostgresIT {

    private static final long SCHOOL = -101601L;
    private static final long SESSION_PRIOR = -101701L;   // 2025-2026
    private static final long SESSION_CURRENT = -101702L; // 2026-2027
    private static final long CLASS_9 = -101801L;
    private static final long CLASS_10 = -101802L;
    private static final long SECTION_A = -101901L;
    private static final long SECTION_B = -101902L;
    private static final long ASSESSMENT_GROUP = -102001L;
    private static final long TEMPLATE = -102101L;
    private static final String SESSION_PRIOR_LABEL = "2025-2026";
    private static final String SESSION_CURRENT_LABEL = "2026-2027";

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceService attendanceService;
    @Autowired MarkService markService;
    @Autowired ReportCardDataAssembler assembler;
    @Autowired ReportCardPublicationService publicationService;
    @Autowired StudentRepository studentRepository;
    @MockBean ObjectStorageService objectStorageService; // ReportCardDataAssembler's logo lookup
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean StudentService studentService;
    @MockBean com.indraacademy.ias_management.service.BusinessNotificationService businessNotificationService;
    @MockBean ReportCardEmailBlastService reportCardEmailBlastService;
    @MockBean RemarksService remarksService;
    @MockBean TimetableSessionAccessService timetableSessionAccessService;
    @MockBean TeacherClassScopeService teacherClassScopeService;

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
        lenient().when(studentService.getStudent(anyString()))
                .thenAnswer(inv -> studentRepository.findByStudentIdAndSchoolId(inv.getArgument(0), SCHOOL));
        lenient().when(studentService.getActiveStudentsByClass(anyString()))
                .thenAnswer(inv -> studentRepository.findByClassNameAndStatusAndSchoolId(
                        inv.getArgument(0), StudentStatus.ACTIVE, SCHOOL));
        lenient().when(studentService.getActiveStudentsByClassAndSection(anyString(), anyLong()))
                .thenAnswer(inv -> studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(
                        inv.getArgument(0), inv.getArgument(1), StudentStatus.ACTIVE, SCHOOL));

        insertSchool(SCHOOL, "e6f-cross-module-it");
        insertSession(SESSION_PRIOR, SESSION_PRIOR_LABEL, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertSession(SESSION_CURRENT, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertClass(CLASS_9, "9");
        insertClass(CLASS_10, "10");
        insertSection(SECTION_A, CLASS_9, "A");
        insertSection(SECTION_B, CLASS_9, "B");

        jdbc.update("INSERT INTO assessment_group (id,school_id,session,class_name,name,group_type,display_order) " +
                "VALUES (?,?,?,?,?,?,0)", ASSESSMENT_GROUP, SCHOOL, SESSION_CURRENT_LABEL, "9", "Annual", "EXAM_BASED");
        jdbc.update("INSERT INTO report_card_template (id,school_id,name,assessment_group_id,is_default,is_active) " +
                "VALUES (?,?,?,?,false,true)", TEMPLATE, SCHOOL, "Standard", ASSESSMENT_GROUP);
        jdbc.update("INSERT INTO report_card_template_section (id,template_id,section_type,enabled,display_order) " +
                "VALUES (?,?,?,true,0)", TEMPLATE - 1, TEMPLATE, "ATTENDANCE");
    }

    // ─── Scenario A: promotion ──────────────────────────────────────────────

    @Test
    void scenarioA_promotionKeepsSourceHistoryCoherentAcrossAllThreeModules() {
        String student = "E6F-A";
        insertStudent(student, "10", CLASS_10, null); // live projection already promoted
        insertClosedEnrollment(student, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertActiveEnrollment(student, SESSION_CURRENT, CLASS_10, null, LocalDate.of(2026, 4, 1));

        markAttendance(student, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 15), "PRESENT");
        markAttendance(student, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 16), "ABSENT");

        long exam = insertExamConfig(SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long entry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(student, entry, 85.0);

        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        // Live projection: promoted.
        assertThat(studentRepository.findByStudentIdAndSchoolId(student, SCHOOL).orElseThrow().getClassName())
                .isEqualTo("10");

        // Attendance still resolves class 9 for the source session.
        AttendanceSummaryDTO attendance = attendanceService.getStudentSummary(student, "year", null, null, SESSION_PRIOR_LABEL);
        assertThat(attendance.getClassName()).isEqualTo("9");
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(2);
        assertThat(attendance.getDaysAbsent()).isEqualTo(1);

        // Results still resolve class 9.
        List<ExamResultDTO> results = markService.getStudentResults(student, SESSION_PRIOR_LABEL);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClassName()).isEqualTo("9");

        // Report card still resolves class 9, section from enrollment, and remains published.
        var context = assembler.resolveHistoricalContext(student, SESSION_PRIOR_LABEL, null);
        assertThat(context.className()).isEqualTo("9");
        assertThat(context.sectionId()).isEqualTo(SECTION_A);
        assertThat(publicationService.isPublished(TEMPLATE, SESSION_PRIOR_LABEL, context.className())).isTrue();

        ReportCardDataDTO dto = assembler.assemble(student, TEMPLATE, SESSION_PRIOR_LABEL);
        assertThat(dto.getClassName()).isEqualTo("9");
        assertThat(dto.getSectionName()).isEqualTo("A");

        // The target ACTIVE enrollment never contaminates the source session's story.
        List<ExamResultDTO> targetResults = markService.getStudentResults(student, SESSION_CURRENT_LABEL);
        assertThat(targetResults).isEmpty();
    }

    // ─── Scenario B: mid-session class change ───────────────────────────────

    @Test
    void scenarioB_midSessionClassChangePartitionsAttendanceAndResultsAndReportCardRequiresDisambiguation() {
        String student = "E6F-B";
        insertStudent(student, "10", CLASS_10, null);
        insertClosedEnrollment(student, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "CLASS_CHANGE");
        insertActiveEnrollment(student, SESSION_CURRENT, CLASS_10, null, LocalDate.of(2026, 8, 11));

        // Attendance across the transition: the enrollment roster puts the student in class 9's
        // submissions for Aug 1-10 and class 10's (no sections) for Aug 11-20. Peers fill the
        // other class's submissions on every day.
        for (int d = 1; d <= 20; d++) {
            LocalDate day = LocalDate.of(2026, 8, d);
            markAttendance(d <= 10 ? student : "E6F-B-PEER9", CLASS_9, SECTION_A, day, "PRESENT");
            markAttendance(d <= 10 ? "E6F-B-PEER10" : student, CLASS_10, null, day, "PRESENT");
        }
        AttendanceSummaryDTO attendance = attendanceService.getStudentAttendanceForDateRange(
                student, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20));
        // 10 days as class 9 (Aug 1-10) + 10 days as class 10 (Aug 11-20) = 20, not double-counted
        // and not the naive "both classes across the whole range" total either.
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(20);

        // Marked exams on both sides of the transition.
        long examNine = insertExamConfig(SESSION_CURRENT_LABEL, "9", "Before Transition");
        insertMark(student, insertSubjectEntry(examNine, "Math", 50, LocalDate.of(2026, 8, 5)), 40.0);
        long examTen = insertExamConfig(SESSION_CURRENT_LABEL, "10", "After Transition");
        insertMark(student, insertSubjectEntry(examTen, "Math", 50, LocalDate.of(2026, 8, 15)), 45.0);

        List<ExamResultDTO> results = markService.getStudentResults(student, SESSION_CURRENT_LABEL);
        assertThat(results).extracting(ExamResultDTO::getClassName).containsExactlyInAnyOrder("9", "10");

        // Report card: two legitimate class contexts — must not be arbitrarily chosen.
        assertThatThrownBy(() -> assembler.resolveHistoricalContext(student, SESSION_CURRENT_LABEL, null))
                .isInstanceOf(ReportCardDataAssembler.ReportCardContextAmbiguousException.class);

        // Explicit classId resolves the intended report correctly.
        var resolvedNine = assembler.resolveHistoricalContext(student, SESSION_CURRENT_LABEL, CLASS_9);
        assertThat(resolvedNine.className()).isEqualTo("9");
        var resolvedTen = assembler.resolveHistoricalContext(student, SESSION_CURRENT_LABEL, CLASS_10);
        assertThat(resolvedTen.className()).isEqualTo("10");
    }

    // ─── Scenario C: section-only change ─────────────────────────────────────

    @Test
    void scenarioC_sectionOnlyChangeStaysOneClassContextWithHistoricalSectionFiltering() {
        String student = "E6F-C";
        insertStudent(student, "9", CLASS_9, SECTION_B); // live section already B
        insertClosedEnrollment(student, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 8, 31), "SECTION_CHANGE");
        insertActiveEnrollment(student, SESSION_CURRENT, CLASS_9, SECTION_B, LocalDate.of(2026, 9, 1));

        for (int d = 1; d <= 10; d++) {
            LocalDate day = LocalDate.of(2026, 8, 20).plusDays(d);
            markAttendance(student, CLASS_9, SECTION_A, day, "PRESENT");
            markAttendance("E6F-C-PEER", CLASS_9, SECTION_B, day, "PRESENT");
        }
        AttendanceSummaryDTO attendance = attendanceService.getStudentAttendanceForDateRange(
                student, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 30));
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(10); // own section's rows only, never section B's

        // One class context (not two) despite the section change.
        var context = assembler.resolveHistoricalContext(student, SESSION_CURRENT_LABEL, null);
        assertThat(context.className()).isEqualTo("9");
        assertThat(context.sectionId()).isEqualTo(SECTION_B); // the later segment's section, not live-only coincidence

        long exam = insertExamConfig(SESSION_CURRENT_LABEL, "9", "Unit Test");
        insertMark(student, insertSubjectEntry(exam, "Math", 50, null), 30.0);
        List<com.indraacademy.ias_management.dto.ClassStudentResultDTO> sectionAResults =
                markService.getClassResults("9", exam, SECTION_A);
        List<com.indraacademy.ias_management.dto.ClassStudentResultDTO> sectionBResults =
                markService.getClassResults("9", exam, SECTION_B);
        // Historical section filtering: student appears for section A (their segment there
        // overlaps the exam's session) — see augmentRosterWithEnrollment — and for section B too,
        // since both are realized segments within the same session; the key invariant is class
        // identity never splits into two reports.
        assertThat(sectionAResults).extracting(com.indraacademy.ias_management.dto.ClassStudentResultDTO::getStudentId)
                .contains(student);
        assertThat(sectionBResults).extracting(com.indraacademy.ias_management.dto.ClassStudentResultDTO::getStudentId)
                .contains(student);
    }

    // ─── Scenario D: exit / readmission ──────────────────────────────────────

    @Test
    void scenarioD_exitReadmissionGapNeverFabricatesMembershipAcrossModules() {
        String student = "E6F-D";
        insertStudent(student, "9", CLASS_9, SECTION_A);
        insertClosedEnrollment(student, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "WITHDRAWN");
        insertActiveEnrollment(student, SESSION_CURRENT, CLASS_9, SECTION_A, LocalDate.of(2026, 8, 21));

        // The class was marked every day; the enrollment roster only includes the student while
        // enrolled (Aug 1-10 and Aug 21-30), so the gap days carry no row for them.
        for (int d = 1; d <= 30; d++) {
            LocalDate day = LocalDate.of(2026, 8, d);
            markAttendance("E6F-D-PEER", CLASS_9, SECTION_A, day, "ABSENT");
            if (d <= 10 || d >= 21) markAttendance(student, CLASS_9, SECTION_A, day, "PRESENT");
        }

        AttendanceSummaryDTO attendance = attendanceService.getStudentAttendanceForDateRange(
                student, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 30));
        // 10 (Aug 1-10) + 10 (Aug 21-30) = 20; the Aug 11-20 gap contributes nothing.
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(20);
        assertThat(attendance.getDaysAbsent()).isZero(); // a peer's absences never leak into this student

        // An exam dated inside the gap must not be discovered from fabricated membership.
        long gapExam = insertExamConfig(SESSION_CURRENT_LABEL, "9", "During Gap");
        insertSubjectEntry(gapExam, "Math", 50, LocalDate.of(2026, 8, 15));
        long postExam = insertExamConfig(SESSION_CURRENT_LABEL, "9", "After Readmission");
        insertSubjectEntry(postExam, "Math", 50, LocalDate.of(2026, 8, 25));
        List<ExamResultDTO> results = markService.getStudentResults(student, SESSION_CURRENT_LABEL);
        assertThat(results).extracting(ExamResultDTO::getExamName).containsExactly("After Readmission");

        // Report-card membership resolves from the readmitted segment, not a fabricated one.
        var context = assembler.resolveHistoricalContext(student, SESSION_CURRENT_LABEL, null);
        assertThat(context.className()).isEqualTo("9");
    }

    // ─── Scenario E: legacy student (pre-adoption) ───────────────────────────

    @Test
    void scenarioE_legacyStudentHistoryRemainsAccessibleAndUnaffectedByLaterAdoption() {
        String student = "E6F-E";
        insertStudent(student, "9", CLASS_9, SECTION_A);
        // No enrollment at all for the prior session — genuinely legacy.
        markAttendance(student, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 4), "ABSENT");
        long exam = insertExamConfig(SESSION_PRIOR_LABEL, "9", "Half Yearly");
        insertMark(student, insertSubjectEntry(exam, "Math", 100, null), 70.0);
        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        AttendanceSummaryDTO attendance = attendanceService.getStudentSummary(student, "month", 8, 2025, null);
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(1);
        List<ExamResultDTO> results = markService.getStudentResults(student, SESSION_PRIOR_LABEL);
        assertThat(results).hasSize(1);
        ReportCardDataDTO dto = assembler.assemble(student, TEMPLATE, SESSION_PRIOR_LABEL);
        assertThat(dto.getClassName()).isEqualTo("9");

        // Enrollment adoption begins in the CURRENT session — must not rewrite the legacy history.
        insertActiveEnrollment(student, SESSION_CURRENT, CLASS_9, SECTION_A, LocalDate.of(2026, 4, 1));

        AttendanceSummaryDTO attendanceAfterAdoption = attendanceService.getStudentSummary(student, "month", 8, 2025, null);
        assertThat(attendanceAfterAdoption.getTotalWorkingDays()).isEqualTo(1); // unchanged
        List<ExamResultDTO> resultsAfterAdoption = markService.getStudentResults(student, SESSION_PRIOR_LABEL);
        assertThat(resultsAfterAdoption).hasSize(1); // unchanged
    }

    // ─── Scenario F: graduation / PASS_OUT ───────────────────────────────────

    @Test
    void scenarioF_graduatedStudentHistoryRemainsFullyReadable() {
        String student = "E6F-F";
        insertStudent(student, "10", CLASS_10, null);
        jdbc.update("UPDATE student SET status = 'GRADUATED' WHERE student_id = ? AND school_id = ?", student, SCHOOL);
        insertClosedEnrollment(student, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");

        markAttendance(student, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 15), "ABSENT");
        long exam = insertExamConfig(SESSION_PRIOR_LABEL, "9", "Final");
        insertMark(student, insertSubjectEntry(exam, "Math", 100, null), 95.0);
        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        // Historical attendance, results, and report card all remain readable post-graduation —
        // none of them depend on the student's live ACTIVE status.
        AttendanceSummaryDTO attendance = attendanceService.getStudentSummary(student, "year", null, null, SESSION_PRIOR_LABEL);
        assertThat(attendance.getTotalWorkingDays()).isEqualTo(1);

        List<ExamResultDTO> results = markService.getStudentResults(student, SESSION_PRIOR_LABEL);
        assertThat(results).hasSize(1);

        ReportCardDataDTO dto = assembler.assemble(student, TEMPLATE, SESSION_PRIOR_LABEL);
        assertThat(dto.getClassName()).isEqualTo("9");

        // Historical class roster (via enrollment) still includes the now-INACTIVE student.
        List<com.indraacademy.ias_management.dto.ClassStudentResultDTO> classResults =
                markService.getClassResults("9", exam, null);
        assertThat(classResults).extracting(com.indraacademy.ias_management.dto.ClassStudentResultDTO::getStudentId)
                .contains(student);
    }

    // ─── Cross-module: read-only proof ───────────────────────────────────────

    @Test
    void crossModuleHistoricalReadsPerformZeroWritesAndLeaveTableSignaturesUnchanged() {
        String student = "E6F-Z";
        insertStudent(student, "10", CLASS_10, null);
        insertClosedEnrollment(student, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        markAttendance(student, CLASS_9, SECTION_A, LocalDate.of(2025, 8, 4), "ABSENT");
        long exam = insertExamConfig(SESSION_PRIOR_LABEL, "9", "Half Yearly");
        insertMark(student, insertSubjectEntry(exam, "Math", 100, null), 85.0);
        publicationService.publish(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        TableSignature before = signatures();

        attendanceService.getStudentSummary(student, "year", null, null, SESSION_PRIOR_LABEL);
        attendanceService.getStudentAttendanceForDateRange(student, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        markService.getStudentResults(student, SESSION_PRIOR_LABEL);
        markService.getClassResults("9", exam, null);
        assembler.resolveHistoricalContext(student, SESSION_PRIOR_LABEL, null);
        assembler.assemble(student, TEMPLATE, SESSION_PRIOR_LABEL);
        publicationService.isPublished(TEMPLATE, SESSION_PRIOR_LABEL, "9");
        publicationService.getStatus(TEMPLATE, SESSION_PRIOR_LABEL, "9");

        assertThat(signatures()).isEqualTo(before);
    }

    private record TableSignature(long students, long enrollments, long attendance, long examConfigs,
                                  long entries, long marks, long publications, long remarks,
                                  long fees, long payments) {}

    private TableSignature signatures() {
        return new TableSignature(count("student"), count("student_enrollment"), count("student_attendance"),
                count("exam_config"), count("exam_subject_entry"), count("student_mark"),
                count("report_card_publication"), count("report_card_remark"),
                count("student_fees"), count("payment"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day) " +
                "VALUES (?,true,CURRENT_TIMESTAMP,?,'TRIAL',?,4,8)", id, slug, slug);
    }

    private void insertSession(long id, String label, LocalDate from, LocalDate to) {
        jdbc.update("INSERT INTO academic_session " +
                        "(id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,?,?,?,false,CURRENT_TIMESTAMP)",
                id, SCHOOL, label, from, to);
    }

    private void insertClass(long id, String name) {
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,?,true,false)",
                id, SCHOOL, name);
    }

    private void insertSection(long id, long classId, String name) {
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,?,true)",
                id, SCHOOL, classId, name);
    }

    private void insertStudent(String id, String className, long classId, Long sectionId) {
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,name) " +
                        "VALUES (?,?,'ACTIVE',?,?,?,?)",
                id, SCHOOL, classId, className, sectionId, "Student " + id);
    }

    private void insertActiveEnrollment(String studentId, long sessionId, long classId, Long sectionId, LocalDate from) {
        insertEnrollment(studentId, sessionId, classId, sectionId, "ACTIVE", from, null, null);
    }

    private void insertClosedEnrollment(String studentId, long sessionId, long classId, Long sectionId,
                                        LocalDate from, LocalDate to, String reason) {
        insertEnrollment(studentId, sessionId, classId, sectionId, "CLOSED", from, to, reason);
    }

    private void insertEnrollment(String studentId, long sessionId, long classId, Long sectionId,
                                  String status, LocalDate from, LocalDate to, String reason) {
        String className = classId == CLASS_9 ? "9" : "10";
        String sectionName = sectionId == null ? null : (sectionId == SECTION_A ? "A" : "B");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                SCHOOL, studentId, sessionId, classId, className, sectionId, sectionName,
                status, from, to, reason);
    }

    private long examConfigSeq = -102201L;
    private final java.util.Map<Long, Long> examConfigSchool = new java.util.HashMap<>();

    private long insertExamConfig(String session, String className, String examName) {
        long id = examConfigSeq--;
        jdbc.update("INSERT INTO exam_config (id,school_id,session,class_name,exam_name,result_status) VALUES (?,?,?,?,?,'PUBLISHED')",
                id, SCHOOL, session, className, examName);
        examConfigSchool.put(id, SCHOOL);
        return id;
    }

    private long entrySeq = -102301L;

    private long insertSubjectEntry(long examConfigId, String subjectName, int maxMarks, LocalDate examDate) {
        long id = entrySeq--;
        jdbc.update("INSERT INTO exam_subject_entry (id,school_id,exam_config_id,subject_name,max_marks,exam_date) VALUES (?,?,?,?,?,?)",
                id, SCHOOL, examConfigId, subjectName, maxMarks, examDate);
        return id;
    }

    private long markSeq = -102401L;

    private void insertMark(String studentId, long examSubjectEntryId, double marksObtained) {
        long id = markSeq--;
        jdbc.update("INSERT INTO student_mark (id,school_id,student_id,exam_subject_entry_id,created_at,updated_at,marks_obtained) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",
                id, SCHOOL, studentId, examSubjectEntryId, marksObtained);
    }

    private void markAttendance(String studentId, long classId, Long sectionId, LocalDate date, String status) {
        AttendanceV2Fixtures.mark(jdbc, SCHOOL, studentId, classId, sectionId, date, status);
    }
}
