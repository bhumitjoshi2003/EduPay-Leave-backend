package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassStudentResultDTO;
import com.indraacademy.ias_management.dto.ExamResultDTO;
import com.indraacademy.ias_management.dto.MarkEntryRequest;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import com.indraacademy.ias_management.entity.ExamConfig;
import com.indraacademy.ias_management.entity.ExamResultStatus;
import com.indraacademy.ias_management.repository.StudentRepository;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real-PostgreSQL coverage for Results Phase 1: V82 constraints and foreign keys, atomic and
 * fully validated bulk marks entry, DRAFT/PUBLISHED visibility, the single percentage / grade /
 * rank rule across Class Results, My Results and report cards, and delete safety.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({MarkService.class, ExamConfigService.class, WeightageCalculationEngine.class,
        StudentTemporalMembershipResolver.class, TimetableSessionAccessService.class,
        ResultsPhase1PostgresIT.FixedClock.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ResultsPhase1PostgresIT {

    static final long SCHOOL = -95001L, OTHER_SCHOOL = -95002L, SESSION = -95003L, OTHER_SESSION = -95004L;
    static final long CLASS_8 = -95010L, CLASS_9 = -95011L, OTHER_CLASS_8 = -95012L;
    static final long SECTION_A = -95020L, SECTION_B = -95021L;
    static final String A1 = "RES-A1", A2 = "RES-A2", A3 = "RES-A3", B1 = "RES-B1", P9 = "RES-P9", OTHER = "RES-OTHER";

    @TestConfiguration
    static class FixedClock {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-24T06:00:00Z"), ZoneOffset.UTC); }
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
    @Autowired MarkService marks;
    @Autowired ExamConfigService exams;
    @Autowired WeightageCalculationEngine engine;
    @Autowired StudentRepository studentRepository;
    @MockBean SecurityUtil security;
    @MockBean AuditService audit;
    @MockBean StudentService studentService;
    @MockBean TeacherClassScopeService classScope;
    @MockBean com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    long examId, math, science, sanskrit;

    @BeforeEach
    void fixtures() {
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone,grading_system) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'Results IT','TRIAL','results-it',4,8,'Asia/Kolkata','CBSE')," +
                "(?,true,CURRENT_TIMESTAMP,'Results Other','TRIAL','results-other',4,8,'Asia/Kolkata','CBSE')", SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)," +
                "(?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',true,CURRENT_TIMESTAMP)", SESSION, SCHOOL, OTHER_SESSION, OTHER_SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?,'8',true,false),(?,?,'9',true,false),(?,?,'8',true,false)",
                CLASS_8, SCHOOL, CLASS_9, SCHOOL, OTHER_CLASS_8, OTHER_SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true),(?,?,?,'B',true)",
                SECTION_A, SCHOOL, CLASS_8, SECTION_B, SCHOOL, CLASS_8);
        student(A1, "Aarav", CLASS_8, "8", SECTION_A, "A");
        student(A2, "Bina", CLASS_8, "8", SECTION_A, "A");
        student(A3, "Chetan", CLASS_8, "8", SECTION_A, "A");
        student(B1, "Divya", CLASS_8, "8", SECTION_B, "B");
        student(P9, "Esha", CLASS_9, "9", null, null);
        jdbc.update("INSERT INTO class_subject (school_id,class_name,class_id,subject_name,is_elective,optional_group) VALUES " +
                "(?,'8',?,'Math',false,NULL),(?,'8',?,'Science',false,NULL),(?,'8',?,'Sanskrit',true,'Language')",
                SCHOOL, CLASS_8, SCHOOL, CLASS_8, SCHOOL, CLASS_8);
        jdbc.update("INSERT INTO student_elective_enrollment (school_id,student_id,class_name,optional_group,subject_name) VALUES (?,?,'8','Language','Sanskrit')",
                SCHOOL, A2);

        when(security.getSchoolId()).thenReturn(SCHOOL);
        when(security.getRole()).thenReturn("ADMIN");
        when(security.getUsername()).thenReturn("admin-res");
        when(studentService.getActiveStudentsByClass(anyString())).thenAnswer(inv ->
                studentRepository.findAll().stream().filter(s -> SCHOOL == s.getSchoolId() && inv.getArgument(0).equals(s.getClassName())).toList());
        when(studentService.getStudent(anyString())).thenAnswer(inv -> studentRepository.findById(inv.getArgument(0)));

        ExamConfig exam = exams.addExam("2026-2027", "8", "Half Yearly");
        examId = exam.getId();
        math = exams.addExamSubject(examId, "Math", 100, null).getId();
        science = exams.addExamSubject(examId, "Science", 100, null).getId();
        sanskrit = exams.addExamSubject(examId, "Sanskrit", 50, null).getId();
    }

    // ─── Database ────────────────────────────────────────────────────────

    @Test
    void sameExamNameAndClassSubjectAllowedInDifferentSchools() {
        when(security.getSchoolId()).thenReturn(OTHER_SCHOOL);
        ExamConfig other = exams.addExam("2026-2027", "8", "Half Yearly");
        assertThat(other.getSchoolId()).isEqualTo(OTHER_SCHOOL);
        jdbc.update("INSERT INTO class_subject (school_id,class_name,subject_name,is_elective) VALUES (?,'8','Math',false)", OTHER_SCHOOL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_config WHERE exam_name='Half Yearly' AND class_name='8'", Long.class)).isEqualTo(2);
    }

    @Test
    void examNameStillUniqueWithinOneSchool() {
        assertThatThrownBy(() -> exams.addExam("2026-2027", "8", "Half Yearly")).hasMessageContaining("already exists");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exam_config (school_id,session,class_name,exam_name) VALUES (?,'2026-2027','8','Half Yearly')", SCHOOL))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void assessmentGroupResultIsUniquePerStudentGroupSession() {
        Long group = jdbc.queryForObject("INSERT INTO assessment_group (school_id,session,class_name,name,group_type,display_order) " +
                "VALUES (?,'2026-2027','8','Term 1','EXAM_BASED',0) RETURNING id", Long.class, SCHOOL);
        String insert = "INSERT INTO assessment_group_result (school_id,student_id,assessment_group_id,session) VALUES (?,?,?,'2026-2027')";
        jdbc.update(insert, SCHOOL, A1, group);
        assertThatThrownBy(() -> jdbc.update(insert, SCHOOL, A1, group)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void markRequiresAnExistingSubjectEntry() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO student_mark (school_id,student_id,exam_subject_entry_id,marks_obtained,created_at,updated_at) " +
                "VALUES (?,?,-1,10,now(),now())", SCHOOL, A1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void subjectEntryWithMarksCannotBeDeletedAtTheDatabase() {
        save(A1, math, 80.0);
        em.flush();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exam_subject_entry WHERE id=?", math)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ─── Mark entry ──────────────────────────────────────────────────────

    @Test
    void bulkSaveIsAtomic() {
        assertThatThrownBy(() -> marks.bulkSaveMarks(List.of(req(A1, math, 80.0), req(A1, science, 150.0)), null))
                .isInstanceOf(MarkValidationException.class)
                .satisfies(e -> assertThat(((MarkValidationException) e).getErrors()).singleElement()
                        .satisfies(err -> {
                            assertThat(err.getIndex()).isEqualTo(1);
                            assertThat(err.getReason()).contains("between 0 and 100");
                        }));
        em.flush();
        assertThat(markCount()).isZero();   // the valid Math mark was not saved either

        var result = marks.bulkSaveMarks(List.of(req(A1, math, 80.0), req(A1, science, 70.0)), null);
        assertThat(result.getSaved()).isEqualTo(2);
        em.flush();
        assertThat(jdbc.queryForMap("SELECT created_by, updated_by, created_at IS NOT NULL c, updated_at IS NOT NULL u FROM student_mark WHERE student_id=? AND exam_subject_entry_id=?", A1, math))
                .containsEntry("created_by", "admin-res").containsEntry("updated_by", "admin-res").containsEntry("c", true).containsEntry("u", true);
    }

    @Test
    void validationRejectsWrongClassUnenrolledSubjectCrossSchoolAndDuplicates() {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,joining_date) VALUES (?,?,'Other','ACTIVE',?,'8',DATE '2026-04-01')",
                OTHER, OTHER_SCHOOL, OTHER_CLASS_8);
        Long otherSchoolEntry = jdbc.queryForObject("INSERT INTO exam_config (school_id,session,class_name,exam_name) VALUES (?,'2026-2027','8','X') RETURNING id", Long.class, OTHER_SCHOOL);
        Long otherEntry = jdbc.queryForObject("INSERT INTO exam_subject_entry (school_id,exam_config_id,subject_name,max_marks) VALUES (?,?,'Math',100) RETURNING id", Long.class, OTHER_SCHOOL, otherSchoolEntry);

        assertThatThrownBy(() -> marks.bulkSaveMarks(List.of(
                req(P9, math, 50.0),          // class 9 student in a class 8 exam
                req(A1, sanskrit, 40.0),      // A1 did not choose the Sanskrit elective
                req(A1, otherEntry, 50.0),    // another school's subject
                req("NOPE", math, 10.0),      // unknown student
                req(A3, math, 10.0), req(A3, math, 12.0)), null))  // duplicate
                .isInstanceOf(MarkValidationException.class)
                .satisfies(e -> {
                    Map<Integer, String> byIndex = ((MarkValidationException) e).getErrors().stream()
                            .collect(Collectors.toMap(err -> err.getIndex(), err -> err.getReason()));
                    assertThat(byIndex.get(0)).contains("not enrolled in class 8");
                    assertThat(byIndex.get(1)).contains("does not take Sanskrit");
                    assertThat(byIndex.get(2)).contains("not found in your school");
                    assertThat(byIndex.get(3)).contains("not enrolled");
                    assertThat(byIndex).doesNotContainKey(4);
                    assertThat(byIndex.get(5)).contains("more than once");
                });
        em.flush();
        assertThat(markCount()).isZero();
    }

    @Test
    void teacherIsLimitedToOwnClassSectionAndCurrentSession() {
        when(security.getRole()).thenReturn("TEACHER");
        when(security.getUsername()).thenReturn("teacher-a");
        when(classScope.resolveOwnScope("teacher-a", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope("8", SECTION_A, false));

        assertThatThrownBy(() -> marks.bulkSaveMarks(List.of(req(B1, math, 60.0)), null))
                .isInstanceOf(MarkValidationException.class).hasMessageContaining("not in your section");
        marks.bulkSaveMarks(List.of(req(A1, math, 60.0)), null);

        when(classScope.resolveOwnScope("teacher-a", SCHOOL)).thenReturn(new TeacherClassScopeService.TeacherScope("9", null, false));
        assertThatThrownBy(() -> marks.bulkSaveMarks(List.of(req(A1, science, 60.0)), null))
                .isInstanceOf(MarkValidationException.class).hasMessageContaining("your own class");

        when(security.getRole()).thenReturn("SUB_ADMIN");
        assertThatThrownBy(() -> marks.bulkSaveMarks(List.of(req(A1, science, 60.0)), null)).isInstanceOf(AccessDeniedException.class);
    }

    // ─── Publishing & visibility ─────────────────────────────────────────

    @Test
    void draftIsHiddenFromStudentsUntilPublishedAndLockedWhilePublished() {
        marks.bulkSaveMarks(List.of(req(A1, math, 80.0), req(A1, science, 70.0)), null);
        em.flush();
        assertThat(exams.getExams("2026-2027", "8")).singleElement()
                .satisfies(e -> assertThat(e.getResultStatus()).isEqualTo(ExamResultStatus.DRAFT));

        assertThat(marks.getStudentResults(A1, "2026-2027", false)).isEmpty();       // student/parent view
        assertThat(marks.getStudentResults(A1, "2026-2027", true)).hasSize(1);       // staff view

        exams.publishResults(examId, "ip");
        em.flush(); em.clear();
        assertThat(marks.getStudentResults(A1, "2026-2027", false)).singleElement()
                .satisfies(r -> assertThat(r.getResultStatus()).isEqualTo("PUBLISHED"));
        assertThatThrownBy(() -> marks.bulkSaveMarks(List.of(req(A1, math, 81.0)), null))
                .isInstanceOf(MarkValidationException.class).hasMessageContaining("published and locked");
        assertThatThrownBy(() -> exams.addExamSubject(examId, "Math2", 10, null)).isInstanceOf(IllegalStateException.class);

        exams.unpublishResults(examId, "ip");
        em.flush(); em.clear();
        assertThat(marks.getStudentResults(A1, "2026-2027", false)).isEmpty();
        marks.bulkSaveMarks(List.of(req(A1, math, 81.0)), null);
    }

    // ─── One calculation everywhere ──────────────────────────────────────

    @Test
    void percentageGradeAndRankAgreeAcrossClassResultsMyResultsAndReportCards() {
        // Section A: A1 = 150/200 (75%), A2 = 150+25 /250 (70%), A3 = 150/200 (75%, ties A1). B1 in its own section.
        marks.bulkSaveMarks(List.of(
                req(A1, math, 80.0), req(A1, science, 70.0),
                req(A2, math, 75.0), req(A2, science, 75.0), req(A2, sanskrit, 25.0),
                req(A3, math, 70.0), req(A3, science, 80.0),
                req(B1, math, 40.0), req(B1, science, 40.0)), null);
        em.flush(); em.clear();

        Map<String, ClassStudentResultDTO> byId = marks.getClassResults("8", examId, null).stream()
                .collect(Collectors.toMap(ClassStudentResultDTO::getStudentId, r -> r));
        assertThat(byId.get(A1).getPercentage()).isEqualTo(75.0);
        assertThat(byId.get(A2).getPercentage()).isEqualTo(70.0);
        assertThat(byId.get(A1).getRank()).isEqualTo(1);
        assertThat(byId.get(A3).getRank()).isEqualTo(1);            // tie shares the rank
        assertThat(byId.get(A2).getRank()).isEqualTo(3);            // competition ranking skips 2
        assertThat(byId.get(B1).getRank()).isEqualTo(1);            // ranked within section B only
        assertThat(byId.get(A1).getGrade()).isEqualTo(GradingPolicy.grade(75.0, "CBSE")).isEqualTo("B1");
        assertThat(byId.get(A1).getPassed()).isTrue();

        ExamResultDTO mine = marks.getStudentResults(A2, "2026-2027", true).get(0);
        assertThat(mine.getPercentage()).isEqualTo(70.0);
        assertThat(mine.getOverallRank()).isEqualTo(3);
        assertThat(mine.getGrade()).isEqualTo(byId.get(A2).getGrade());
        assertThat(mine.getSubjects()).extracting(s -> s.getSubjectName()).containsExactly("Math", "Science", "Sanskrit");

        Long group = jdbc.queryForObject("INSERT INTO assessment_group (school_id,session,class_name,name,group_type,display_order) " +
                "VALUES (?,'2026-2027','8','Term 1','EXAM_BASED',0) RETURNING id", Long.class, SCHOOL);
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, group, examId, BigDecimal.ONE);
        WeightedGroupResultDTO card = engine.computeForStudent(A2, group, "2026-2027");
        assertThat(card.getWeightedPercentage()).isEqualTo(70.0);
        assertThat(card.isComplete()).isTrue();
        assertThat(engine.computeAndRankForClass(List.of(A1, A2, A3), Map.of(), group, "2026-2027"))
                .extracting(r -> r.getRank()).containsExactly(1, 3, 1);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assessment_group_result", Long.class)).isZero(); // read-only
    }

    @Test
    void missingMarkMeansIncompleteNeverDroppedOrSilentZero() {
        marks.bulkSaveMarks(List.of(req(A1, math, 90.0), req(A3, math, 50.0), req(A3, science, 50.0)), null);  // A1 Science missing
        em.flush(); em.clear();

        ClassStudentResultDTO a1 = marks.getClassResults("8", examId, SECTION_A).stream()
                .filter(r -> r.getStudentId().equals(A1)).findFirst().orElseThrow();
        assertThat(a1.isComplete()).isFalse();
        assertThat(a1.getMarksMissing()).isEqualTo(1);
        assertThat(a1.getPercentage()).isNull();
        assertThat(a1.getRank()).isNull();
        assertThat(a1.getGrade()).isNull();
        assertThat(a1.getSubjects()).extracting(s -> s.getSubjectName()).contains("Science");   // not dropped
        assertThat(a1.getTotalMaxMarks()).isEqualTo(200.0);

        ExamResultDTO mine = marks.getStudentResults(A1, "2026-2027", true).get(0);
        assertThat(mine.getPercentage()).isNull();
        assertThat(mine.getMarksMissing()).isEqualTo(1);

        Long group = jdbc.queryForObject("INSERT INTO assessment_group (school_id,session,class_name,name,group_type,display_order) " +
                "VALUES (?,'2026-2027','8','Term 1','EXAM_BASED',0) RETURNING id", Long.class, SCHOOL);
        jdbc.update("INSERT INTO assessment_group_exam_mapping (school_id,assessment_group_id,exam_config_id,weightage,display_order) VALUES (?,?,?,?,0)",
                SCHOOL, group, examId, BigDecimal.ONE);
        WeightedGroupResultDTO card = engine.computeForStudent(A1, group, "2026-2027");
        assertThat(card.getMarksMissing()).isEqualTo(1);             // report card: explicit absent ("Ab")
        assertThat(card.getWeightedPercentage()).isEqualTo(45.0);    // 90 / 200, Science still counted
    }

    // ─── Delete safety ───────────────────────────────────────────────────

    @Test
    void examOrSubjectWithMarksCannotBeDeleted() {
        marks.bulkSaveMarks(List.of(req(A1, math, 80.0)), null);
        em.flush();
        assertThatThrownBy(() -> exams.deleteExam(examId)).isInstanceOf(IllegalStateException.class).hasMessageContaining("mark(s)");
        assertThatThrownBy(() -> exams.deleteExamSubject(math)).isInstanceOf(IllegalStateException.class).hasMessageContaining("mark(s)");
        ExamConfigService.BulkSubjectRequest keep = new ExamConfigService.BulkSubjectRequest();
        keep.subjectName = "Science";
        keep.maxMarks = 100;
        assertThatThrownBy(() -> exams.bulkSyncExamSubjects(examId, List.of(keep))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> exams.updateExamSubject(math, 50, null)).hasMessageContaining("highest mark");
        assertThat(markCount()).isOne();

        exams.deleteExamSubject(sanskrit);          // no marks: allowed
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_subject_entry WHERE id=?", Long.class, sanskrit)).isZero();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private void student(String id, String name, long classId, String className, Long sectionId, String sectionName) {
        jdbc.update("INSERT INTO student (student_id,school_id,name,status,class_id,class_name,section_id,section_name,joining_date) " +
                "VALUES (?,?,?,'ACTIVE',?,?,?,?,DATE '2026-04-01')", id, SCHOOL, name, classId, className, sectionId, sectionName);
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id," +
                "section_name_snapshot,status,effective_from) VALUES (?,?,?,?,?,?,?,'ACTIVE',DATE '2026-04-01')",
                SCHOOL, id, SESSION, classId, className, sectionId, sectionName);
    }

    private static MarkEntryRequest req(String studentId, long entryId, double value) {
        MarkEntryRequest r = new MarkEntryRequest();
        r.setStudentId(studentId);
        r.setExamSubjectEntryId(entryId);
        r.setMarksObtained(value);
        return r;
    }

    private void save(String studentId, long entryId, double value) {
        marks.bulkSaveMarks(List.of(req(studentId, entryId, value)), null);
    }

    private long markCount() {
        return jdbc.queryForObject("SELECT count(*) FROM student_mark WHERE school_id=?", Long.class, SCHOOL);
    }
}
