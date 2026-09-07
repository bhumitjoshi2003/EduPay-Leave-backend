package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.repository.StudentRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** PostgreSQL-only coverage for V53's restrictive section-history relationship. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SectionService.class)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class SectionDeletionPostgresIT {

    private static final long SCHOOL = -99501L;
    private static final long OTHER_SCHOOL = -99502L;
    private static final long SESSION = -99503L;
    private static final long CLASS = -99504L;
    private static final long OTHER_CLASS = -99505L;
    private static final long SECTION = -99506L;
    private static final long FREE_SECTION = -99507L;
    private static final String STUDENT = "SECTION-DELETE-PG";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired SectionService service;
    @Autowired StudentRepository students;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean ObjectMapper objectMapper;

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @BeforeEach
    void fixtures() {
        when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES (?,true,CURRENT_TIMESTAMP,'Section IT','TRIAL','section-it',4,8,'Asia/Kolkata'),(?,true,CURRENT_TIMESTAMP,'Other Section IT','TRIAL','other-section-it',4,8,'Asia/Kolkata')", SCHOOL, OTHER_SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES (?,?,'2026-2027',DATE '2026-04-01',DATE '2027-03-31',false,CURRENT_TIMESTAMP)", SESSION, SCHOOL);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,stream_eligible) VALUES (?,?, '10',true,false),(?,?,'11',true,false)", CLASS, SCHOOL, OTHER_CLASS, SCHOOL);
        jdbc.update("INSERT INTO section (id,school_id,class_id,name,active) VALUES (?,?,?,'A',true),(?,?,?,'B',true)", SECTION, SCHOOL, CLASS, FREE_SECTION, SCHOOL, CLASS);
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,section_id,section_name,joining_date) VALUES (?,?,'ACTIVE',?,'10',?,'A',DATE '2026-04-01')", STUDENT, SCHOOL, CLASS, SECTION);
    }

    @AfterEach
    void removeAnyCommittedFixtures() {
        jdbc.update("DELETE FROM timetable_entry WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM teacher_class_grant WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM teacher WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?,?)", SCHOOL, OTHER_SCHOOL);
    }

    @Test
    void activeEnrollmentBlocksDeletionAndPreservesProjectionAndHistory() {
        insertEnrollment("ACTIVE", null, null);
        assertBlockedAndUnchanged();
    }

    @Test
    void plannedEnrollmentBlocksDeletionAndPreservesProjectionAndHistory() {
        jdbc.update("UPDATE student SET status='UPCOMING' WHERE school_id=? AND student_id=?", SCHOOL, STUDENT);
        insertEnrollment("PLANNED", null, null);
        assertBlockedAndUnchanged();
    }

    @Test
    void closedHistoricalEnrollmentBlocksDeletionAndPreservesHistory() {
        insertEnrollment("CLOSED", "2026-08-31", "SECTION_CHANGE");
        assertBlockedAndUnchanged();
    }

    @Test
    void legacyUncoveredStudentIsClearedAndSectionDeletionSucceeds() {
        assertThat(service.deleteSection(SECTION, request)).isEqualTo(1);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM section WHERE school_id=? AND id=?", Integer.class, SCHOOL, SECTION)).isZero();
        assertThat(jdbc.queryForMap("SELECT section_id,section_name FROM student WHERE school_id=? AND student_id=?", SCHOOL, STUDENT))
                .containsEntry("section_id", null).containsEntry("section_name", null);
    }

    @Test
    void unrelatedSectionDeletionSucceedsWithoutChangingStudent() {
        assertThat(service.deleteSection(FREE_SECTION, request)).isZero();
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM section WHERE school_id=? AND id=?", Integer.class, SCHOOL, FREE_SECTION)).isZero();
        assertThat(jdbc.queryForObject("SELECT section_id FROM student WHERE school_id=? AND student_id=?", Long.class, SCHOOL, STUDENT)).isEqualTo(SECTION);
    }

    @Test
    void otherTenantCannotDeleteSection() {
        when(securityUtil.getSchoolId()).thenReturn(OTHER_SCHOOL);
        assertThatThrownBy(() -> service.deleteSection(SECTION, request))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Section not found.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM section WHERE school_id=? AND id=?", Integer.class, SCHOOL, SECTION)).isOne();
        assertThat(jdbc.queryForObject("SELECT section_id FROM student WHERE school_id=? AND student_id=?", Long.class, SCHOOL, STUDENT)).isEqualTo(SECTION);
    }

    @Test
    void directCleanupMethodsSkipEnrollmentBackedStudentsButRetainLegacyFallback() {
        insertEnrollment("ACTIVE", null, null);
        jdbc.update("UPDATE student SET class_id=?,class_name='11' WHERE school_id=? AND student_id=?", OTHER_CLASS, SCHOOL, STUDENT);
        assertThat(students.clearOrphanedSections(SCHOOL)).isZero();
        entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT section_id FROM student WHERE school_id=? AND student_id=?", Long.class, SCHOOL, STUDENT)).isEqualTo(SECTION);

        jdbc.update("DELETE FROM student_enrollment WHERE school_id=? AND student_id=?", SCHOOL, STUDENT);
        assertThat(students.clearOrphanedSections(SCHOOL)).isOne();
        entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT section_id FROM student WHERE school_id=? AND student_id=?", Long.class, SCHOOL, STUDENT)).isNull();
    }

    @Test
    void timetableAndTeacherReferencesRetainExistingNonFkBehavior() {
        jdbc.update("INSERT INTO teacher (teacher_id,school_id,name,status,class_teacher,class_teacher_section_id) VALUES ('SECTION-TEACHER-PG',?,'Teacher','ACTIVE','10',?)", SCHOOL, FREE_SECTION);
        jdbc.update("INSERT INTO teacher_class_grant (school_id,teacher_id,class_name,class_id,section_id,section_name,created_at) VALUES (?,'SECTION-TEACHER-PG','10',?,?,'B',CURRENT_TIMESTAMP)", SCHOOL, CLASS, FREE_SECTION);
        jdbc.update("INSERT INTO timetable_entry (school_id,class_name,class_id,section_id,section_name,day,period_number,start_time,end_time,subject_name) VALUES (?,'10',?,?,'B','MONDAY',99,'20:00','20:30','Synthetic')", SCHOOL, CLASS, FREE_SECTION);

        assertThat(service.deleteSection(FREE_SECTION, request)).isZero();
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT class_teacher_section_id FROM teacher WHERE school_id=? AND teacher_id='SECTION-TEACHER-PG'", Long.class, SCHOOL)).isEqualTo(FREE_SECTION);
        assertThat(jdbc.queryForObject("SELECT section_id FROM teacher_class_grant WHERE school_id=? AND teacher_id='SECTION-TEACHER-PG'", Long.class, SCHOOL)).isEqualTo(FREE_SECTION);
        assertThat(jdbc.queryForObject("SELECT section_id FROM timetable_entry WHERE school_id=? AND subject_name='Synthetic'", Long.class, SCHOOL)).isEqualTo(FREE_SECTION);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void transactionRollsBackLegacyProjectionClearWhenLaterAuditFails() {
        doThrow(new IllegalStateException("synthetic audit failure")).when(auditService)
                .log(any(), any(), any(), any(), any(), any(), any(), any());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> service.deleteSection(SECTION, request)))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic audit failure");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM section WHERE school_id=? AND id=?", Integer.class, SCHOOL, SECTION)).isOne();
        assertThat(jdbc.queryForObject("SELECT section_id FROM student WHERE school_id=? AND student_id=?", Long.class, SCHOOL, STUDENT)).isEqualTo(SECTION);
    }

    private void insertEnrollment(String status, String until, String reason) {
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,section_id,section_name_snapshot,status,effective_from,effective_until,closure_reason) VALUES (?,?,?,?,?,?,? ,?,DATE '2026-04-01',?::date,?)",
                SCHOOL, STUDENT, SESSION, CLASS, "10", SECTION, "A", status, until, reason);
    }

    private void assertBlockedAndUnchanged() {
        assertThatThrownBy(() -> service.deleteSection(SECTION, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enrollment history references it");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM section WHERE school_id=? AND id=?", Integer.class, SCHOOL, SECTION)).isOne();
        assertThat(jdbc.queryForObject("SELECT section_id FROM student WHERE school_id=? AND student_id=?", Long.class, SCHOOL, STUDENT)).isEqualTo(SECTION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM student_enrollment WHERE school_id=? AND student_id=? AND section_id=?", Integer.class, SCHOOL, STUDENT, SECTION)).isOne();
    }
}
