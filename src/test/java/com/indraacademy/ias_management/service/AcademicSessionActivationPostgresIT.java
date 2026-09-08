package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.ClassTeacherResponsibilityDtos.SessionActivationOutcome;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase G real-PostgreSQL proof: "Make Current" atomically switches the current session AND
 * activates its class-teacher configuration, targeting the NEW session (never the outgoing
 * one), with exactly one current session per school and no partial transition on failure.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AcademicSessionActivationService.class, AcademicSessionService.class, ClassTeacherActivationService.class,
        TimetableSessionAccessService.class, AuditService.class,
        com.indraacademy.ias_management.util.SecurityUtil.class,
        com.indraacademy.ias_management.config.ClockConfig.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class AcademicSessionActivationPostgresIT {

    private static final long SCHOOL = -98001L;
    private static final long OTHER_SCHOOL = -98002L;
    private static final long SESSION_A = -98101L;
    private static final long SESSION_B = -98102L;
    private static final long OTHER_SCHOOL_SESSION = -98103L;
    private static final long CLASS = -98201L;
    private static final String TEACHER_A = "ASA-IT-A";
    private static final String TEACHER_B = "ASA-IT-B";

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AcademicSessionActivationService sessionActivationService;

    private HttpServletRequest request;

    @BeforeEach
    void seedTenantFixtures() {
        insertSchool(SCHOOL, "asa-it-school");
        insertSchool(OTHER_SCHOOL, "asa-it-other-school");
        insertSession(SESSION_A, SCHOOL, "ASA-IT-SESSION-A", true);
        insertSession(SESSION_B, SCHOOL, "ASA-IT-SESSION-B", false);
        insertSession(OTHER_SCHOOL_SESSION, OTHER_SCHOOL, "ASA-IT-OTHER", false);
        insertClass(CLASS, SCHOOL, "ASA Class");
        // TEACHER_A is the live class-teacher of CLASS today (as if session A had this fact
        // live) — session B's own configuration names a DIFFERENT teacher, so activating B
        // must both grant TEACHER_B and clear TEACHER_A.
        insertTeacher(TEACHER_A, SCHOOL, "ACTIVE", "ASA Class", null);
        insertTeacher(TEACHER_B, SCHOOL, "ACTIVE", null, null);
        insertResponsibility(SESSION_B, CLASS, null, TEACHER_B);

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

    @Test
    void makeSessionBCurrent_activatesBsConfiguration_notSessionAs() {
        asSchool(SCHOOL);

        SessionActivationOutcome outcome = sessionActivationService.setCurrentSessionAndActivate(SESSION_B, request);

        assertThat(outcome.activationPerformed()).isTrue();
        assertThat(outcome.session().isCurrent()).isTrue();
        assertThat(outcome.activation().applied()).isEqualTo(1); // TEACHER_B granted
        assertThat(outcome.activation().cleared()).isEqualTo(1); // TEACHER_A cleared

        // Exactly one current session for the school, and it's B.
        Integer currentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic_session WHERE school_id=? AND is_current=true", Integer.class, SCHOOL);
        assertThat(currentCount).isOne();
        Boolean sessionACurrent = jdbc.queryForObject("SELECT is_current FROM academic_session WHERE id=?", Boolean.class, SESSION_A);
        Boolean sessionBCurrent = jdbc.queryForObject("SELECT is_current FROM academic_session WHERE id=?", Boolean.class, SESSION_B);
        assertThat(sessionACurrent).isFalse();
        assertThat(sessionBCurrent).isTrue();

        // Live projection reflects B's configuration, not A's prior fact.
        String liveA = jdbc.queryForObject("SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_A);
        String liveB = jdbc.queryForObject("SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_B);
        assertThat(liveA).isNull();
        assertThat(liveB).isEqualTo("ASA Class");

        // Provenance belongs to B, not A.
        Integer provenanceForB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_B);
        Integer provenanceForA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                Integer.class, SCHOOL, SESSION_A);
        assertThat(provenanceForB).isOne();
        assertThat(provenanceForA).isZero();
    }

    @Test
    void reRunningOnTheAlreadyCurrentSession_isAGenuineNoOp() {
        asSchool(SCHOOL);
        sessionActivationService.setCurrentSessionAndActivate(SESSION_B, request);
        String appliedAtFirst = jdbc.queryForObject(
                "SELECT applied_at::text FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                String.class, SCHOOL, SESSION_B);

        SessionActivationOutcome second = sessionActivationService.setCurrentSessionAndActivate(SESSION_B, request);

        assertThat(second.activationPerformed()).isFalse();
        assertThat(second.activation()).isNull();
        String appliedAtSecond = jdbc.queryForObject(
                "SELECT applied_at::text FROM class_teacher_activation WHERE school_id=? AND academic_session_id=?",
                String.class, SCHOOL, SESSION_B);
        assertThat(appliedAtSecond).isEqualTo(appliedAtFirst); // provenance never rewritten
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_activation WHERE school_id=?", Integer.class, SCHOOL);
        assertThat(rowCount).isOne(); // no duplicate provenance row created
    }

    @Test
    void invalidTargetSession_failsBeforeAnyWrite_sessionARemainsCurrent() {
        asSchool(SCHOOL);

        // OTHER_SCHOOL_SESSION belongs to a different tenant — lockOwnedSession must reject it
        // before touching anything, proving a real (not mocked) pre-write failure leaves the
        // current-session flag completely untouched.
        assertThatThrownBy(() -> sessionActivationService.setCurrentSessionAndActivate(OTHER_SCHOOL_SESSION, request))
                .isInstanceOf(java.util.NoSuchElementException.class);

        Boolean sessionAStillCurrent = jdbc.queryForObject("SELECT is_current FROM academic_session WHERE id=?", Boolean.class, SESSION_A);
        assertThat(sessionAStillCurrent).isTrue();
        String liveA = jdbc.queryForObject("SELECT class_teacher FROM teacher WHERE teacher_id=?", String.class, TEACHER_A);
        assertThat(liveA).isEqualTo("ASA Class"); // never cleared
        Integer provenanceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_teacher_activation WHERE school_id=?", Integer.class, SCHOOL);
        assertThat(provenanceRows).isZero();
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
                "VALUES (?, ?, 'ASA IT Teacher', ?, ?, ?)", teacherId, schoolId, status, classTeacher, sectionId);
    }

    private void insertResponsibility(long sessionId, long classId, Long sectionId, String teacherId) {
        jdbc.update("INSERT INTO class_teacher_responsibility (school_id, academic_session_id, class_id, section_id, teacher_id) " +
                "VALUES (?, ?, ?, ?, ?)", SCHOOL, sessionId, classId, sectionId, teacherId);
    }
}
