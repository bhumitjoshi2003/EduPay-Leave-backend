package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ClassStudentResultDTO;
import com.indraacademy.ias_management.dto.ExamResultDTO;
import com.indraacademy.ias_management.dto.SchoolPerformanceSummaryDTO;
import com.indraacademy.ias_management.dto.StudentSubjectMarkDTO;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Phase E6D: real-PostgreSQL coverage for enrollment-authoritative historical results/marks.
 * Synthetic fixtures use negative identifiers and every test transaction rolls back
 * (@DataJpaTest default), so no cleanup step is required.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MarkService.class, StudentTemporalMembershipResolver.class,
        MarkServiceHistoricalPostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class MarkServiceHistoricalPostgresIT {

    private static final long SCHOOL = -98901L;
    private static final long OTHER_SCHOOL = -98902L;
    private static final long SESSION_PRIOR = -99001L;
    private static final long SESSION_CURRENT = -99002L;
    private static final long SESSION_OTHER = -99003L;
    private static final long CLASS_9 = -99101L;
    private static final long CLASS_10 = -99102L;
    private static final long CLASS_OTHER = -99103L;
    private static final long SECTION_A = -99201L;
    private static final long SECTION_B = -99202L;
    private static final long SECTION_10A = -99203L;
    private static final long SECTION_10B = -99204L;
    private static final long SECTION_OTHER = -99205L;
    private static final String SESSION_PRIOR_LABEL = "2025-2026";
    private static final String SESSION_CURRENT_LABEL = "2026-2027";
    private static final String STUDENT = "E6D-PG-S1";
    private static final String LEGACY_STUDENT = "E6D-PG-LEGACY";
    private static final String OTHER_STUDENT = "E6D-PG-OTHER";

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MarkService markService;
    @Autowired StudentRepository studentRepository;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean StudentService studentService;
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
        // StudentService itself pulls in a large, unrelated dependency graph (fees, entitlements,
        // parent portal, id generation...) that this narrow @DataJpaTest slice has no reason to
        // wire — mock it and delegate its three MarkService-relevant methods to the real
        // StudentRepository, matching exactly what the real StudentService does internally.
        lenient().when(studentService.getStudent(anyString()))
                .thenAnswer(inv -> studentRepository.findByStudentIdAndSchoolId(inv.getArgument(0), SCHOOL));
        lenient().when(studentService.getActiveStudentsByClass(anyString()))
                .thenAnswer(inv -> studentRepository.findByClassNameAndStatusAndSchoolId(
                        inv.getArgument(0), StudentStatus.ACTIVE, SCHOOL));
        lenient().when(studentService.getActiveStudentsByClassAndSection(anyString(), anyLong()))
                .thenAnswer(inv -> studentRepository.findByClassNameAndSectionIdAndStatusAndSchoolId(
                        inv.getArgument(0), inv.getArgument(1), StudentStatus.ACTIVE, SCHOOL));

        insertSchool(SCHOOL, "e6d-mark-it");
        insertSchool(OTHER_SCHOOL, "e6d-mark-it-other");
        insertSession(SESSION_PRIOR, SCHOOL, SESSION_PRIOR_LABEL, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertSession(SESSION_CURRENT, SCHOOL, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertSession(SESSION_OTHER, OTHER_SCHOOL, SESSION_CURRENT_LABEL, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31));
        insertClass(CLASS_9, SCHOOL, "9");
        insertClass(CLASS_10, SCHOOL, "10");
        insertClass(CLASS_OTHER, OTHER_SCHOOL, "9");
        insertSection(SECTION_A, SCHOOL, CLASS_9, "A");
        insertSection(SECTION_B, SCHOOL, CLASS_9, "B");
        insertSection(SECTION_10A, SCHOOL, CLASS_10, "A");
        insertSection(SECTION_10B, SCHOOL, CLASS_10, "B");
        insertSection(SECTION_OTHER, OTHER_SCHOOL, CLASS_OTHER, "A");
        // Live Student row is deliberately stale — className "10" post-promotion, section 10B —
        // every test proves historical figures come from enrollment/mark evidence, never this.
        insertStudent(STUDENT, SCHOOL, "10", CLASS_10, SECTION_10B);
        insertStudent(LEGACY_STUDENT, SCHOOL, "9", CLASS_9, SECTION_A);
        insertStudent(OTHER_STUDENT, OTHER_SCHOOL, "9", CLASS_OTHER, SECTION_OTHER);
    }

    @Test
    void promotedStudentReadsMarkedResultAndDiscoversUnmarkedExamViaEnrollment() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 4, 1));

        long halfYearly = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long mathEntry = insertSubjectEntry(halfYearly, "Math", 100, null);
        insertMark(STUDENT, mathEntry, 85.0);

        // Configured but never marked for this student — must still be discoverable via
        // realized enrollment alone.
        long finalExam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Final");
        insertSubjectEntry(finalExam, "Science", 100, null);

        List<ExamResultDTO> results = markService.getStudentResults(STUDENT, SESSION_PRIOR_LABEL);

        assertThat(results).extracting(ExamResultDTO::getExamName).containsExactlyInAnyOrder("Half Yearly", "Final");
        assertThat(results).extracting(ExamResultDTO::getClassName).containsOnly("9"); // NOT "10"
        ExamResultDTO half = results.stream().filter(r -> "Half Yearly".equals(r.getExamName())).findFirst().orElseThrow();
        assertThat(half.getTotalMarksObtained()).isEqualTo(85.0);
        ExamResultDTO fin = results.stream().filter(r -> "Final".equals(r.getExamName())).findFirst().orElseThrow();
        assertThat(fin.getSubjects().get(0).getMarksObtained()).isNull();
    }

    @Test
    void midSessionClassChangeResolvesSubjectDatesIndependentlyAcrossTheTransition() {
        insertClosedEnrollment(STUDENT, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "CLASS_CHANGE");
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10A, LocalDate.of(2026, 8, 11));

        long beforeExam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "Unit Test Before");
        insertSubjectEntry(beforeExam, "Math", 50, LocalDate.of(2026, 8, 5)); // inside class-9 segment
        long afterLeftExam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "Unit Test After Left");
        insertSubjectEntry(afterLeftExam, "Math", 50, LocalDate.of(2026, 8, 20)); // after they left class 9
        long class10Exam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "10", "Unit Test Class 10");
        insertSubjectEntry(class10Exam, "Science", 50, LocalDate.of(2026, 8, 15)); // inside class-10 segment

        List<ExamResultDTO> results = markService.getStudentResults(STUDENT, SESSION_CURRENT_LABEL);

        assertThat(results).extracting(ExamResultDTO::getExamName)
                .containsExactlyInAnyOrder("Unit Test Before", "Unit Test Class 10");
    }

    @Test
    void exitReadmissionGapDoesNotFabricateExamMembership() {
        insertClosedEnrollment(STUDENT, SESSION_CURRENT, CLASS_9, SECTION_A,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), "WITHDRAWN");
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_9, SECTION_A, LocalDate.of(2026, 8, 21));

        long beforeGapExam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "Before Gap");
        insertSubjectEntry(beforeGapExam, "Math", 50, LocalDate.of(2026, 8, 5));
        long inGapExam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "During Gap");
        insertSubjectEntry(inGapExam, "Math", 50, LocalDate.of(2026, 8, 15)); // inside the exit/readmission gap
        long afterGapExam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "After Gap");
        insertSubjectEntry(afterGapExam, "Math", 50, LocalDate.of(2026, 8, 25));

        List<ExamResultDTO> results = markService.getStudentResults(STUDENT, SESSION_CURRENT_LABEL);

        assertThat(results).extracting(ExamResultDTO::getExamName)
                .containsExactlyInAnyOrder("Before Gap", "After Gap");
    }

    @Test
    void legacyMarksWithNoEnrollmentDataAtAllRemainReadable() {
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long entry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(LEGACY_STUDENT, entry, 60.0);

        List<ExamResultDTO> results = markService.getStudentResults(LEGACY_STUDENT, SESSION_PRIOR_LABEL);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getClassName()).isEqualTo("9");
        assertThat(results.get(0).getTotalMarksObtained()).isEqualTo(60.0);
    }

    @Test
    void plannedAndCancelledEnrollmentAreIgnoredAndDoNotDiscoverAnUnmarkedExam() {
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from) " +
                        "VALUES (?,?,?,?,?,?,?,'PLANNED',DATE '2027-01-01')",
                SCHOOL, STUDENT, SESSION_CURRENT, CLASS_9, "9", SECTION_A, "A");
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,'CANCELLED',DATE '2026-09-01',DATE '2026-09-01','CANCELLED_BEFORE_START')",
                SCHOOL, STUDENT, SESSION_CURRENT, CLASS_9, "9", SECTION_A, "A");

        long exam = insertExamConfig(SCHOOL, SESSION_CURRENT_LABEL, "9", "Unit Test");
        insertSubjectEntry(exam, "Math", 50, LocalDate.of(2026, 9, 5));

        List<ExamResultDTO> results = markService.getStudentResults(STUDENT, SESSION_CURRENT_LABEL);

        assertThat(results).isEmpty(); // PLANNED/CANCELLED never establish membership
    }

    @Test
    void historicalClassResultsAndSubjectEntryRosterUseHistoricalSectionForAPromotedStudent() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");

        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long mathEntry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(STUDENT, mathEntry, 90.0);

        List<ClassStudentResultDTO> sectionAResults = markService.getClassResults("9", exam, SECTION_A);
        assertThat(sectionAResults).extracting(ClassStudentResultDTO::getStudentId).contains(STUDENT);

        // The student was never in section B of class 9 — filtering by that section must not
        // spuriously attribute their historical result.
        List<ClassStudentResultDTO> sectionBResults = markService.getClassResults("9", exam, SECTION_B);
        assertThat(sectionBResults).extracting(ClassStudentResultDTO::getStudentId).doesNotContain(STUDENT);

        List<StudentSubjectMarkDTO> subjectRoster = markService.getStudentsForSubjectEntry(mathEntry, SECTION_A);
        assertThat(subjectRoster).extracting(StudentSubjectMarkDTO::getStudentId).contains(STUDENT);
    }

    @Test
    void schoolPerformanceSummaryIncludesAClassWithNoCurrentlyActiveStudents() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long mathEntry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(STUDENT, mathEntry, 77.0);

        // Every currently-ACTIVE student (live) is in class "10" — class "9" only has realized
        // enrollment history in the PRIOR session.
        SchoolPerformanceSummaryDTO summary = markService.getSchoolPerformanceSummary(SESSION_PRIOR_LABEL);

        assertThat(summary.getClassResults()).extracting(dto -> dto.getClassName()).contains("9");
    }

    @Test
    void tenantIsolationHoldsAcrossEnrollmentAndMarkLookups() {
        insertActiveEnrollment(STUDENT, SESSION_CURRENT, CLASS_10, SECTION_10B, LocalDate.of(2026, 4, 1));
        // OTHER_SCHOOL happens to name its class "9" too.
        insertClosedEnrollment(OTHER_SCHOOL, OTHER_STUDENT, SESSION_OTHER, CLASS_OTHER, SECTION_OTHER,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "SESSION_COMPLETED");
        long otherExam = insertExamConfig(OTHER_SCHOOL, SESSION_CURRENT_LABEL, "9", "Other Half Yearly");
        long otherEntry = insertSubjectEntry(otherExam, "Math", 100, null);
        insertMark(OTHER_STUDENT, otherEntry, 55.0);

        List<ExamResultDTO> otherStudentViaThisSchool = markService.getStudentResults(STUDENT, SESSION_CURRENT_LABEL);
        assertThat(otherStudentViaThisSchool).isEmpty(); // no exams configured for SCHOOL this session

        SchoolPerformanceSummaryDTO summary = markService.getSchoolPerformanceSummary(SESSION_CURRENT_LABEL);
        assertThat(summary.getClassResults()).extracting(dto -> dto.getClassName()).doesNotContain("9");
        assertThat(summary.getClassResults()).flatExtracting(dto -> dto.getStudentsRanked())
                .isEmpty();
    }

    @Test
    void historicalReadsPerformZeroWritesAndLeaveTableSignaturesUnchanged() {
        insertClosedEnrollment(STUDENT, SESSION_PRIOR, CLASS_9, SECTION_A,
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "SESSION_COMPLETED");
        long exam = insertExamConfig(SCHOOL, SESSION_PRIOR_LABEL, "9", "Half Yearly");
        long mathEntry = insertSubjectEntry(exam, "Math", 100, null);
        insertMark(STUDENT, mathEntry, 85.0);

        TableSignature before = signatures();

        markService.getStudentResults(STUDENT, SESSION_PRIOR_LABEL);
        markService.getStudentResults(STUDENT, null);
        markService.getClassResults("9", exam, SECTION_A);
        markService.getStudentsForSubjectEntry(mathEntry, SECTION_A);
        markService.getSchoolPerformanceSummary(SESSION_PRIOR_LABEL);
        markService.getStudentMarksForExam(STUDENT, exam);

        assertThat(signatures()).isEqualTo(before);
    }

    private record TableSignature(long students, long enrollments, long examConfigs, long entries, long marks) {}

    private TableSignature signatures() {
        return new TableSignature(count("student"), count("student_enrollment"),
                count("exam_config"), count("exam_subject_entry"), count("student_mark"));
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
        String sectionName = (sectionId == SECTION_A || sectionId == SECTION_10A) ? "A" : "B";
        jdbc.update("INSERT INTO student_enrollment " +
                        "(school_id,student_id,academic_session_id,class_id,class_name_snapshot," +
                        "section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                schoolId, studentId, sessionId, classId, className, sectionId, sectionName,
                status, from, to, reason);
    }

    private long examConfigSeq = -99301L;
    private final java.util.Map<Long, Long> examConfigSchool = new java.util.HashMap<>();

    private long insertExamConfig(long schoolId, String session, String className, String examName) {
        long id = examConfigSeq--;
        jdbc.update("INSERT INTO exam_config (id,school_id,session,class_name,exam_name) VALUES (?,?,?,?,?)",
                id, schoolId, session, className, examName);
        examConfigSchool.put(id, schoolId);
        return id;
    }

    private long entrySeq = -99401L;

    private long insertSubjectEntry(long examConfigId, String subjectName, int maxMarks, LocalDate examDate) {
        long id = entrySeq--;
        long schoolId = examConfigSchool.get(examConfigId);
        jdbc.update("INSERT INTO exam_subject_entry (id,school_id,exam_config_id,subject_name,max_marks,exam_date) VALUES (?,?,?,?,?,?)",
                id, schoolId, examConfigId, subjectName, maxMarks, examDate);
        return id;
    }

    private long markSeq = -99501L;

    private void insertMark(String studentId, long examSubjectEntryId, double marksObtained) {
        long id = markSeq--;
        Long schoolId = jdbc.queryForObject("SELECT school_id FROM exam_subject_entry WHERE id = ?", Long.class, examSubjectEntryId);
        jdbc.update("INSERT INTO student_mark (id,school_id,student_id,exam_subject_entry_id,created_at,updated_at,marks_obtained) VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)",
                id, schoolId, studentId, examSubjectEntryId, marksObtained);
    }
}
