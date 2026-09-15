package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.AssignmentRequest;
import com.indraacademy.ias_management.dto.FeeWorkflowDtos.GenerationResult;
import com.indraacademy.ias_management.entity.StudentFeeAssignment;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    /** Financial AcademicSession Authority, Phase D4: every real assignment row has carried
     * academic_session_id since D3's dual-write went live (production had zero rows before it) —
     * this helper now matches that shape, since the generation lock is id-based from D4 onward
     * and a label-only row would simply never be found (see insertAssignmentWithLabelAndSessionId
     * for the deliberate-mismatch tests that exist specifically to prove that). */
    private void insertAssignment(String studentId, String sessionLabel) {
        insertAssignmentWithLabelAndSessionId(studentId, sessionLabel, sessionLabel.equals(LABEL_2025) ? SESSION_2025 : SESSION_2026);
    }
    private void insertAssignmentWithLabelAndSessionId(String studentId, String sessionLabel, long academicSessionId) {
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, academic_session_id, status) VALUES (?,?,?,?,'READY')",
                SCHOOL, studentId, sessionLabel, academicSessionId);
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

    // ── Financial AcademicSession Authority, Phase D3 — validation hardening + dual-write ─────

    /** Task 14: assign() must reject a well-formatted-but-nonexistent session BEFORE persisting
     * any StudentFeeAssignment row — closing the Phase D1 validation gap. */
    @Test void assign_invalidButWellFormattedSession_rejectsWithoutCreatingAnyAssignmentRow() {
        insertStudent("STU-INVALID-ASSIGN", CLASS_9, "9");
        TestTransaction.flagForCommit(); TestTransaction.end();

        assertThatThrownBy(() -> service.assign(
                new AssignmentRequest(List.of("STU-INVALID-ASSIGN"), "2099-2100", LocalDate.of(2025, 4, 1), List.of(1), null, null), false, "ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcademicSession not found");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM student_fee_assignment WHERE school_id=? AND student_id='STU-INVALID-ASSIGN'", Integer.class, SCHOOL);
        assertThat(count).isZero();
    }

    /** Task 15: exclude() shares assign()'s upsert path, so the same rejection must apply. */
    @Test void exclude_invalidButWellFormattedSession_rejectsWithoutCreatingAnyAssignmentRow() {
        insertStudent("STU-INVALID-EXCLUDE", CLASS_9, "9");
        TestTransaction.flagForCommit(); TestTransaction.end();

        assertThatThrownBy(() -> service.assign(
                new AssignmentRequest(List.of("STU-INVALID-EXCLUDE"), "2099-2100", LocalDate.of(2025, 4, 1), List.of(1), "test exclusion", null), true, "ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcademicSession not found");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM student_fee_assignment WHERE school_id=? AND student_id='STU-INVALID-EXCLUDE'", Integer.class, SCHOOL);
        assertThat(count).isZero();
    }

    /** Task 16: generate() must reject before the FeeGenerationBatch row is even created — the
     * old behavior could leave a RUNNING-then-FAILED batch row for a session that was never
     * real; the new order resolves the session first, so no workflow history is created at all. */
    @Test void generate_invalidButWellFormattedSession_rejectsBeforeAnyBatchOrFeeRowCreated() {
        insertStudent("STU-INVALID-GEN", CLASS_9, "9");
        TestTransaction.flagForCommit(); TestTransaction.end();

        assertThatThrownBy(() -> service.generate(
                new AssignmentRequest(List.of("STU-INVALID-GEN"), "2099-2100", LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcademicSession not found");

        Integer batchCount = jdbc.queryForObject(
                "SELECT count(*) FROM fee_generation_batch WHERE school_id=? AND requested_student_ids LIKE '%STU-INVALID-GEN%'", Integer.class, SCHOOL);
        assertThat(batchCount).isZero();
        Integer feeCount = jdbc.queryForObject(
                "SELECT count(*) FROM student_fees WHERE school_id=? AND student_id='STU-INVALID-GEN'", Integer.class, SCHOOL);
        assertThat(feeCount).isZero();
    }

    /** Task 17: assign() dual-write — both the label and the id come from the SAME resolved
     * AcademicSession, on both the returned object and the persisted row. */
    @Test void assign_dualWrite_populatesLabelAndIdFromTheSameResolvedSession() {
        insertStudent("STU-DUALWRITE", CLASS_9, "9");
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<StudentFeeAssignment> saved = service.assign(
                new AssignmentRequest(List.of("STU-DUALWRITE"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), false, "ip");

        assertThat(saved.getFirst().getAcademicSession()).isEqualTo(LABEL_2025);
        assertThat(saved.getFirst().getAcademicSessionId()).isEqualTo(SESSION_2025);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT academic_session, academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-DUALWRITE'", SCHOOL);
        assertThat(row.get("academic_session")).isEqualTo(LABEL_2025);
        assertThat(row.get("academic_session_id")).isEqualTo(SESSION_2025);
    }

    /** Task 18: exclude() dual-write — same proof via the exclusion path. */
    @Test void exclude_dualWrite_populatesLabelAndIdFromTheSameResolvedSession() {
        insertStudent("STU-EXCLWRITE", CLASS_9, "9");
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<StudentFeeAssignment> saved = service.assign(
                new AssignmentRequest(List.of("STU-EXCLWRITE"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), "test exclusion", null), true, "ip");

        assertThat(saved.getFirst().isExcluded()).isTrue();
        assertThat(saved.getFirst().getAcademicSession()).isEqualTo(LABEL_2025);
        assertThat(saved.getFirst().getAcademicSessionId()).isEqualTo(SESSION_2025);
    }

    /** Task 19: FeeGenerationBatch dual-write — the persisted row (read AFTER the final
     * status/count update, not the initial RUNNING save) still carries the resolved label and
     * id, proving the id is unchanged across both writes to this batch. */
    @Test void generate_dualWrite_batchCarriesResolvedLabelAndId_unchangedAfterCompletion() {
        insertStudent("STU-BATCHWRITE", CLASS_9, "9");
        insertEnrollment("STU-BATCHWRITE", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignment("STU-BATCHWRITE", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-BATCHWRITE"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");
        assertThat(results.getFirst().successful()).isTrue();

        Map<String, Object> batchRow = jdbc.queryForMap(
                "SELECT academic_session, academic_session_id, status FROM fee_generation_batch WHERE school_id=? AND requested_student_ids LIKE '%STU-BATCHWRITE%'", SCHOOL);
        assertThat(batchRow.get("academic_session")).isEqualTo(LABEL_2025);
        assertThat(batchRow.get("academic_session_id")).isEqualTo(SESSION_2025);
        assertThat(batchRow.get("status")).isEqualTo("COMPLETED");
    }

    /** Task 20: after successful generation, the StudentFeeAssignment still retains the correct
     * academic_session_id through its own status mutation — proven here specifically via a row
     * that never had it set by assign() (inserted with the pre-D3-shaped raw insertAssignment
     * helper), so this is a genuine proof of generateForStudent's own populate-on-success path,
     * not merely that assign() already set it earlier. */
    @Test void generate_successfulGeneration_assignmentRetainsCorrectSessionIdThroughStatusMutation() {
        insertStudent("STU-RETAIN-ID", CLASS_9, "9");
        insertEnrollment("STU-RETAIN-ID", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignment("STU-RETAIN-ID", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-RETAIN-ID"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");
        assertThat(results.getFirst().successful()).isTrue();

        Long assignmentSessionId = jdbc.queryForObject(
                "SELECT academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-RETAIN-ID'", Long.class, SCHOOL);
        assertThat(assignmentSessionId).isEqualTo(SESSION_2025);
    }

    /** Task 21: a StudentFeeAssignment manually left in a conflicted state (label says 2025-2026,
     * but its stored id already points at the DIFFERENT, same-school 2026-2027 session — the
     * composite FK permits this since both sessions belong to SCHOOL) must fail closed rather
     * than silently rewriting the id to match the newly-resolved session. */
    /** Financial AcademicSession Authority, Phase D4 — this fixture predates the D4 lookup
     * migration in shape (updated from the D3-era test that originally expected
     * applyAcademicSessionIdentity's own IllegalStateException here). Since assign()'s upsert
     * lookup is now id-based, this row (label=2025-2026, id=SESSION_2026) is simply never found
     * by findBySchoolIdAndStudentIdAndAcademicSessionId(..., SESSION_2025) — the code falls
     * through to orElseGet(new), attempts to INSERT a second row, and the unchanged label-based
     * uq_student_fee_assignment constraint rejects it as a duplicate (school_id, student_id,
     * academic_session). Still genuinely fail-closed — no duplicate ever commits, the existing
     * conflicted row is completely untouched — just via a different, still-understandable
     * exception (a named unique-constraint violation) than the earlier code path produced. */
    @Test void assign_conflictingExistingAcademicSessionId_failsClosed_rejectedByTheUnchangedLabelUniqueConstraint() {
        insertStudent("STU-CONFLICT", CLASS_9, "9");
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, academic_session_id, status) VALUES (?,?,?,?,'READY')",
                SCHOOL, "STU-CONFLICT", LABEL_2025, SESSION_2026);
        TestTransaction.flagForCommit(); TestTransaction.end();

        assertThatThrownBy(() -> service.assign(
                new AssignmentRequest(List.of("STU-CONFLICT"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), false, "ip"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("uq_student_fee_assignment");

        // No duplicate ever committed — exactly the original row survives, completely unchanged.
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM student_fee_assignment WHERE school_id=? AND student_id='STU-CONFLICT'", Integer.class, SCHOOL);
        assertThat(rowCount).isEqualTo(1);
        Long persistedSessionId = jdbc.queryForObject(
                "SELECT academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-CONFLICT'", Long.class, SCHOOL);
        assertThat(persistedSessionId).isEqualTo(SESSION_2026);
    }

    /** Phase D3 final audit, Tasks 5/6: the same conflicted-identity scenario, but reached via
     * generate() rather than assign() — the required safety property is that a conflicting
     * non-null StudentFeeAssignment.academicSessionId must NEVER allow new StudentFees/
     * StudentFeesLineItem rows to be created for the differently-resolved session. The identity
     * check now runs immediately after the assignment lock in generateForStudent, before any fee
     * calculation or financial write — this proves it, rather than merely relying on
     * transactional rollback as an implicit, unstated safety net. */
    /** Financial AcademicSession Authority, Phase D4 — updated from the D3-era test that expected
     * applyAcademicSessionIdentity's own conflict exception here. Since the pessimistic
     * generation lock is now id-based (findForGenerationUpdateByAcademicSessionId), this fixture
     * (label=2025-2026, id=SESSION_2026) is simply never found when locking for SESSION_2025 — it
     * behaves exactly like "no assignment for this session" (a graceful, non-throwing result),
     * not a thrown conflict. markGenerationFailed is therefore never invoked either, since
     * generateForStudent returns normally rather than throwing — the row is left completely
     * untouched, not merely "not overwritten." Still fail-closed in every safety-relevant sense:
     * zero StudentFees, zero line items, zero duplicate, an understandable result message. */
    @Test void generate_conflictingExistingAcademicSessionId_isTreatedAsNoAssignmentForThisSession_createsNoFinancialData() {
        insertStudent("STU-GEN-CONFLICT", CLASS_9, "9");
        insertEnrollment("STU-GEN-CONFLICT", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, academic_session_id, status, selected_months) VALUES (?,?,?,?,'READY',?)",
                SCHOOL, "STU-GEN-CONFLICT", LABEL_2025, SESSION_2026, "1");
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-GEN-CONFLICT"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isFalse();
        assertThat(results.getFirst().message()).contains("not assigned");
        assertThat(feeRowCount("STU-GEN-CONFLICT", LABEL_2025)).isZero();
        Integer lineItemCount = jdbc.queryForObject(
                "SELECT count(*) FROM student_fees_line_item WHERE school_id=? AND student_id='STU-GEN-CONFLICT'", Integer.class, SCHOOL);
        assertThat(lineItemCount).isZero();
        // Completely untouched — the lock never found this row at all, so nothing about it
        // (status, id) was ever mutated, not even by markGenerationFailed's bookkeeping.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-GEN-CONFLICT'", SCHOOL);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("academic_session_id")).isEqualTo(SESSION_2026);
    }

    /** Task 22: two schools can legitimately share the same session label — assign() for SCHOOL
     * must resolve only SCHOOL's own session, never the other school's, even though both are
     * named "2025-2026". The composite FK is the DB backstop; this proves service-level
     * resolution is independently school-scoped too. */
    @Test void assign_tenantIsolation_neverResolvesAnotherSchoolsSessionId() {
        long otherSchool = -98099L;
        long otherSession = -98098L;
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'FeeWorkflow Other IT','TRIAL','feeworkflow-other-it',4,8,'Asia/Kolkata')", otherSchool);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)", otherSession, otherSchool, LABEL_2025);
        insertStudent("STU-TENANT", CLASS_9, "9");
        TestTransaction.flagForCommit(); TestTransaction.end();

        try {
            List<StudentFeeAssignment> saved = service.assign(
                    new AssignmentRequest(List.of("STU-TENANT"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), false, "ip");

            assertThat(saved.getFirst().getAcademicSessionId()).isEqualTo(SESSION_2025);
            assertThat(saved.getFirst().getAcademicSessionId()).isNotEqualTo(otherSession);
        } finally {
            jdbc.update("DELETE FROM academic_session WHERE id=?", otherSession);
            jdbc.update("DELETE FROM school WHERE id=?", otherSchool);
        }
    }

    // ── Financial AcademicSession Authority, Phase D4 — query/lock authority migration ────────

    /** Task 15: assign() must find and update an EXISTING row purely by its authoritative id,
     * even when that row's raw label snapshot is malformed — the concrete proof the query
     * migration matters, not merely that it still works when the label happens to be correct. */
    @Test void assign_findsExistingRowByAuthoritativeId_evenWhenRawLabelSnapshotIsMalformed() {
        insertStudent("STU-MALFORMED-ASSIGN", CLASS_9, "9");
        insertAssignmentWithLabelAndSessionId("STU-MALFORMED-ASSIGN", "MALFORMED-LABEL", SESSION_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<StudentFeeAssignment> saved = service.assign(
                new AssignmentRequest(List.of("STU-MALFORMED-ASSIGN"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1, 2), null, null), false, "ip");

        // Exactly one row exists — the id-based lookup found and updated the existing malformed-
        // label row rather than creating a duplicate (which the still-label-based unique
        // constraint would have permitted here, since 'MALFORMED-LABEL' never matched LABEL_2025).
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM student_fee_assignment WHERE school_id=? AND student_id='STU-MALFORMED-ASSIGN'", Integer.class, SCHOOL);
        assertThat(count).isEqualTo(1);
        assertThat(saved.getFirst().getAcademicSession()).isEqualTo(LABEL_2025); // corrected back to the real label
        assertThat(saved.getFirst().getAcademicSessionId()).isEqualTo(SESSION_2025);
    }

    /** Task 16: the pessimistic generation lock must find and lock the SAME row purely by its
     * authoritative id — the old label-based lock would have searched for
     * academic_session = LABEL_2025 and found nothing here (this row's label is malformed),
     * returning "Student is not assigned for fees" and generating zero StudentFees rows. */
    @Test void generate_locksExistingRowByAuthoritativeId_evenWhenRawLabelSnapshotIsMalformed() {
        insertStudent("STU-MALFORMED-LOCK", CLASS_9, "9");
        insertEnrollment("STU-MALFORMED-LOCK", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignmentWithLabelAndSessionId("STU-MALFORMED-LOCK", "MALFORMED-LABEL", SESSION_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-MALFORMED-LOCK"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isTrue();
        assertThat(results.getFirst().generated()).isEqualTo(1);
        assertThat(feeRowCount("STU-MALFORMED-LOCK", LABEL_2025)).isEqualTo(1);
    }

    /** Task 17: proves the id-based assignment lookup is genuinely school-scoped, not merely
     * relying on AcademicSession ids being globally unique PKs. A second school has its own
     * assignment row (under its own, distinct student id — student.student_id is this schema's
     * actual global PRIMARY KEY, confirmed directly against the live schema, so it cannot be
     * reused across schools) for a session sharing the identical label — assign() for SCHOOL
     * must create/find only its own row, never touching or reusing the other school's. */
    @Test void assign_tenantIsolatedLookup_neverTouchesAnotherSchoolsAssignmentRow() {
        // student.student_id is this schema's actual global PRIMARY KEY (student_pkey), not
        // school-scoped — confirmed directly against the real schema during this audit — so a
        // genuinely distinct student id is used for the other school's row rather than a
        // same-string collision, which the database itself would refuse to store at all.
        long otherSchool = -98096L;
        long otherSession = -98095L;
        jdbc.update("INSERT INTO school (id,active,created_at,name,plan,slug,academic_year_start_month,periods_per_day,timezone) VALUES " +
                "(?,true,CURRENT_TIMESTAMP,'FeeWorkflow Other IT 2','TRIAL','feeworkflow-other-it-2',4,8,'Asia/Kolkata')", otherSchool);
        jdbc.update("INSERT INTO academic_session (id,school_id,label,start_date,end_date,is_current,created_at) VALUES " +
                "(?,?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)", otherSession, otherSchool, LABEL_2025);
        insertStudent("STU-TENANT-2", CLASS_9, "9");
        jdbc.update("INSERT INTO student (student_id,school_id,status,class_name,takes_bus,joining_date) VALUES " +
                "('STU-TENANT-2-OTHER', ?, 'ACTIVE', '9', false, DATE '2025-04-01')", otherSchool);
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, academic_session_id, status, selected_months) VALUES (?,?,?,?,'GENERATED',?)",
                otherSchool, "STU-TENANT-2-OTHER", LABEL_2025, otherSession, "1,2,3,4,5,6,7,8,9,10,11,12");
        TestTransaction.flagForCommit(); TestTransaction.end();

        try {
            List<StudentFeeAssignment> saved = service.assign(
                    new AssignmentRequest(List.of("STU-TENANT-2"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), false, "ip");

            assertThat(saved.getFirst().getAcademicSessionId()).isEqualTo(SESSION_2025);
            assertThat(saved.getFirst().getSchoolId()).isEqualTo(SCHOOL);

            Integer schoolRowCount = jdbc.queryForObject(
                    "SELECT count(*) FROM student_fee_assignment WHERE school_id=? AND student_id='STU-TENANT-2'", Integer.class, SCHOOL);
            assertThat(schoolRowCount).isEqualTo(1);

            // The other school's row remains completely untouched — still GENERATED, still its own id.
            Map<String, Object> otherRow = jdbc.queryForMap(
                    "SELECT status, academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-TENANT-2-OTHER'", otherSchool);
            assertThat(otherRow.get("status")).isEqualTo("GENERATED");
            assertThat(otherRow.get("academic_session_id")).isEqualTo(otherSession);
        } finally {
            jdbc.update("DELETE FROM student_fee_assignment WHERE school_id=? AND student_id='STU-TENANT-2-OTHER'", otherSchool);
            jdbc.update("DELETE FROM student WHERE school_id=? AND student_id='STU-TENANT-2-OTHER'", otherSchool);
            jdbc.update("DELETE FROM academic_session WHERE id=?", otherSession);
            jdbc.update("DELETE FROM school WHERE id=?", otherSchool);
        }
    }

    /** Task 18: same school, same student, two DIFFERENT session assignments — an assign() or
     * generate() call scoped to Session A must only ever touch A's own row; Session B's
     * assignment (status, generatedAt, failureReason, academicSessionId) must remain byte-for-
     * byte unchanged throughout. */
    @Test void multiSessionSameStudent_operationsForSessionA_leaveSessionBAssignmentCompletelyUntouched() {
        insertStudent("STU-MULTI-SESSION", CLASS_9, "9");
        insertEnrollment("STU-MULTI-SESSION", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        insertAssignmentWithLabelAndSessionId("STU-MULTI-SESSION", LABEL_2025, SESSION_2025);
        insertAssignmentWithLabelAndSessionId("STU-MULTI-SESSION", LABEL_2026, SESSION_2026);
        TestTransaction.flagForCommit(); TestTransaction.end();

        service.assign(new AssignmentRequest(List.of("STU-MULTI-SESSION"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1, 2), null, null), false, "ip");

        Map<String, Object> sessionBAfterAssign = jdbc.queryForMap(
                "SELECT status, academic_session_id, selected_months FROM student_fee_assignment WHERE school_id=? AND student_id='STU-MULTI-SESSION' AND academic_session_id=?",
                SCHOOL, SESSION_2026);
        assertThat(sessionBAfterAssign.get("status")).isEqualTo("READY");
        assertThat(sessionBAfterAssign.get("selected_months")).isNull(); // insertAssignmentWithLabelAndSessionId never set it — untouched by A's assign call
        assertThat(sessionBAfterAssign.get("academic_session_id")).isEqualTo(SESSION_2026);

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-MULTI-SESSION"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1, 2), null, null), "ip");
        assertThat(results.getFirst().successful()).isTrue();

        Map<String, Object> sessionBAfterGenerate = jdbc.queryForMap(
                "SELECT status, generated_at, failure_reason, academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-MULTI-SESSION' AND academic_session_id=?",
                SCHOOL, SESSION_2026);
        assertThat(sessionBAfterGenerate.get("status")).isEqualTo("READY"); // never touched by A's generate call
        assertThat(sessionBAfterGenerate.get("generated_at")).isNull();
        assertThat(sessionBAfterGenerate.get("failure_reason")).isNull();
        assertThat(sessionBAfterGenerate.get("academic_session_id")).isEqualTo(SESSION_2026);

        Map<String, Object> sessionAAfterGenerate = jdbc.queryForMap(
                "SELECT status, academic_session_id FROM student_fee_assignment WHERE school_id=? AND student_id='STU-MULTI-SESSION' AND academic_session_id=?",
                SCHOOL, SESSION_2025);
        assertThat(sessionAAfterGenerate.get("status")).isNotEqualTo("READY"); // A's own row DID get updated
        assertThat(sessionAAfterGenerate.get("academic_session_id")).isEqualTo(SESSION_2025);
        assertThat(feeRowCount("STU-MULTI-SESSION", LABEL_2025)).isEqualTo(2);
    }

    /** Task 22: a legacy/synthetic row with academic_session_id = NULL is treated by the new
     * id-based lock exactly like "no assignment for this session" — a graceful, non-throwing
     * result, never an exception, never a duplicate, never financial corruption. Production
     * carried zero rows in this table before D3, so this shape is only ever reachable via a
     * manually-constructed fixture, never real data. */
    @Test void generate_legacyNullIdAssignment_isTreatedAsNoAssignmentForThisSession() {
        insertStudent("STU-NULL-ID", CLASS_9, "9");
        insertEnrollment("STU-NULL-ID", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, status, selected_months) VALUES (?,?,?,'READY',?)",
                SCHOOL, "STU-NULL-ID", LABEL_2025, "1"); // academic_session_id deliberately left NULL
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> results = service.generate(
                new AssignmentRequest(List.of("STU-NULL-ID"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");

        assertThat(results.getFirst().successful()).isFalse();
        assertThat(results.getFirst().message()).contains("not assigned");
        assertThat(feeRowCount("STU-NULL-ID", LABEL_2025)).isZero();
    }

    /** Final pre-commit audit, Task 12: the same legacy-null-id shape, but via assign() rather
     * than generate() — the id-based upsert lookup misses this row (NULL never matches a real
     * id), falls through to orElseGet(new), and the unchanged label-based unique constraint
     * rejects the resulting duplicate-label insert attempt. Same mechanism, same safe outcome,
     * as the label-A/id-B conflict test above — documented here explicitly for the NULL-id
     * origin specifically, since production's zero pre-D3 rows make this the only realistic
     * "legacy-shaped" row this code could ever actually encounter. */
    @Test void assign_legacyNullIdRowWithSameLabel_failsClosed_rejectedByTheUnchangedLabelUniqueConstraint() {
        insertStudent("STU-NULL-ID-ASSIGN", CLASS_9, "9");
        jdbc.update("INSERT INTO student_fee_assignment (school_id, student_id, academic_session, status) VALUES (?,?,?,'READY')",
                SCHOOL, "STU-NULL-ID-ASSIGN", LABEL_2025); // academic_session_id deliberately left NULL
        TestTransaction.flagForCommit(); TestTransaction.end();

        assertThatThrownBy(() -> service.assign(
                new AssignmentRequest(List.of("STU-NULL-ID-ASSIGN"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), false, "ip"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("uq_student_fee_assignment");

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM student_fee_assignment WHERE school_id=? AND student_id='STU-NULL-ID-ASSIGN'", Integer.class, SCHOOL);
        assertThat(rowCount).isEqualTo(1);
    }

    /** Task 24/25 supporting evidence lives in the audit report (EXPLAIN ANALYZE against a
     * synthetic 25,000-row single-school dataset) — no schema change accompanies this phase. */

    /** Task 13/24/29: retry selects the original batch purely by its numeric PK (unchanged) and
     * reconstructs the request from the batch's own stored label — proving the retry's freshly
     * resolved session id matches the ORIGINAL batch's own stored id, i.e. retry can never
     * accidentally resolve a different AcademicSession merely because labels happen to match. A
     * genuinely failed batch is produced here the same way an admin would hit it: an assignment
     * with no matching enrollment causes generateForStudent's authoritative-class resolution to
     * fail for that one student. */
    @Test void retryGenerationBatch_resolvesTheSameSessionIdentityAsTheOriginalBatch() {
        insertStudent("STU-RETRY", CLASS_9, "9");
        // Enrollment-covered for a DIFFERENT session only (2026-2027) — a student with NO
        // enrollment rows at all falls back to the legacy Student.className path and succeeds
        // (see the fixtures() comment above); this instead matches noEnrollmentForTargetSession's
        // own recipe (Test 2) for a genuine, real failure: enrollment-covered generally, but not
        // for the session actually being generated.
        insertEnrollment("STU-RETRY", SESSION_2026, "ACTIVE", CLASS_10, LocalDate.of(2026, 4, 1), null);
        insertAssignment("STU-RETRY", LABEL_2025);
        TestTransaction.flagForCommit(); TestTransaction.end();

        List<GenerationResult> firstAttempt = service.generate(
                new AssignmentRequest(List.of("STU-RETRY"), LABEL_2025, LocalDate.of(2025, 4, 1), List.of(1), null, null), "ip");
        assertThat(firstAttempt.getFirst().successful()).isFalse();

        Long originalBatchId = jdbc.queryForObject(
                "SELECT id FROM fee_generation_batch WHERE school_id=? AND requested_student_ids LIKE '%STU-RETRY%' ORDER BY id ASC LIMIT 1",
                Long.class, SCHOOL);
        Long originalSessionId = jdbc.queryForObject(
                "SELECT academic_session_id FROM fee_generation_batch WHERE id=?", Long.class, originalBatchId);
        assertThat(originalSessionId).isEqualTo(SESSION_2025);

        // Fix the underlying problem (add the missing enrollment), then retry — same batch
        // selected by id, same reconstructed label, must resolve to the identical session id.
        insertEnrollment("STU-RETRY", SESSION_2025, "ACTIVE", CLASS_9, LocalDate.of(2025, 4, 1), null);

        List<GenerationResult> retryResults = service.retryGenerationBatch(originalBatchId, "ip");
        assertThat(retryResults.getFirst().successful()).isTrue();

        Long retryBatchSessionId = jdbc.queryForObject(
                "SELECT academic_session_id FROM fee_generation_batch WHERE retry_of_batch_id=?", Long.class, originalBatchId);
        assertThat(retryBatchSessionId).isEqualTo(originalSessionId);
    }
}
