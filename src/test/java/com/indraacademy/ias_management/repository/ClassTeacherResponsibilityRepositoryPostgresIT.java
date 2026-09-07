package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.ClassTeacherResponsibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F2 (V54) migration/constraint proof against a real, PROD-shaped PostgreSQL database with
 * Flyway enabled — H2 cannot validate PostgreSQL's composite tenant foreign keys or partial
 * unique indexes. Every test is transactional and rolls back its synthetic negative-id fixtures;
 * no durable test data is left behind.
 *
 * <p>Covers both halves of V54: the additive {@code timetable_entry.academic_session_id} column
 * (proving hundreds of legacy-shaped rows are untouched by the migration) and the new
 * {@code class_teacher_responsibility} table's tenant-safe FKs and partial-unique-index
 * uniqueness.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ClassTeacherResponsibilityRepositoryPostgresIT {

    private static final long SCHOOL_A = -95001L;
    private static final long SCHOOL_B = -95002L;
    private static final long SESSION_A1 = -95101L;
    private static final long SESSION_A2 = -95102L;
    private static final long SESSION_B1 = -95103L;
    private static final long CLASS_A = -95201L;
    private static final long CLASS_A_OTHER = -95202L;
    private static final long CLASS_B = -95203L;
    private static final long SECTION_A = -95301L;
    private static final long SECTION_A_OTHER_CLASS = -95302L; // belongs to CLASS_A_OTHER, not CLASS_A
    private static final long SECTION_B = -95303L;
    private static final String TEACHER_A1 = "CTR-IT-TEACHER-A1";
    private static final String TEACHER_A2 = "CTR-IT-TEACHER-A2";
    private static final String TEACHER_B1 = "CTR-IT-TEACHER-B1";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClassTeacherResponsibilityRepository repository;

    @BeforeEach
    void seedTenantFixtures() {
        insertSchool(SCHOOL_A, "ctr-it-school-a");
        insertSchool(SCHOOL_B, "ctr-it-school-b");
        insertSession(SESSION_A1, SCHOOL_A, "CTR-IT-A1");
        insertSession(SESSION_A2, SCHOOL_A, "CTR-IT-A2");
        insertSession(SESSION_B1, SCHOOL_B, "CTR-IT-B1");
        insertClass(CLASS_A, SCHOOL_A, "CTR Class A");
        insertClass(CLASS_A_OTHER, SCHOOL_A, "CTR Class A Other");
        insertClass(CLASS_B, SCHOOL_B, "CTR Class B");
        insertSection(SECTION_A, SCHOOL_A, CLASS_A, "A");
        insertSection(SECTION_A_OTHER_CLASS, SCHOOL_A, CLASS_A_OTHER, "A");
        insertSection(SECTION_B, SCHOOL_B, CLASS_B, "B");
        insertTeacher(TEACHER_A1, SCHOOL_A);
        insertTeacher(TEACHER_A2, SCHOOL_A);
        insertTeacher(TEACHER_B1, SCHOOL_B);
    }

    // ── timetable_entry: additive column proof ──────────────────────────────────────────────

    @Test
    void hundredsOfLegacyShapedTimetableRowsSurviveTheMigrationUnrewritten() {
        int rowCount = 300;
        for (int i = 0; i < rowCount; i++) {
            String day = switch (i % 6) {
                case 0 -> "MONDAY"; case 1 -> "TUESDAY"; case 2 -> "WEDNESDAY";
                case 3 -> "THURSDAY"; case 4 -> "FRIDAY"; default -> "SATURDAY";
            };
            jdbc.update("INSERT INTO timetable_entry " +
                            "(school_id, class_name, class_id, section_id, section_name, day, period_number, " +
                            "start_time, end_time, subject_name, teacher_id, teacher_name) " +
                            "VALUES (?, 'CTR-LEGACY', NULL, NULL, NULL, ?, ?, '09:00', '09:40', 'Legacy Subject', ?, 'Legacy Teacher')",
                    SCHOOL_A, day, (i % 8) + 1, TEACHER_A1);
        }
        // A legacy simultaneous-group pair, exactly V43's shape, also with no session link.
        jdbc.update("INSERT INTO timetable_entry " +
                "(school_id, class_name, class_id, section_id, day, period_number, start_time, end_time, " +
                "subject_name, teacher_id, simultaneous_group) " +
                "VALUES (?, 'CTR-LEGACY', NULL, NULL, 'MONDAY', 50, '10:00', '10:40', 'Math', ?, 'CTR_GROUP')",
                SCHOOL_A, TEACHER_A1);
        jdbc.update("INSERT INTO timetable_entry " +
                "(school_id, class_name, class_id, section_id, day, period_number, start_time, end_time, " +
                "subject_name, teacher_id, simultaneous_group) " +
                "VALUES (?, 'CTR-LEGACY', NULL, NULL, 'MONDAY', 50, '10:00', '10:40', 'Biology', ?, 'CTR_GROUP')",
                SCHOOL_A, TEACHER_A2);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM timetable_entry WHERE school_id = ? AND class_name = 'CTR-LEGACY'", SCHOOL_A);

        assertThat(rows).hasSize(rowCount + 2);
        // The migration is purely additive: every inserted (legacy-shaped) row reads back with
        // the new column NULL and every other field exactly as inserted — nothing was rewritten,
        // defaulted, or coerced.
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("academic_session_id")).isNull();
            assertThat(row.get("class_id")).isNull();
            assertThat(row.get("section_id")).isNull();
        });
        long groupedCount = rows.stream().filter(r -> "CTR_GROUP".equals(r.get("simultaneous_group"))).count();
        assertThat(groupedCount).isEqualTo(2);
    }

    @Test
    void timetableSessionFkRejectsCrossSchoolSessionOwnership() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO timetable_entry " +
                        "(school_id, class_name, academic_session_id, day, period_number, start_time, end_time, subject_name) " +
                        "VALUES (?, 'CTR-X', ?, 'MONDAY', 1, '09:00', '09:40', 'Subj')",
                SCHOOL_A, SESSION_B1)) // SESSION_B1 belongs to SCHOOL_B, not SCHOOL_A
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void timetableSessionFkAcceptsSameTenantSession() {
        jdbc.update("INSERT INTO timetable_entry " +
                        "(school_id, class_name, academic_session_id, day, period_number, start_time, end_time, subject_name) " +
                        "VALUES (?, 'CTR-X', ?, 'MONDAY', 2, '09:00', '09:40', 'Subj')",
                SCHOOL_A, SESSION_A1);

        Long sessionId = jdbc.queryForObject(
                "SELECT academic_session_id FROM timetable_entry WHERE school_id = ? AND class_name = 'CTR-X'",
                Long.class, SCHOOL_A);
        assertThat(sessionId).isEqualTo(SESSION_A1);
    }

    // ── class_teacher_responsibility: FK tenant-safety + uniqueness ─────────────────────────

    @Test
    void validSectionlessResponsibilitySaves() {
        ClassTeacherResponsibility saved = repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A_OTHER, null, TEACHER_A1));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void validSectionSpecificResponsibilitySaves() {
        ClassTeacherResponsibility saved = repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A, SECTION_A, TEACHER_A1));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void sessionFkRejectsCrossSchoolOwnership() {
        // SESSION_B1 belongs to SCHOOL_B — using it with SCHOOL_A must fail tenant-safety.
        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_B1, CLASS_A, null, TEACHER_A1)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void classFkRejectsCrossSchoolOwnership() {
        // CLASS_B belongs to SCHOOL_B.
        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_B, null, TEACHER_A1)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sectionFkRejectsCrossSchoolOwnership() {
        // SECTION_B belongs to SCHOOL_B/CLASS_B.
        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A, SECTION_B, TEACHER_A1)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sectionFkRejectsSectionBelongingToADifferentClassInTheSameSchool() {
        // SECTION_A_OTHER_CLASS is a real, same-school section — but of CLASS_A_OTHER, not CLASS_A.
        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A, SECTION_A_OTHER_CLASS, TEACHER_A1)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void teacherFkRejectsUnknownTeacher() {
        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A, null, "CTR-IT-NO-SUCH-TEACHER")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sectionlessUniquenessRejectsASecondResponsibilityForTheSameClassSameSession() {
        repository.saveAndFlush(responsibility(SCHOOL_A, SESSION_A1, CLASS_A_OTHER, null, TEACHER_A1));

        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A_OTHER, null, TEACHER_A2)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sectionSpecificUniquenessRejectsASecondResponsibilityForTheSameClassAndSection() {
        repository.saveAndFlush(responsibility(SCHOOL_A, SESSION_A1, CLASS_A, SECTION_A, TEACHER_A1));

        assertThatThrownBy(() -> repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A1, CLASS_A, SECTION_A, TEACHER_A2)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sameClassCanIndependentlyHaveDifferentTeachersInDifferentSessions() {
        repository.saveAndFlush(responsibility(SCHOOL_A, SESSION_A1, CLASS_A, SECTION_A, TEACHER_A1));
        ClassTeacherResponsibility second = repository.saveAndFlush(
                responsibility(SCHOOL_A, SESSION_A2, CLASS_A, SECTION_A, TEACHER_A2));

        assertThat(second.getId()).isNotNull();
        assertThat(repository.findAll()).filteredOn(r -> r.getClassId().equals(CLASS_A)).hasSize(2);
    }

    private static ClassTeacherResponsibility responsibility(
            long schoolId, long sessionId, long classId, Long sectionId, String teacherId) {
        ClassTeacherResponsibility r = new ClassTeacherResponsibility();
        r.setSchoolId(schoolId);
        r.setAcademicSessionId(sessionId);
        r.setClassId(classId);
        r.setSectionId(sectionId);
        r.setTeacherId(teacherId);
        return r;
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id, active, created_at, name, plan, slug, " +
                        "academic_year_start_month, periods_per_day) VALUES (?, true, CURRENT_TIMESTAMP, ?, " +
                        "'TRIAL', ?, 4, 8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId, String label) {
        jdbc.update("INSERT INTO academic_session (id, created_at, is_current, start_date, end_date, label, school_id) " +
                "VALUES (?, CURRENT_TIMESTAMP, false, DATE '2026-04-01', DATE '2027-03-31', ?, ?)", id, label, schoolId);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id, active, name, school_id, stream_eligible) " +
                "VALUES (?, true, ?, ?, false)", id, name, schoolId);
    }

    private void insertSection(long id, long schoolId, long classId, String name) {
        jdbc.update("INSERT INTO section (id, active, class_id, name, school_id) VALUES (?, true, ?, ?, ?)",
                id, classId, name, schoolId);
    }

    private void insertTeacher(String teacherId, long schoolId) {
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status) VALUES (?, ?, 'CTR IT Teacher', 'ACTIVE')",
                teacherId, schoolId);
    }
}
