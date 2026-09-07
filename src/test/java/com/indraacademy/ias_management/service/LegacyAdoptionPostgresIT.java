package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.AdoptionOutcome;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.InvariantSnapshot;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.ResponsibilityAdoptionReport;
import com.indraacademy.ias_management.dto.LegacyAdoptionDtos.TimetableAdoptionReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F5A real-PostgreSQL proof: dry-run non-mutation, real-run atomicity/idempotency, and
 * classification correctness against a PROD-shaped fixture (a sectionless class family and a
 * sectioned class family, hundreds of legacy NULL-session rows, a live class-teacher assignment)
 * that a unit test with mocked repositories cannot validate — real FK/unique-index enforcement
 * and real transactional rollback.
 *
 * <p>Fixtures are committed (see {@code ClassTeacherActivationPostgresIT}/
 * {@code TimetableSessionAuthorityPostgresIT} for the same rationale): the workers' entry points
 * run in their own transactions and must see this test's rows as already-committed state.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({LegacyTimetableAdoptionWorker.class, LegacyTimetableAdoptionService.class,
        LegacyResponsibilityAdoptionWorker.class, LegacyResponsibilityAdoptionService.class,
        LegacyAdoptionInvariantService.class, TimetableSessionAccessService.class,
        com.indraacademy.ias_management.config.ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class LegacyAdoptionPostgresIT {

    private static final long SCHOOL = -97501L;
    private static final long SESSION_TARGET = -97601L;
    private static final long SESSION_OTHER = -97602L;
    private static final long CLASS_NO_SECTION = -97701L;
    private static final long CLASS_NO_SECTION_2 = -97702L;
    private static final long CLASS_NO_SECTION_3 = -97703L;
    private static final long CLASS_WITH_SECTION = -97704L;
    private static final long SECTION_A = -97801L;
    private static final long SECTION_B = -97802L;
    private static final String TEACHER_PLAIN = "LA-IT-PLAIN";
    private static final String TEACHER_CT = "LA-IT-CT";
    private static final String TEACHER_OTHER = "LA-IT-OTHER";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LegacyTimetableAdoptionService timetableAdoptionService;
    @Autowired private LegacyResponsibilityAdoptionService responsibilityAdoptionService;
    @Autowired private LegacyAdoptionInvariantService invariantService;

    @BeforeEach
    void seedTenantSkeleton() {
        insertSchool(SCHOOL, "la-it-school");
        insertSession(SESSION_TARGET, SCHOOL, "LA-IT-TARGET", true);
        insertSession(SESSION_OTHER, SCHOOL, "LA-IT-OTHER-SESSION", false);
        insertClass(CLASS_NO_SECTION, SCHOOL, "LA Class 1");
        insertClass(CLASS_NO_SECTION_2, SCHOOL, "LA Class 2");
        insertClass(CLASS_NO_SECTION_3, SCHOOL, "LA Class 3");
        insertClass(CLASS_WITH_SECTION, SCHOOL, "LA Class 11");
        insertSection(SECTION_A, SCHOOL, CLASS_WITH_SECTION, "A");
        insertSection(SECTION_B, SCHOOL, CLASS_WITH_SECTION, "B");
        insertTeacher(TEACHER_PLAIN, SCHOOL, "ACTIVE", null, null);
        insertTeacher(TEACHER_CT, SCHOOL, "ACTIVE", "LA Class 1", null);
        insertTeacher(TEACHER_OTHER, SCHOOL, "ACTIVE", null, null);

        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        jdbc.update("DELETE FROM timetable_entry WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM class_teacher_activation WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM class_teacher_responsibility WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM section WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM teacher WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id=?", SCHOOL);
    }

    // ── scale fixture: hundreds of legacy rows, dry-run non-mutation ───────────────────────

    @Test
    void dryRun_hundredsOfLegacyRows_classifiesAllSafe_andWritesNothing() {
        int expectedRows = seedHundredsOfSafeLegacyRows();

        InvariantSnapshot before = invariantService.capture(SCHOOL, SESSION_TARGET);

        TimetableAdoptionReport report = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, true);

        assertThat(report.scanned()).isEqualTo(expectedRows);
        assertThat(report.safe()).isEqualTo(expectedRows);
        assertThat(report.invalidOrConflicting()).isZero();
        assertThat(report.requiresAdminConfirmation()).isZero();
        assertThat(report.adopted()).isZero(); // dry run never writes

        InvariantSnapshot after = invariantService.capture(SCHOOL, SESSION_TARGET);
        assertThat(after).isEqualTo(before); // content-checksum proof of zero mutation

        Integer stillNullSession = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id IS NULL",
                Integer.class, SCHOOL);
        assertThat(stillNullSession).isEqualTo(expectedRows);
    }

    @Test
    void realRun_adoptsAllSafeRowsAtomically_thenSecondRunIsIdempotent() {
        int expectedRows = seedHundredsOfSafeLegacyRows();

        TimetableAdoptionReport first = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);
        assertThat(first.adopted()).isEqualTo(expectedRows);

        Integer nowCanonical = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=? AND class_id IS NOT NULL",
                Integer.class, SCHOOL, SESSION_TARGET);
        assertThat(nowCanonical).isEqualTo(expectedRows);

        TimetableAdoptionReport second = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);
        assertThat(second.safe()).isZero();
        assertThat(second.adopted()).isZero();
        assertThat(second.alreadyAdopted()).isEqualTo(expectedRows);

        Integer stillCanonical = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_TARGET);
        assertThat(stillCanonical).isEqualTo(expectedRows); // no duplicates created by re-running
    }

    @Test
    void realRun_preservesFactualTimetableValues_onlyIdentityFieldsChange() {
        long id = insertLegacyRow(SCHOOL, "LA Class 1", null, "MONDAY", 1, "09:00", "09:40",
                "Mathematics", TEACHER_PLAIN, null);

        timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT academic_session_id, class_id, section_id, day, period_number, start_time, end_time, "
                        + "subject_name, teacher_id, simultaneous_group FROM timetable_entry WHERE id=?", id);
        assertThat(row.get("academic_session_id")).isEqualTo(SESSION_TARGET);
        assertThat(row.get("class_id")).isEqualTo(CLASS_NO_SECTION);
        assertThat(row.get("section_id")).isNull();
        assertThat(row.get("day")).isEqualTo("MONDAY");
        assertThat(row.get("period_number")).isEqualTo(1);
        assertThat(row.get("start_time")).isEqualTo("09:00");
        assertThat(row.get("end_time")).isEqualTo("09:40");
        assertThat(row.get("subject_name")).isEqualTo("Mathematics");
        assertThat(row.get("teacher_id")).isEqualTo(TEACHER_PLAIN);
        assertThat(row.get("simultaneous_group")).isNull();
    }

    // ── classification correctness against real DB state ───────────────────────────────────

    @Test
    void dryRun_unknownClassName_isInvalidAndNeverWritten() {
        long id = insertLegacyRow(SCHOOL, "Ghost Class", null, "MONDAY", 1, "09:00", "09:40",
                "Math", null, null);

        TimetableAdoptionReport report = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, true);

        assertThat(report.invalidOrConflicting()).isEqualTo(1);
        assertThat(report.unresolvedClassMappings()).isEqualTo(1);
        Long sessionId = jdbc.queryForObject(
                "SELECT academic_session_id FROM timetable_entry WHERE id=?", Long.class, id);
        assertThat(sessionId).isNull();
    }

    @Test
    void realRun_v55SlotCollision_onlyFirstRowAdopted_secondLeftUntouched() {
        long first = insertLegacyRow(SCHOOL, "LA Class 1", null, "MONDAY", 1, "09:00", "09:40", "Math", null, null);
        long second = insertLegacyRow(SCHOOL, "LA Class 1", null, "MONDAY", 1, "09:00", "09:40", "Science", null, null);

        TimetableAdoptionReport report = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);

        assertThat(report.adopted()).isEqualTo(1);
        assertThat(report.slotConflicts()).isEqualTo(1);

        Long firstSession = jdbc.queryForObject("SELECT academic_session_id FROM timetable_entry WHERE id=?", Long.class, first);
        Long secondSession = jdbc.queryForObject("SELECT academic_session_id FROM timetable_entry WHERE id=?", Long.class, second);
        assertThat(firstSession).isEqualTo(SESSION_TARGET);
        assertThat(secondSession).isNull(); // conflicting row never adopted
    }

    @Test
    void realRun_teacherOverlap_onlyFirstRowAdopted_secondLeftUntouched() {
        long first = insertLegacyRow(SCHOOL, "LA Class 1", null, "MONDAY", 1, "09:00", "09:40", "Math", TEACHER_PLAIN, null);
        long second = insertLegacyRow(SCHOOL, "LA Class 2", null, "MONDAY", 2, "09:20", "10:00", "Science", TEACHER_PLAIN, null);

        TimetableAdoptionReport report = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);

        assertThat(report.adopted()).isEqualTo(1);
        assertThat(report.teacherOverlaps()).isEqualTo(1);
        Long secondSession = jdbc.queryForObject("SELECT academic_session_id FROM timetable_entry WHERE id=?", Long.class, second);
        assertThat(secondSession).isNull();
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM timetable_entry WHERE id=?", Long.class, first))
                .isEqualTo(SESSION_TARGET);
    }

    @Test
    void dryRun_loneSimultaneousTag_isSafeAndFlaggedAsConcern_realDb() {
        insertLegacyRow(SCHOOL, "LA Class 1", null, "MONDAY", 1, "09:00", "09:40", "Math", null, "MATH_BIO");

        TimetableAdoptionReport report = timetableAdoptionService.adopt(SCHOOL, SESSION_TARGET, true);

        assertThat(report.safe()).isEqualTo(1);
        assertThat(report.simultaneousGroupConcerns()).isEqualTo(1);
        assertThat(report.details().get(0).simultaneousGroupConcern()).isTrue();
        assertThat(report.details().get(0).outcome()).isEqualTo(AdoptionOutcome.SAFE);
    }

    // ── fail-closed: no owned/current-shaped target session → zero writes ──────────────────

    @Test
    void realRun_targetSessionNotOwnedByThisSchool_throwsAndLeavesDbUnchanged() {
        insertLegacyRow(SCHOOL, "LA Class 1", null, "MONDAY", 1, "09:00", "09:40", "Math", null, null);
        long foreignSessionId = -999999L;

        assertThatThrownBy(() -> timetableAdoptionService.adopt(SCHOOL, foreignSessionId, false))
                .isInstanceOf(java.util.NoSuchElementException.class);

        Integer stillNull = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable_entry WHERE school_id=? AND academic_session_id IS NULL",
                Integer.class, SCHOOL);
        assertThat(stillNull).isEqualTo(1);
    }

    // ── class-teacher responsibility adoption ───────────────────────────────────────────────

    @Test
    void responsibility_dryRun_safeAndNeverWrites() {
        InvariantSnapshot before = invariantService.capture(SCHOOL, SESSION_TARGET);

        ResponsibilityAdoptionReport report = responsibilityAdoptionService.adopt(SCHOOL, SESSION_TARGET, true);

        assertThat(report.scanned()).isEqualTo(1);
        assertThat(report.safe()).isEqualTo(1);
        assertThat(report.adopted()).isZero();

        InvariantSnapshot after = invariantService.capture(SCHOOL, SESSION_TARGET);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void responsibility_realRun_createsRow_thenIdempotentOnSecondRun_liveTeacherNeverTouched() {
        String classTeacherBefore = jdbc.queryForObject(
                "SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_CT);

        ResponsibilityAdoptionReport first = responsibilityAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);
        assertThat(first.adopted()).isEqualTo(1);

        ResponsibilityAdoptionReport second = responsibilityAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);
        assertThat(second.adopted()).isZero();
        assertThat(second.alreadyAdopted()).isEqualTo(1);

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_TARGET);
        assertThat(rowCount).isEqualTo(1); // no duplicate created by re-running

        String classTeacherAfter = jdbc.queryForObject(
                "SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_CT);
        assertThat(classTeacherAfter).isEqualTo(classTeacherBefore); // live projection never touched
    }

    @Test
    void responsibility_realRun_conflictingExistingRow_neverOverwritten() {
        jdbc.update("INSERT INTO class_teacher_responsibility (school_id, academic_session_id, class_id, section_id, teacher_id) "
                + "VALUES (?, ?, ?, NULL, ?)", SCHOOL, SESSION_TARGET, CLASS_NO_SECTION, TEACHER_OTHER);

        ResponsibilityAdoptionReport report = responsibilityAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);

        assertThat(report.invalidOrConflicting()).isEqualTo(1);
        assertThat(report.adopted()).isZero();
        String stillTeacher = jdbc.queryForObject(
                "SELECT teacher_id FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=?",
                String.class, SCHOOL, SESSION_TARGET, CLASS_NO_SECTION);
        assertThat(stillTeacher).isEqualTo(TEACHER_OTHER);
    }

    @Test
    void responsibility_neverCallsActivationApply_noProvenanceRowCreated() {
        responsibilityAdoptionService.adopt(SCHOOL, SESSION_TARGET, false);

        Integer provenanceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_TARGET);
        assertThat(provenanceRows).isZero();
    }

    // ── fixture helpers ──────────────────────────────────────────────────────────────────────

    /** Batch-inserts a PROD-scale fixture: 3 sectionless classes + 1 sectioned class (2 sections),
     *  each spanning 6 days x 8 periods with no teacher and no simultaneous tag — every row
     *  resolves cleanly, so every one of them should classify SAFE. Mirrors F1's real proportions
     *  (a majority sectionless, a minority sectioned) without hardcoding PROD's exact counts. */
    private int seedHundredsOfSafeLegacyRows() {
        String[] days = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"};
        List<Object[]> batch = new ArrayList<>();
        String sql = "INSERT INTO timetable_entry (school_id, class_name, section_name, day, period_number, "
                + "start_time, end_time, subject_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        String[] sectionlessClasses = {"LA Class 1", "LA Class 2", "LA Class 3"};
        for (String className : sectionlessClasses) {
            for (String day : days) {
                for (int period = 1; period <= 8; period++) {
                    batch.add(new Object[]{SCHOOL, className, null, day, period,
                            startTime(period), endTime(period), "Subject " + period});
                }
            }
        }
        for (String sectionName : new String[]{"A", "B"}) {
            for (String day : days) {
                for (int period = 1; period <= 8; period++) {
                    batch.add(new Object[]{SCHOOL, "LA Class 11", sectionName, day, period,
                            startTime(period), endTime(period), "Subject " + period});
                }
            }
        }
        jdbc.batchUpdate(sql, batch);
        return batch.size();
    }

    private String startTime(int period) {
        return String.format("%02d:00", 8 + period);
    }

    private String endTime(int period) {
        return String.format("%02d:40", 8 + period);
    }

    private long insertLegacyRow(long schoolId, String className, String sectionName, String day, int period,
            String start, String end, String subject, String teacherId, String group) {
        return jdbc.queryForObject(
                "INSERT INTO timetable_entry (school_id, class_name, section_name, day, period_number, "
                        + "start_time, end_time, subject_name, teacher_id, simultaneous_group) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, schoolId, className, sectionName, day, period, start, end, subject, teacherId, group);
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id, active, created_at, name, plan, slug, "
                        + "academic_year_start_month, periods_per_day) VALUES (?, true, CURRENT_TIMESTAMP, ?, "
                        + "'TRIAL', ?, 4, 8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId, String label, boolean current) {
        jdbc.update("INSERT INTO academic_session (id, created_at, is_current, start_date, end_date, label, school_id) "
                + "VALUES (?, CURRENT_TIMESTAMP, ?, DATE '2026-04-01', DATE '2027-03-31', ?, ?)",
                id, current, label, schoolId);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id, active, name, school_id, stream_eligible) "
                + "VALUES (?, true, ?, ?, false)", id, name, schoolId);
    }

    private void insertSection(long id, long schoolId, long classId, String name) {
        jdbc.update("INSERT INTO section (id, active, class_id, name, school_id) VALUES (?, true, ?, ?, ?)",
                id, classId, name, schoolId);
    }

    private void insertTeacher(String teacherId, long schoolId, String status, String classTeacher, Long sectionId) {
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status, class_teacher, class_teacher_section_id) "
                + "VALUES (?, ?, 'LA IT Teacher', ?, ?, ?)", teacherId, schoolId, status, classTeacher, sectionId);
    }
}
