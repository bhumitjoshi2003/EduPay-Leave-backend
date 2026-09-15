package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.AssignmentRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.GenerationResult;
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
import org.springframework.test.context.transaction.TestTransaction;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Real-Postgres proof that {@link FeeWorkflowService}'s assignment/batch-driven generation path
 * — historically the one that read the mutable {@code Student.className} — now derives the
 * billed class from the authoritative {@link com.indraacademy.ias_management.entity.StudentEnrollment}
 * segment for the exact session being generated, exactly like {@link FeeGenerationTargetService}
 * already does, and never falls back to the student's current profile for an enrollment-covered
 * student.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({FeeWorkflowService.class, FeeCalculationService.class, AcademicSessionService.class,
        FeeWorkflowServicePostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class FeeWorkflowServicePostgresIT {
    /** computeMonthSnapshot's dueMonths JSON must be really parsed (not mocked) — matching
     *  FeeGenerationTargetServicePostgresIT's own precedent for the same dependency. */
    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    static final long SCHOOL = -98001;
    static final long SESSION_2025 = -98002, SESSION_2026 = -98003;
    static final long CLASS_9 = -98004, CLASS_10 = -98005;
    static final long FEE_HEAD = -98006;
    static final String LABEL_2025 = "2025-2026", LABEL_2026 = "2026-2027";

    @Autowired JdbcTemplate jdbc;
    @Autowired FeeWorkflowService service;
    @MockBean SecurityUtil securityUtil;
    @MockBean AuditService auditService;
    @MockBean StudentFeesRecalculationService recalculationService;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
    }

    @BeforeEach void fixtures() {
        lenient().when(securityUtil.getSchoolId()).thenReturn(SCHOOL);
        lenient().when(securityUtil.getUsername()).thenReturn("admin");
        lenient().when(securityUtil.getRole()).thenReturn("ADMIN");

        // Every test here commits real rows (generate() runs each student in its own
        // TransactionTemplate transaction — a separate physical connection from this ambient
        // @DataJpaTest transaction — so fixtures must be committed for it to see them at all).
        // Defensively clean up whatever a previous test left committed before inserting fresh.
        cleanupCommittedFixtures();

        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'FeeWorkflow IT','TRIAL','feeworkflow-it',4,8,'Asia/Kolkata')", SCHOOL);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)," +
                "(?,?,?,DATE '2026-04-01',DATE '2027-03-31',false,CURRENT_TIMESTAMP)",
                SESSION_2025, SCHOOL, LABEL_2025, SESSION_2026, SCHOOL, LABEL_2026);
        jdbc.update("INSERT INTO school_class (id,school_id,name,active,display_order,stream_eligible) VALUES " +
                "(?,?,'9',true,1,false),(?,?,'10',true,2,false)", CLASS_9, SCHOOL, CLASS_10, SCHOOL);
        jdbc.update("INSERT INTO fee_head (id,active,code,created_at,display_order,due_months,frequency,name,is_optional,is_refundable,school_id) VALUES " +
                "(?,true,'TUITION',CURRENT_TIMESTAMP,1,'[1,2,3,4,5,6,7,8,9,10,11,12]','MONTHLY','Tuition Fee',false,false,?)", FEE_HEAD, SCHOOL);
        jdbc.update("INSERT INTO fee_structure_rule (id,amount,class_name,created_at,effective_from,school_id,academic_session_id,fee_head_id,class_id) VALUES " +
                "(?,100000,'9',CURRENT_TIMESTAMP,DATE '2025-04-01',?,?,?,?)", FEE_HEAD - 1, SCHOOL, SESSION_2025, FEE_HEAD, CLASS_9);
        jdbc.update("INSERT INTO fee_structure_rule (id,amount,class_name,created_at,effective_from,school_id,academic_session_id,fee_head_id,class_id) VALUES " +
                "(?,120000,'10',CURRENT_TIMESTAMP,DATE '2026-04-01',?,?,?,?)", FEE_HEAD - 2, SCHOOL, SESSION_2026, FEE_HEAD, CLASS_10);
        // Fee config is ACTIVE with retroactive generation allowed so a historical-session
        // generate() call (Test 1/4) is never rejected by the operational-status gate itself.
        jdbc.update("INSERT INTO school_fee_settings (school_id, operational_status, allow_retroactive_generation) VALUES (?, 'ACTIVE', true)", SCHOOL);
    }

    private void insertStudent(String id, long classId, String className) {
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_id,class_name,takes_bus,joining_date) " +
                "VALUES (?,?,'ACTIVE',?,?,false,DATE '2025-04-01')", id, SCHOOL, classId, className);
    }
    private void insertEnrollment(String studentId, long sessionId, String status, long classId, LocalDate from, LocalDate until) {
        String closureReason = "CLOSED".equals(status) ? "SESSION_COMPLETED" : null;
        jdbc.update("INSERT INTO student_enrollment (school_id,student_id,academic_session_id,class_id,class_name_snapshot,status,effective_from,effective_until,closure_reason) " +
                "VALUES (?,?,?,?,'x',?,?,?,?)", SCHOOL, studentId, sessionId, classId, status, from, until, closureReason);
    }
    private void insertAssignment(String studentId, String sessionLabel) {
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, status) VALUES (?,?,?,'READY')",
                SCHOOL, studentId, sessionLabel);
    }
    private List<String> generatedClassNames(String studentId, String sessionLabel) {
        return jdbc.queryForList("SELECT DISTINCT class_name FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                String.class, SCHOOL, studentId, sessionLabel);
    }
    private int feeRowCount(String studentId, String sessionLabel) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND year=?",
                Integer.class, SCHOOL, studentId, sessionLabel);
        return c == null ? 0 : c;
    }

    /** Every test here commits real rows (see the comment in {@link #fixtures()}), so leftover
     * data must be cleaned up explicitly rather than relying on the ambient @DataJpaTest
     * rollback — matching FeeGenerationTargetServicePostgresIT's own established pattern. */
    private void cleanupCommittedFixtures() {
        jdbc.update("DELETE FROM student_fees_line_item WHERE school_id=? " +
                "OR student_fees_id IN (SELECT id FROM student_fees WHERE school_id=?) " +
                "OR fee_head_id IN (SELECT id FROM fee_head WHERE school_id=?)", SCHOOL, SCHOOL, SCHOOL);
        jdbc.update("DELETE FROM student_fees WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_fee_assignment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_generation_batch WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_one_time_fee_charged WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_structure_rule WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM fee_head WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_fee_settings WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student_enrollment WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM student WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school_class WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id=?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id=?", SCHOOL);
    }

    /** Test 1: Student.currentClass = Class 10 (already promoted), but the 2025-2026 session's
     * own enrollment segment says Class 9 — generation for that historical session must use
     * Class 9, never the student's current mutable profile. */
    @Test void historicalSessionGeneration_usesSessionEnrollmentClass_notCurrentStudentProfile() {
        insertStudent("STU-HIST", CLASS_10, "10"); // current/mutable profile: already Class 10
        insertEnrollment("STU-HIST", SESSION_2025, "CLOSED", CLASS_9, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertAssignment("STU-HIST", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-HIST"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isTrue();
        assertThat(results.getFirst().generated()).isEqualTo(1);
        assertThat(generatedClassNames("STU-HIST", LABEL_2025)).containsExactly("9");
    }

    /** Financial AcademicSession Authority, Phase B3/B4: a freshly-generated StudentFees row
     * and its line item must both carry the resolved AcademicSession's real id, alongside the
     * unchanged label — not merely the label as before this phase. */
    @Test void generation_populatesAcademicSessionIdOnStudentFeesAndLineItem() {
        insertStudent("STU-SESSIONID", CLASS_9, "9");
        insertEnrollment("STU-SESSIONID", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignment("STU-SESSIONID", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-SESSIONID"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT academic_session_id FROM student_fees WHERE student_id = 'STU-SESSIONID'", Long.class))
                .isEqualTo(SESSION_2025);
        assertThat(jdbc.queryForObject(
                "SELECT academic_session_id FROM student_fees_line_item WHERE student_id = 'STU-SESSIONID'", Long.class))
                .isEqualTo(SESSION_2025);
    }

    /** Test 2: the student IS enrollment-covered (has a segment for a DIFFERENT session) but has
     * no valid enrollment for the session actually being generated — no StudentFees row must be
     * created, and the mutable Student.className must never be used as a silent fallback. */
    @Test void noEnrollmentForTargetSession_createsNoStudentFeesRow() {
        insertStudent("STU-NOENR", CLASS_10, "10");
        insertEnrollment("STU-NOENR", SESSION_2026, "ACTIVE", CLASS_10, LocalDate.of(2026, 4, 1), null); // only 2026-2027, not 2025-2026
        insertAssignment("STU-NOENR", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-NOENR"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isFalse();
        assertThat(results.getFirst().message()).contains("No enrollment found");
        assertThat(feeRowCount("STU-NOENR", LABEL_2025)).isZero();
    }

    /** Test 3: two admins concurrently generating the same student/session/month must never
     * produce more than one canonical StudentFees row — the pre-existing StudentFeeAssignment
     * pessimistic lock (unchanged by the enrollment-authority fix) still serializes this. */
    @Test void concurrentDuplicateGeneration_producesExactlyOneCanonicalRow() throws Exception {
        insertStudent("STU-CONC", CLASS_9, "9");
        insertEnrollment("STU-CONC", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignment("STU-CONC", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<List<GenerationResult>>> futures = List.of(
                    pool.submit(() -> { ready.countDown(); go.await(); return service.generate(
                            new AssignmentRequest(List.of("STU-CONC"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip1"); }),
                    pool.submit(() -> { ready.countDown(); go.await(); return service.generate(
                            new AssignmentRequest(List.of("STU-CONC"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip2"); })
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var f : futures) f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertThat(feeRowCount("STU-CONC", LABEL_2025)).isEqualTo(1);
    }

    /** Test 4: a student promoted from Class 9 (2025-2026, CLOSED) to Class 10 (2026-2027,
     * ACTIVE) must be billed under each session's own enrollment class when each session is
     * generated — never the other session's class, and never the current mutable profile. */
    @Test void promotionAcrossSessions_eachSessionUsesItsOwnEnrollmentClass() {
        insertStudent("STU-PROMO", CLASS_10, "10"); // current profile already reflects the promotion
        insertEnrollment("STU-PROMO", SESSION_2025, "CLOSED", CLASS_9, LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31));
        insertEnrollment("STU-PROMO", SESSION_2026, "ACTIVE", CLASS_10, LocalDate.of(2026, 4, 1), null);
        insertAssignment("STU-PROMO", LABEL_2025);
        insertAssignment("STU-PROMO", LABEL_2026);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> historicalResults = service.generate(
                new AssignmentRequest(List.of("STU-PROMO"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");
        List<GenerationResult> currentResults = service.generate(
                new AssignmentRequest(List.of("STU-PROMO"), LABEL_2026, LocalDate.of(2026, 4, 1), List.of(1), null, null), "ip");

        assertThat(historicalResults.getFirst().successful()).isTrue();
        assertThat(currentResults.getFirst().successful()).isTrue();
        assertThat(generatedClassNames("STU-PROMO", LABEL_2025)).containsExactly("9");
        assertThat(generatedClassNames("STU-PROMO", LABEL_2026)).containsExactly("10");
    }

    /** Financial AcademicSession Authority, Phase C2: the dedup check must key off the
     * authoritative {@code academic_session_id}, not the raw {@code year} label — proven by
     * pre-seeding a StudentFees row for month 1 whose label is deliberately wrong (matches
     * neither session), but whose {@code academic_session_id} correctly matches SESSION_2025.
     * Generation must still recognize it as already-generated and skip, never creating a second,
     * duplicate row for the same student/session/month. */
    @Test void dedupCheck_matchesByAuthoritativeSessionId_evenWhenRawYearSnapshotIsMalformed() {
        insertStudent("STU-DEDUP-ID", CLASS_9, "9");
        insertEnrollment("STU-DEDUP-ID", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignment("STU-DEDUP-ID", LABEL_2025);
        jdbc.update("INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '9', 1, false, false, 'MALFORMED-LABEL', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED')",
                SCHOOL, "STU-DEDUP-ID", SESSION_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-DEDUP-ID"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isTrue();
        assertThat(results.getFirst().generated()).isZero();
        assertThat(results.getFirst().skipped()).isEqualTo(1);
        Integer totalRows = jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id=? AND academic_session_id=? AND month=1",
                Integer.class, SCHOOL, "STU-DEDUP-ID", SESSION_2025);
        assertThat(totalRows).isEqualTo(1);
    }
}
