package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.ActivationApplyResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase F4 real-PostgreSQL proof for copy, activation, and the concurrency guarantees neither a
 * unit test nor mocks can validate: the pessimistic row lock actually serializes two independent
 * database connections, and a real end-to-end apply() call produces the exact grant/clear/leave
 * pattern described in the F4 report.
 *
 * <p>Fixtures are committed (not left in the default rolled-back test transaction) for the same
 * reason established in {@code TimetableSessionAuthorityPostgresIT}: the copy worker and the
 * activation lock both need committed state visible from a second transaction/connection.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClassTeacherResponsibilityCopyWorker.class,
        TimetableSessionAccessService.class, ClassTeacherActivationService.class, AcademicSessionService.class,
        AuditService.class, com.indraacademy.ias_management.util.SecurityUtil.class,
        com.indraacademy.ias_management.config.ClockConfig.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ClassTeacherActivationPostgresIT {

    private static final long SCHOOL = -97001L;
    private static final long OTHER_SCHOOL = -97002L;
    private static final long SESSION_SOURCE = -97101L;
    private static final long SESSION_CURRENT = -97102L;
    private static final long OTHER_SCHOOL_SESSION = -97103L;
    private static final long CLASS_A = -97201L;
    private static final long CLASS_B = -97202L;
    private static final String TEACHER_A = "CTA-IT-A";
    private static final String TEACHER_B = "CTA-IT-B";
    private static final String TEACHER_STALE = "CTA-IT-STALE";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClassTeacherResponsibilityCopyWorker copyWorker;
    @Autowired private ClassTeacherActivationService activationService;

    private HttpServletRequest request;

    @BeforeEach
    void seedTenantFixtures() {
        insertSchool(SCHOOL, "cta-it-school");
        insertSchool(OTHER_SCHOOL, "cta-it-other-school");
        insertSession(SESSION_SOURCE, SCHOOL, "CTA-IT-SOURCE", false);
        insertSession(SESSION_CURRENT, SCHOOL, "CTA-IT-CURRENT", true);
        insertSession(OTHER_SCHOOL_SESSION, OTHER_SCHOOL, "CTA-IT-OTHER", false);
        insertClass(CLASS_A, SCHOOL, "CTA Class A");
        insertClass(CLASS_B, SCHOOL, "CTA Class B");
        insertTeacher(TEACHER_A, SCHOOL, "ACTIVE", null, null);
        insertTeacher(TEACHER_B, SCHOOL, "ACTIVE", null, null);
        insertTeacher(TEACHER_STALE, SCHOOL, "ACTIVE", "CTA Class B", null); // stale live holder of class B

        TestTransaction.flagForCommit();
        TestTransaction.end();

        request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        jdbc.update("DELETE FROM class_teacher_activation WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM class_teacher_responsibility WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM teacher WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
        jdbc.update("DELETE FROM school WHERE id IN (?, ?)", SCHOOL, OTHER_SCHOOL);
    }

    private void asSchool(long schoolId) {
        com.indraacademy.ias_management.util.SchoolContext.set(schoolId);
    }

    // ── copy: idempotency + conflict ────────────────────────────────────────────────────────

    @Test
    void copyWorker_reRunIsIdempotent() {
        var source = responsibility(SESSION_SOURCE, CLASS_A, null, TEACHER_A);

        var first = copyWorker.attempt(SCHOOL, source, SESSION_CURRENT);
        var second = copyWorker.attempt(SCHOOL, source, SESSION_CURRENT);

        assertThat(first.outcome()).isEqualTo(ClassTeacherResponsibilityCopyWorker.Outcome.COPIED);
        assertThat(second.outcome()).isEqualTo(ClassTeacherResponsibilityCopyWorker.Outcome.ALREADY_COPIED);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=?",
                Integer.class, SCHOOL, SESSION_CURRENT, CLASS_A);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void copyWorker_conflictingExistingTargetRow_reportedNotOverwritten() {
        var source = responsibility(SESSION_SOURCE, CLASS_A, null, TEACHER_A);
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_B); // different teacher already configured there

        var evaluation = copyWorker.attempt(SCHOOL, source, SESSION_CURRENT);

        assertThat(evaluation.outcome()).isEqualTo(ClassTeacherResponsibilityCopyWorker.Outcome.CONFLICT);
        String teacherStillThere = jdbc.queryForObject(
                "SELECT teacher_id FROM class_teacher_responsibility WHERE school_id=? AND academic_session_id=? AND class_id=?",
                String.class, SCHOOL, SESSION_CURRENT, CLASS_A);
        assertThat(teacherStillThere).isEqualTo(TEACHER_B); // never overwritten
    }

    // ── activation: replacement/clearing semantics, end to end ─────────────────────────────

    @Test
    void apply_grantsClearsAndLeavesUnchanged_inOneCall() {
        // Class A: newly configured for TEACHER_A, no prior live holder → BECOMING_LIVE.
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_A);
        // Class B: TEACHER_STALE currently live, but nothing configured for class B this
        // session → must be cleared.
        // (TEACHER_STALE was seeded already live for "CTA Class B" in @BeforeEach.)

        asSchool(SCHOOL);
        ActivationApplyResult result = activationService.apply(request);

        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.cleared()).isEqualTo(1);

        String classTeacherA = jdbc.queryForObject(
                "SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_A);
        assertThat(classTeacherA).isEqualTo("CTA Class A");

        String classTeacherStale = jdbc.queryForObject(
                "SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_STALE);
        assertThat(classTeacherStale).isNull();
    }

    @Test
    void apply_reRunAfterSuccess_isIdempotent_noFurtherChanges() {
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_A);
        asSchool(SCHOOL);

        activationService.apply(request);
        ActivationApplyResult second = activationService.apply(request);

        assertThat(second.applied()).isZero();
        assertThat(second.cleared()).isZero();
    }

    @Test
    void apply_noCurrentSession_failsAndLeavesLiveProjectionUnchanged() {
        // Unmark the current session — the school now has no current session at all.
        jdbc.update("UPDATE academic_session SET is_current=false WHERE id=?", SESSION_CURRENT);
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_A);
        asSchool(SCHOOL);

        assertThatThrownBy(() -> activationService.apply(request)).isInstanceOf(IllegalStateException.class);

        String classTeacherA = jdbc.queryForObject("SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_A);
        String classTeacherStale = jdbc.queryForObject("SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_STALE);
        assertThat(classTeacherA).isNull(); // never granted
        assertThat(classTeacherStale).isEqualTo("CTA Class B"); // never cleared — nothing was touched

        Integer provenanceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_CURRENT);
        assertThat(provenanceRows).isZero(); // a failed activation must not record successful provenance
    }

    // ── activation provenance: real transaction, real persisted state (F4.1) ───────────────

    @Test
    void apply_persistsProvenanceInTheSameTransactionAsTheLiveWrites() {
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_A);
        asSchool(SCHOOL);

        activationService.apply(request);

        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT configuration_fingerprint, applied_by FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                SCHOOL, SESSION_CURRENT);
        assertThat(row.get("configuration_fingerprint")).isNotNull();
        assertThat(row.get("applied_by")).isNotNull();
    }

    @Test
    void preview_afterRealApply_readsBackAppliedInSyncAcrossSeparateCalls() {
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_A);
        asSchool(SCHOOL);

        activationService.apply(request); // call 1 — its own transaction
        var preview = activationService.preview(); // call 2 — a separate transaction/read

        assertThat(preview.activationState().name()).isEqualTo("APPLIED_IN_SYNC");
        assertThat(preview.inSync()).isTrue();
    }

    @Test
    void preview_coincidentallyInSync_withNoRealApplyEver_isNeverApplied() {
        // Live already happens to equal configured (hand-entered, or carried over) — but
        // activationService.apply() was never called at all in this test.
        insertResponsibility(SESSION_CURRENT, CLASS_B, null, TEACHER_STALE); // TEACHER_STALE is already live for "CTA Class B"
        asSchool(SCHOOL);

        var preview = activationService.preview();

        assertThat(preview.inSync()).isTrue();
        assertThat(preview.activationState().name()).isEqualTo("NEVER_APPLIED");
    }

    // ── concurrency: the pessimistic session lock actually serializes two connections ──────

    @Test
    void sessionLock_serializesAConcurrentHolderAgainstAnActivationApply() throws Exception {
        insertResponsibility(SESSION_CURRENT, CLASS_A, null, TEACHER_A);

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        CompletableFuture<Long> holderElapsed = CompletableFuture.supplyAsync(() -> {
            try (Connection conn = DriverManager.getConnection(
                    normalizeJdbcUrl(System.getenv("DB_URL")), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT * FROM academic_session WHERE id = " + SESSION_CURRENT + " FOR UPDATE");
                }
                holderHasLock.countDown();
                releaseHolder.await(5, TimeUnit.SECONDS);
                conn.commit();
                return System.currentTimeMillis();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();
        long waiterStart = System.currentTimeMillis();

        CompletableFuture<Long> waiterElapsed = CompletableFuture.supplyAsync(() -> {
            asSchool(SCHOOL);
            activationService.apply(request);
            return System.currentTimeMillis();
        });

        // Give the waiter a moment to actually block on the lock, then release the holder.
        Thread.sleep(800);
        releaseHolder.countDown();

        long waiterFinished = waiterElapsed.get(10, TimeUnit.SECONDS);
        holderElapsed.get(5, TimeUnit.SECONDS);

        // The waiter could only finish AFTER the holder released — proving the lock blocked it,
        // not that the two operations ran independently.
        assertThat(waiterFinished - waiterStart).isGreaterThanOrEqualTo(750);
    }

    private static String normalizeJdbcUrl(String url) {
        return url.startsWith("jdbc:") ? url : "jdbc:" + url;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private com.indraacademy.ias_management.entity.ClassTeacherResponsibility responsibility(
            long sessionId, long classId, Long sectionId, String teacherId) {
        var r = new com.indraacademy.ias_management.entity.ClassTeacherResponsibility();
        r.setSchoolId(SCHOOL);
        r.setAcademicSessionId(sessionId);
        r.setClassId(classId);
        r.setSectionId(sectionId);
        r.setTeacherId(teacherId);
        return r;
    }

    private void insertResponsibility(long sessionId, long classId, Long sectionId, String teacherId) {
        jdbc.update("INSERT INTO class_teacher_responsibility (school_id, academic_session_id, class_id, section_id, teacher_id) " +
                "VALUES (?, ?, ?, ?, ?)", SCHOOL, sessionId, classId, sectionId, teacherId);
    }

    private void insertSchool(long id, String slug) {
        jdbc.update("INSERT INTO school (id, active, created_at, name, plan, slug, " +
                        "academic_year_start_month, periods_per_day) VALUES (?, true, CURRENT_TIMESTAMP, ?, " +
                        "'TRIAL', ?, 4, 8)", id, slug, slug);
    }

    private void insertSession(long id, long schoolId, String label, boolean current) {
        jdbc.update("INSERT INTO academic_session (id, created_at, is_current, start_date, end_date, label, school_id) " +
                "VALUES (?, CURRENT_TIMESTAMP, ?, DATE '2026-04-01', DATE '2027-03-31', ?, ?)", id, current, label, schoolId);
    }

    private void insertClass(long id, long schoolId, String name) {
        jdbc.update("INSERT INTO school_class (id, active, name, school_id, stream_eligible) " +
                "VALUES (?, true, ?, ?, false)", id, name, schoolId);
    }

    private void insertTeacher(String teacherId, long schoolId, String status, String classTeacher, Long sectionId) {
        jdbc.update("INSERT INTO teacher (teacher_id, school_id, name, status, class_teacher, class_teacher_section_id) " +
                "VALUES (?, ?, 'CTA IT Teacher', ?, ?, ?)", teacherId, schoolId, status, classTeacher, sectionId);
    }
}
