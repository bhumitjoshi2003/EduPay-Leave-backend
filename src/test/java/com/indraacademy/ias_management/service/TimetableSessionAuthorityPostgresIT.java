package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.TimetableEntry;
import com.indraacademy.ias_management.repository.TimetableRepository;
import com.indraacademy.ias_management.service.TimetableSessionCopyWorker.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F3 (V55) migration/constraint proof, plus TimetableSessionCopyWorker's real-DB behavior,
 * against a real, PROD-shaped PostgreSQL database with Flyway enabled — H2 cannot validate
 * PostgreSQL's partial unique indexes or composite tenant foreign keys.
 *
 * <p>Fixtures are committed (via {@link TestTransaction#flagForCommit()}/{@code end()}) rather
 * than left in the default rolled-back test transaction: {@link TimetableSessionCopyWorker#attempt}
 * runs in its own {@code REQUIRES_NEW} transaction — exactly the per-row isolation Phase F3
 * requires in production — which under read-committed isolation cannot see this test's fixture
 * rows unless they are actually committed first. Cleanup is therefore explicit in
 * {@link #cleanUpCommittedFixtures()} rather than automatic.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TimetableValidationService.class, TimetableSessionCopyWorker.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class TimetableSessionAuthorityPostgresIT {

    private static final long SCHOOL = -96001L;
    private static final long SESSION_SOURCE = -96101L;
    private static final long SESSION_TARGET = -96102L;
    private static final long OTHER_SCHOOL = -96002L;
    private static final long OTHER_SCHOOL_SESSION = -96103L;
    private static final long CLASS_NO_SECTION = -96201L;
    private static final long CLASS_WITH_SECTION = -96202L;
    private static final long SECTION = -96301L;
    private static final String TEACHER_ACTIVE = "TT-IT-ACTIVE";
    private static final String TEACHER_LEFT = "TT-IT-LEFT";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TimetableRepository timetableRepository;
    @Autowired private TimetableSessionCopyWorker worker;

    @BeforeEach
    void seedTenantFixtures() {
        insertSchool(SCHOOL, "tt-it-school");
        insertSchool(OTHER_SCHOOL, "tt-it-other-school");
        insertSession(SESSION_SOURCE, SCHOOL, "TT-IT-SOURCE");
        insertSession(SESSION_TARGET, SCHOOL, "TT-IT-TARGET");
        insertSession(OTHER_SCHOOL_SESSION, OTHER_SCHOOL, "TT-IT-OTHER");
        insertClass(CLASS_NO_SECTION, SCHOOL, "TT Class No Section");
        insertClass(CLASS_WITH_SECTION, SCHOOL, "TT Class With Section");
        insertSection(SECTION, SCHOOL, CLASS_WITH_SECTION, "A");
        insertTeacher(TEACHER_ACTIVE, SCHOOL, "ACTIVE");
        insertTeacher(TEACHER_LEFT, SCHOOL, "LEFT");

        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        jdbc.update("DELETE FROM timetable_entry WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM teacher WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
    }

    // ── V55 constraint proofs ────────────────────────────────────────────────────────────────

    @Test
    void ungroupedSectionlessDuplicateSlotInSameSession_rejected() {
        insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION, null, "MONDAY", 1, null);

        assertThatThrownBy(() -> insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION, null, "MONDAY", 1, null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void ungroupedSectionSpecificDuplicateSlotInSameSession_rejected() {
        insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_WITH_SECTION, SECTION, "MONDAY", 1, null);

        assertThatThrownBy(() -> insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_WITH_SECTION, SECTION, "MONDAY", 1, null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void identicalSlotInADifferentSession_allowedAtTheDbLevel() {
        insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION, null, "MONDAY", 1, null);

        // Same school/class/day/period, but a different session — must not collide.
        insertTimetableEntry(SCHOOL, SESSION_TARGET, CLASS_NO_SECTION, null, "MONDAY", 1, null);

        List<Long> sessions = jdbc.queryForList(
                "SELECT academic_session_id FROM timetable_entry WHERE school_id=? AND class_id=? AND day='MONDAY' AND period_number=1",
                Long.class, SCHOOL, CLASS_NO_SECTION);
        assertThat(sessions).containsExactlyInAnyOrder(SESSION_SOURCE, SESSION_TARGET);
    }

    @Test
    void groupedSimultaneousOccupants_allowedToShareTheSameSlot() {
        insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION, null, "MONDAY", 2, "TT_GROUP");
        insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION, null, "MONDAY", 2, "TT_GROUP");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=? AND class_id=? AND day='MONDAY' AND period_number=2",
                Integer.class, SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void legacyNullSessionRows_neverCollideWithTheUniqueIndexes() {
        // Exactly PROD's 640-row shape: no session, no canonical class_id.
        jdbc.update("INSERT INTO timetable_entry (school_id, class_name, day, period_number, start_time, end_time, subject_name) " +
                "VALUES (?, 'Legacy', 'MONDAY', 1, '09:00', '09:40', 'Legacy Subject')", SCHOOL);
        jdbc.update("INSERT INTO timetable_entry (school_id, class_name, day, period_number, start_time, end_time, subject_name) " +
                "VALUES (?, 'Legacy', 'MONDAY', 1, '09:00', '09:40', 'Legacy Subject 2')", SCHOOL);
        // Two legacy rows, identical class_name/day/period, both NULL session and NULL class_id
        // — the partial indexes require academic_session_id IS NOT NULL, so neither is covered.

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND class_name='Legacy' AND academic_session_id IS NULL",
                Integer.class, SCHOOL);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void sessionFkRejectsCrossSchoolOwnership() {
        assertThatThrownBy(() -> insertTimetableEntry(SCHOOL, OTHER_SCHOOL_SESSION, CLASS_NO_SECTION, null, "MONDAY", 1, null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void classFkRejectsUnknownClass() {
        assertThatThrownBy(() -> insertTimetableEntry(SCHOOL, SESSION_SOURCE, -999999L, null, "MONDAY", 1, null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sectionFkRejectsSectionBelongingToADifferentClass() {
        assertThatThrownBy(() -> insertTimetableEntry(SCHOOL, SESSION_SOURCE, CLASS_NO_SECTION, SECTION, "MONDAY", 1, null))
                .isInstanceOf(DataAccessException.class);
    }

    // ── TimetableSessionCopyWorker: real-DB per-row behavior ────────────────────────────────

    @Test
    void copyWorker_copiesAnOrdinaryRowIntoTheTargetSession() {
        TimetableEntry source = savedSource(CLASS_NO_SECTION, null, "MONDAY", 1, TEACHER_ACTIVE, null);

        var evaluation = worker.attempt(SCHOOL, source, SESSION_TARGET);

        assertThat(evaluation.outcome()).isEqualTo(Outcome.COPIED);
        assertThat(evaluation.entry().getId()).isNotEqualTo(source.getId());
        assertThat(evaluation.entry().getAcademicSessionId()).isEqualTo(SESSION_TARGET);
    }

    @Test
    void copyWorker_reRunIsIdempotent_reportsAlreadyCopiedNotADuplicate() {
        TimetableEntry source = savedSource(CLASS_NO_SECTION, null, "MONDAY", 1, TEACHER_ACTIVE, null);

        var first = worker.attempt(SCHOOL, source, SESSION_TARGET);
        var second = worker.attempt(SCHOOL, source, SESSION_TARGET);

        assertThat(first.outcome()).isEqualTo(Outcome.COPIED);
        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_COPIED);
        assertThat(second.entry().getId()).isEqualTo(first.entry().getId());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=? AND class_id=? AND day='MONDAY' AND period_number=1",
                Integer.class, SCHOOL, SESSION_TARGET, CLASS_NO_SECTION);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void copyWorker_leftTeacher_skippedNotSilentlyReassigned() {
        TimetableEntry source = savedSource(CLASS_NO_SECTION, null, "MONDAY", 1, TEACHER_LEFT, null);

        var evaluation = worker.attempt(SCHOOL, source, SESSION_TARGET);

        assertThat(evaluation.outcome()).isEqualTo(Outcome.SKIPPED_INELIGIBLE_TEACHER);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_TARGET);
        assertThat(count).isZero();
    }

    @Test
    void copyWorker_conflictingExistingTargetRow_reportedNotOverwritten() {
        TimetableEntry source = savedSource(CLASS_NO_SECTION, null, "MONDAY", 1, TEACHER_ACTIVE, null);
        // A DIFFERENT, pre-existing occupant already sits in that exact target slot.
        insertTimetableEntry(SCHOOL, SESSION_TARGET, CLASS_NO_SECTION, null, "MONDAY", 1, null);

        var evaluation = worker.attempt(SCHOOL, source, SESSION_TARGET);

        assertThat(evaluation.outcome()).isEqualTo(Outcome.CONFLICT);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=? AND class_id=? AND day='MONDAY' AND period_number=1",
                Integer.class, SCHOOL, SESSION_TARGET, CLASS_NO_SECTION);
        assertThat(count).isEqualTo(1); // still just the pre-existing row — never overwritten
    }

    @Test
    void copyWorker_simultaneousTagCopiedVerbatim_includingQuestionableSoloTags() {
        // Mirrors PROD's own orphaned "sg-c0c201ce96"-style tag — a group value with no partner —
        // copied as-is, never reinterpreted or cleared.
        TimetableEntry source = savedSource(CLASS_NO_SECTION, null, "MONDAY", 1, TEACHER_ACTIVE, "sg-questionable-tag");

        var evaluation = worker.attempt(SCHOOL, source, SESSION_TARGET);

        assertThat(evaluation.outcome()).isEqualTo(Outcome.COPIED);
        assertThat(evaluation.entry().getSimultaneousGroup()).isEqualTo("sg-questionable-tag");
    }

    @Test
    void copyWorker_invalidClass_reportedAndSkipped() {
        TimetableEntry source = savedSource(CLASS_NO_SECTION, null, "MONDAY", 1, TEACHER_ACTIVE, null);
        source.setClassId(-999999L); // simulate a class deleted since the source session was configured

        var evaluation = worker.attempt(SCHOOL, source, SESSION_TARGET);

        assertThat(evaluation.outcome()).isEqualTo(Outcome.SKIPPED_INVALID_CLASS);
    }

    private TimetableEntry savedSource(Long classId, Long sectionId, String day, int period, String teacherId, String group) {
        long id = insertTimetableEntry(SCHOOL, SESSION_SOURCE, classId, sectionId, day, period, group);
        TimetableEntry e = timetableRepository.findById(id).orElseThrow();
        e.setTeacherId(teacherId);
        return timetableRepository.saveAndFlush(e);
    }

    private long insertTimetableEntry(long schoolId, long sessionId, long classId, Long sectionId,
            String day, int period, String simultaneousGroup) {
        Long id = jdbc.queryForObject(
                "INSERT INTO timetable_entry (school_id, academic_session_id, class_id, class_name, section_id, day, " +
                        "period_number, start_time, end_time, subject_name, simultaneous_group) " +
                        "VALUES (?, ?, ?, 'TT Class', ?, ?, ?, '09:00', '09:40', 'TT Subject', ?) RETURNING id",
                Long.class, schoolId, sessionId, classId, sectionId, day, period, simultaneousGroup);
        return id;
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

    private void insertTeacher(String teacherId, long schoolId, String status) {
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status) VALUES (?, ?, 'TT IT Teacher', ?)",
                teacherId, schoolId, status);
    }
}
