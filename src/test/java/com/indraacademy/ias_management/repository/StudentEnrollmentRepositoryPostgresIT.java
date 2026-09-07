package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.StudentEnrollment;
import com.indraacademy.ias_management.entity.StudentEnrollmentClosureReason;
import com.indraacademy.ias_management.entity.StudentEnrollmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the configured real PostgreSQL database with Flyway enabled. Every test is
 * transactional and rolls back its synthetic negative-id fixtures; no enrollment backfill or
 * durable test data is created. H2 is deliberately not used because it cannot validate the
 * PostgreSQL GiST exclusion constraint or composite tenant foreign keys.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentEnrollmentRepositoryPostgresIT {

    private static final long SCHOOL_1 = -91001L;
    private static final long SCHOOL_2 = -91002L;
    private static final long SESSION_1 = -92001L;
    private static final long SESSION_2 = -92002L;
    private static final long CLASS_1 = -93001L;
    private static final long CLASS_1_OTHER = -93002L;
    private static final long CLASS_2 = -93003L;
    private static final long SECTION_1 = -94001L;
    private static final long SECTION_2 = -94002L;
    private static final String STUDENT_1 = "ENR-POSTGRES-S1";
    private static final String STUDENT_2 = "ENR-POSTGRES-S2";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StudentEnrollmentRepository repository;

    @BeforeEach
    void seedTenantFixtures() {
        insertSchool(SCHOOL_1, "enrollment-it-one");
        insertSchool(SCHOOL_2, "enrollment-it-two");
        insertSession(SESSION_1, SCHOOL_1);
        insertSession(SESSION_2, SCHOOL_2);
        insertClass(CLASS_1, SCHOOL_1, "Class One");
        insertClass(CLASS_1_OTHER, SCHOOL_1, "Class Other");
        insertClass(CLASS_2, SCHOOL_2, "Class Two");
        insertSection(SECTION_1, SCHOOL_1, CLASS_1, "A");
        insertSection(SECTION_2, SCHOOL_2, CLASS_2, "B");
        insertStudent(STUDENT_1, SCHOOL_1, CLASS_1);
        insertStudent(STUDENT_2, SCHOOL_2, CLASS_2);
    }

    @Test
    void validEnrollmentAndTenantScopedQueriesWork() {
        StudentEnrollment saved = repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1,
                SECTION_1, LocalDate.of(2026, 4, 1)));

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL_1, STUDENT_1, SESSION_1)).hasSize(1);
        assertThat(repository.findEffectiveEnrollment(SCHOOL_1, STUDENT_1, SESSION_1,
                LocalDate.of(2026, 4, 1))).contains(saved);
        assertThat(repository.findByIdAndSchoolId(saved.getId(), SCHOOL_2)).isEmpty();
    }

    @Test
    void sameStudentMayHaveTwoNonOverlappingSegmentsInOneSession() {
        repository.saveAndFlush(closed(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 8, 10)));
        repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1_OTHER,
                null, LocalDate.of(2026, 8, 11)));

        assertThat(repository.findBySchoolIdAndStudentIdAndAcademicSessionIdOrderByEffectiveFromAsc(
                SCHOOL_1, STUDENT_1, SESSION_1)).hasSize(2);
    }

    @Test
    void inclusiveEndDateOverlapsAnotherSegmentStartingOnThatSameDate() {
        repository.saveAndFlush(closed(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 8, 10)));

        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_1_OTHER, null, LocalDate.of(2026, 8, 10))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void overlappingClosedSegmentsAreRejected() {
        repository.saveAndFlush(closed(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 8, 10)));

        assertThatThrownBy(() -> repository.saveAndFlush(closed(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_1_OTHER, null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoOpenSegmentsAreRejected() {
        repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1)));

        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_1_OTHER, null, LocalDate.of(2026, 8, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossSchoolStudentIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_2, STUDENT_1, SESSION_2,
                CLASS_2, SECTION_2, LocalDate.of(2026, 4, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossSchoolSessionIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_2,
                CLASS_1, SECTION_1, LocalDate.of(2026, 4, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void crossSchoolClassIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_2, null, LocalDate.of(2026, 4, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sectionFromWrongSchoolIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_1, SECTION_2, LocalDate.of(2026, 4, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sectionBelongingToAnotherClassIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_1_OTHER, SECTION_1, LocalDate.of(2026, 4, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invalidDateRangeIsRejected() {
        assertThatThrownBy(() -> repository.saveAndFlush(closed(SCHOOL_1, STUDENT_1, SESSION_1,
                CLASS_1, SECTION_1, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void activeEnrollmentCannotHaveAnEndDateOrClosureReason() {
        StudentEnrollment invalid = active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1));
        invalid.setEffectiveUntil(LocalDate.of(2026, 8, 1));
        invalid.setClosureReason(StudentEnrollmentClosureReason.CLASS_CHANGE);

        assertThatThrownBy(() -> repository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void closedEnrollmentRequiresEndDateAndClosureReason() {
        StudentEnrollment invalid = active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1));
        invalid.setStatus(StudentEnrollmentStatus.CLOSED);

        assertThatThrownBy(() -> repository.saveAndFlush(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void referencedClassCannotBeDeleted() {
        repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1)));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM school_class WHERE id = ?", CLASS_1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schoolRosterQueriesCannotLeakAnotherTenant() {
        repository.saveAndFlush(active(SCHOOL_1, STUDENT_1, SESSION_1, CLASS_1, SECTION_1,
                LocalDate.of(2026, 4, 1)));
        repository.saveAndFlush(active(SCHOOL_2, STUDENT_2, SESSION_2, CLASS_2, SECTION_2,
                LocalDate.of(2026, 4, 1)));

        List<StudentEnrollment> tenantOne = repository
                .findBySchoolIdAndAcademicSessionIdAndClassIdAndSectionIdOrderByEffectiveFromAsc(
                        SCHOOL_1, SESSION_1, CLASS_1, SECTION_1);

        assertThat(tenantOne).extracting(StudentEnrollment::getStudentId).containsExactly(STUDENT_1);
    }

    private StudentEnrollment active(long schoolId, String studentId, long sessionId, long classId,
                                     Long sectionId, LocalDate from) {
        StudentEnrollment enrollment = base(schoolId, studentId, sessionId, classId, sectionId, from);
        enrollment.setStatus(StudentEnrollmentStatus.ACTIVE);
        return enrollment;
    }

    private StudentEnrollment closed(long schoolId, String studentId, long sessionId, long classId,
                                     Long sectionId, LocalDate from, LocalDate until) {
        StudentEnrollment enrollment = base(schoolId, studentId, sessionId, classId, sectionId, from);
        enrollment.setStatus(StudentEnrollmentStatus.CLOSED);
        enrollment.setEffectiveUntil(until);
        enrollment.setClosureReason(StudentEnrollmentClosureReason.CLASS_CHANGE);
        return enrollment;
    }

    private StudentEnrollment base(long schoolId, String studentId, long sessionId, long classId,
                                   Long sectionId, LocalDate from) {
        StudentEnrollment enrollment = new StudentEnrollment();
        enrollment.setSchoolId(schoolId);
        enrollment.setStudentId(studentId);
        enrollment.setAcademicSessionId(sessionId);
        enrollment.setClassId(classId);
        enrollment.setClassNameSnapshot(classId == CLASS_1_OTHER ? "Class Other" : "Class Snapshot");
        enrollment.setSectionId(sectionId);
        enrollment.setSectionNameSnapshot(sectionId == null ? null : "Section Snapshot");
        enrollment.setEffectiveFrom(from);
        return enrollment;
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id, active, created_at, name, plan, slug, " +
                        "academic_year_start_month, periods_per_day) VALUES (?, true, CURRENT_TIMESTAMP, ?, " +
                        "'TRIAL', ?, 4, 8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId) {
        jdbc.update("INSERT INTO academic_session (id, created_at, is_current, start_date, end_date, label, school_id) " +
                        "VALUES (?, CURRENT_TIMESTAMP, false, DATE '2026-04-01', DATE '2027-03-31', ?, ?)",
                id, "IT-" + Math.abs(id), schoolId);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id, active, name, school_id, stream_eligible) " +
                "VALUES (?, true, ?, ?, false)", id, name, schoolId);
    }

    private void insertSection(long id, long schoolId, long classId, String name) {
        jdbc.update("INSERT INTO section (id, active, class_id, name, school_id) VALUES (?, true, ?, ?, ?)",
                id, classId, name, schoolId);
    }

    private void insertStudent(String studentId, long schoolId, long classId) {
        jdbc.update("INSERT INTO student (student_id, school_id, class_id, class_name, status) " +
                "VALUES (?, ?, ?, 'Class Snapshot', 'ACTIVE')", studentId, schoolId, classId);
    }
}
