package com.indraacademy.ias_management.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.indraacademy.ias_management.dto.ManualPaymentRequest;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Financial AcademicSession Authority, Phase B3/B4 — real-Postgres proof that
 * {@link StudentFeesService#recordManualPayment} resolves the client-supplied session label to
 * an authoritative {@code AcademicSession} row (never persisting an unchecked string), rejects
 * an unresolvable label outright, and that the resulting {@code Payment}/allocation rows carry
 * the resolved session's real id — plus that this resolution is genuinely tenant-scoped (two
 * schools sharing the same session label must never cross-resolve).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StudentFeesService.class, AcademicSessionService.class, FeeCalculationService.class,
        AuditService.class, com.indraacademy.ias_management.util.SecurityUtil.class,
        StudentFeesServiceSessionAuthorityPostgresIT.RealObjectMapperConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class StudentFeesServiceSessionAuthorityPostgresIT {

    @TestConfiguration
    static class RealObjectMapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    private static final long SCHOOL_A = -98501L;
    private static final long SCHOOL_B = -98502L;
    private static final String LABEL = "2025-2026";

    @MockBean private BusinessNotificationService businessNotifications;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StudentFeesService service;
    @Autowired private PaymentRepository paymentRepository;

    private long sessionAId;
    private long sessionBId;

    private void seed() {
        jdbc.update("INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) VALUES " +
                        "(?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                SCHOOL_A, LABEL);
        sessionAId = jdbc.queryForObject("SELECT id FROM academic_session WHERE school_id=? AND label=?", Long.class, SCHOOL_A, LABEL);
        jdbc.update("INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) VALUES " +
                        "(?,?,DATE '2025-04-01',DATE '2026-03-31',false,CURRENT_TIMESTAMP)",
                SCHOOL_B, LABEL);
        sessionBId = jdbc.queryForObject("SELECT id FROM academic_session WHERE school_id=? AND label=?", Long.class, SCHOOL_B, LABEL);
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    private void insertStudentFees(long schoolId, String studentId, int month) {
        insertStudentFeesWithLabelAndSessionId(schoolId, studentId, month, LABEL, schoolId == SCHOOL_A ? sessionAId : sessionBId);
    }

    private void insertStudentFeesWithLabelAndSessionId(long schoolId, String studentId, int month, String yearLabel, long academicSessionId) {
        jdbc.update("INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, discount_amount, snapshot_status) " +
                        "VALUES (?, ?, '6A', ?, false, false, ?, ?, 0, false, 0, 1000, 0, 0, 'COMPUTED')",
                schoolId, studentId, month, yearLabel, academicSessionId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_student_fees_allocation WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM payment WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM student_fees WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
        jdbc.update("DELETE FROM academic_session WHERE school_id IN (?,?)", SCHOOL_A, SCHOOL_B);
    }

    /** SecurityUtil is a real bean here (not mocked, matching this repo's *PostgresIT
     * convention for services whose own tenant-scoping logic is exactly what's under test) —
     * it reads from a thread-bound SchoolContext rather than a JWT in this test environment. */
    private void actingAsSchool(long schoolId) {
        com.indraacademy.ias_management.util.SchoolContext.set(schoolId);
    }

    @Test
    void manualPayment_resolvesSessionAndPopulatesAcademicSessionIdOnPaymentAndAllocation() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFees(SCHOOL_A, "STU-MANUAL-A", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-A", LABEL, "100000000000", new BigDecimal("1000"));
        Payment saved = service.recordManualPayment(request, "127.0.0.1");

        assertThat(saved.getAcademicSessionId()).isEqualTo(sessionAId);
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM payment WHERE id=?", Long.class, saved.getId()))
                .isEqualTo(sessionAId);
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM payment_student_fees_allocation WHERE payment_id=?",
                Long.class, saved.getId())).isEqualTo(sessionAId);
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    /** Online Convenience Fee refactor: manual/offline payments must never carry a gateway or
     * Edunexify transaction fee — those exist only for the online (Razorpay) channel. */
    @Test
    void manualPayment_neverChargesOnlineConvenienceFee_principalEqualsAllocatableAmount() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFees(SCHOOL_A, "STU-MANUAL-NOFEE", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-NOFEE", LABEL, "100000000000", new BigDecimal("1000"));
        Payment saved = service.recordManualPayment(request, "127.0.0.1");

        assertThat(saved.getPricingVersion()).isEqualTo("MANUAL");
        assertThat(saved.getGatewayRateBps()).isNull();
        assertThat(saved.getGatewayTaxRateBps()).isNull();
        assertThat(saved.getGatewayRecoveryFeePaise()).isEqualTo(0L);
        assertThat(saved.getEdunexifyTransactionFeePaise()).isEqualTo(0L);
        assertThat(saved.getOnlineConvenienceFeePaise()).isEqualTo(0L);
        // amountReceived (₹1000 = 100000 paise) minus additionalCharges (0 here) = principal.
        assertThat(saved.getSchoolLiabilityPrincipalPaise()).isEqualTo(100000L);
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    @Test
    void manualPayment_unresolvableSessionLabel_rejectsWithoutCreatingAnyRow() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFees(SCHOOL_A, "STU-MANUAL-BAD", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-BAD", "2099-2100", "100000000000", new BigDecimal("1000"));

        assertThatThrownBy(() -> service.recordManualPayment(request, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcademicSession not found");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE student_id='STU-MANUAL-BAD'", Integer.class))
                .isZero();
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    /** Tenant safety: School A and School B both have a session literally labeled "2025-2026"
     * (allowed — AcademicSession.label is unique per school, not globally). A School-A manual
     * payment must resolve School A's session id, never School B's, even though the labels are
     * byte-identical strings. */
    @Test
    void manualPayment_sameLabelDifferentSchool_resolvesOnlyItsOwnSchoolsSession() {
        seed();
        actingAsSchool(SCHOOL_B);
        insertStudentFees(SCHOOL_B, "STU-MANUAL-B", 1);

        ManualPaymentRequest request = manualRequest("STU-MANUAL-B", LABEL, "100000000000", new BigDecimal("1000"));
        Payment saved = service.recordManualPayment(request, "127.0.0.1");

        assertThat(saved.getAcademicSessionId()).isEqualTo(sessionBId);
        assertThat(saved.getAcademicSessionId()).isNotEqualTo(sessionAId);
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    /** Financial AcademicSession Authority, Phase C2 — the most important correctness proof:
     * a StudentFees row whose display-only {@code year} snapshot has been deliberately
     * corrupted/mismatched must still be found and correctly locked by the new authoritative-id
     * lookup, since {@code academic_session_id} — not the label — is now what operational
     * queries select by. This is the concrete demonstration of why the migration matters: the
     * old label-based query would have missed this exact row. */
    @Test
    void manualPayment_findsRowByAuthoritativeId_evenWhenRawLabelSnapshotIsMalformed() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFeesWithLabelAndSessionId(SCHOOL_A, "STU-MALFORMED", 1, "2099-2100", sessionAId);

        // Amount comfortably exceeds base due (1000) plus any possible date-dependent late fee
        // (max 30*21=630, per StudentFeesService.calculateLateFees) — the point of this test is
        // authoritative-id row selection, not exercising the separate late-fee schedule.
        ManualPaymentRequest request = manualRequest("STU-MALFORMED", LABEL, "100000000000", new BigDecimal("2000"));
        Payment saved = service.recordManualPayment(request, "127.0.0.1");

        assertThat(saved.getId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT paid FROM student_fees WHERE student_id='STU-MALFORMED'", Boolean.class))
                .isTrue();
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    /** Manual-payment correct-row selection: two sessions can legitimately both have a "month
     * 1" liability for the same student (e.g. an academic-month-1 row exists in both the
     * current and a historical session). Paying against one session's label must only ever
     * touch that session's own row — proven by authoritative-id selection, not by hoping the
     * label happens to disambiguate them (it always would today, but the id is what the query
     * now actually uses). */
    @Test
    void manualPayment_selectsOnlyTheTargetSessionsLiability_leavesTheOtherSessionsRowUntouched() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFeesWithLabelAndSessionId(SCHOOL_A, "STU-TWO-SESSIONS", 1, LABEL, sessionAId);
        String otherLabel = "2024-2025";
        jdbc.update("INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) VALUES " +
                        "(?,?,DATE '2024-04-01',DATE '2025-03-31',false,CURRENT_TIMESTAMP)",
                SCHOOL_A, otherLabel);
        long otherSessionId = jdbc.queryForObject("SELECT id FROM academic_session WHERE school_id=? AND label=?", Long.class, SCHOOL_A, otherLabel);
        insertStudentFeesWithLabelAndSessionId(SCHOOL_A, "STU-TWO-SESSIONS", 1, otherLabel, otherSessionId);

        // See the comment in manualPayment_findsRowByAuthoritativeId_evenWhenRawLabelSnapshotIsMalformed
        // for why this amount exceeds the base due — comfortably covers any date-dependent late fee too.
        ManualPaymentRequest request = manualRequest("STU-TWO-SESSIONS", LABEL, "100000000000", new BigDecimal("2000"));
        service.recordManualPayment(request, "127.0.0.1");

        assertThat(jdbc.queryForObject(
                "SELECT paid FROM student_fees WHERE student_id='STU-TWO-SESSIONS' AND academic_session_id=?",
                Boolean.class, sessionAId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT paid FROM student_fees WHERE student_id='STU-TWO-SESSIONS' AND academic_session_id=?",
                Boolean.class, otherSessionId)).isFalse();
        // @AfterEach's cleanUp() already deletes every academic_session row under SCHOOL_A,
        // which includes this test's extra "2024-2025" row.
        com.indraacademy.ias_management.util.SchoolContext.clear();
    }

    private ManualPaymentRequest manualRequest(String studentId, String session, String monthSelection, BigDecimal amount) {
        ManualPaymentRequest request = new ManualPaymentRequest();
        request.setStudentId(studentId);
        request.setStudentName("IT Student");
        request.setClassName("6A");
        request.setSession(session);
        request.setMonthSelectionString(monthSelection);
        request.setAmountReceived(amount);
        request.setPaymentMode("CASH");
        return request;
    }

    // ── Webhook settlement correctness fix: markFeesAsPaid must trust Payment.schoolId,     ──
    // ── never an ambient SchoolContext (which is never populated for /api/webhooks/*)       ──

    /** The exact ambient state a real Razorpay webhook request runs under — JwtAuthFilter never
     * touches /api/webhooks/*, so SchoolContext is never populated for it. Before this fix,
     * markFeesAsPaid derived schoolId from securityUtil.getSchoolId() (== SchoolContext.get()),
     * which would be null here, causing every StudentFees lookup to match nothing and the whole
     * method to throw. This proves the fix: tenant identity now comes from payment.getSchoolId()
     * alone, so settlement succeeds with zero ambient context. */
    @Test
    void markFeesAsPaid_succeedsWithNoAmbientSchoolContext_usingOnlyPaymentSchoolId() {
        seed();
        com.indraacademy.ias_management.util.SchoolContext.clear();
        insertStudentFeesWithLabelAndSessionId(SCHOOL_A, "STU-NO-CONTEXT", 1, LABEL, sessionAId);

        Payment payment = persistPayment(SCHOOL_A, "STU-NO-CONTEXT", LABEL, sessionAId, "100000000000", 200000);

        service.markFeesAsPaid(payment);

        assertThat(jdbc.queryForObject("SELECT paid FROM student_fees WHERE student_id='STU-NO-CONTEXT'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_student_fees_allocation WHERE payment_id=?", Integer.class, payment.getId()))
                .isGreaterThan(0);
    }

    /** Defense-in-depth: a stale/wrong ambient SchoolContext must never redirect a financial
     * allocation — Payment.schoolId is authoritative regardless of whatever the current thread
     * happens to have set. School B's own identically-shaped row must remain completely
     * untouched. */
    @Test
    void markFeesAsPaid_ignoresWrongAmbientSchoolContext_usesOnlyPaymentSchoolId() {
        seed();
        insertStudentFeesWithLabelAndSessionId(SCHOOL_A, "STU-WRONG-CONTEXT", 1, LABEL, sessionAId);
        insertStudentFeesWithLabelAndSessionId(SCHOOL_B, "STU-WRONG-CONTEXT", 1, LABEL, sessionBId);

        Payment payment = persistPayment(SCHOOL_A, "STU-WRONG-CONTEXT", LABEL, sessionAId, "100000000000", 200000);

        // Deliberately wrong ambient context — School B — while the Payment itself belongs to School A.
        com.indraacademy.ias_management.util.SchoolContext.set(SCHOOL_B);
        service.markFeesAsPaid(payment);
        com.indraacademy.ias_management.util.SchoolContext.clear();

        assertThat(jdbc.queryForObject(
                "SELECT paid FROM student_fees WHERE school_id=? AND student_id='STU-WRONG-CONTEXT'", Boolean.class, SCHOOL_A))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT paid FROM student_fees WHERE school_id=? AND student_id='STU-WRONG-CONTEXT'", Boolean.class, SCHOOL_B))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_student_fees_allocation WHERE payment_id=? AND school_id=?",
                Integer.class, payment.getId(), SCHOOL_B))
                .isZero();
    }

    /** Missing-school-id Payment must fail closed rather than silently falling back to ambient
     * SchoolContext. */
    @Test
    void markFeesAsPaid_missingPaymentSchoolId_failsClosed_neverFallsBackToAmbientContext() {
        seed();
        actingAsSchool(SCHOOL_A);
        insertStudentFeesWithLabelAndSessionId(SCHOOL_A, "STU-NULL-SCHOOL", 1, LABEL, sessionAId);
        Payment payment = persistPayment(SCHOOL_A, "STU-NULL-SCHOOL", LABEL, sessionAId, "100000000000", 200000);
        payment.setSchoolId(null);
        paymentRepository.save(payment);

        assertThatThrownBy(() -> service.markFeesAsPaid(payment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schoolId");

        assertThat(jdbc.queryForObject("SELECT paid FROM student_fees WHERE student_id='STU-NULL-SCHOOL'", Boolean.class)).isFalse();
        com.indraacademy.ias_management.util.SchoolContext.clear();

        // This row's school_id is deliberately null, so @AfterEach's cleanUp() (which filters
        // on school_id IN (SCHOOL_A, SCHOOL_B)) can never find it — delete it explicitly here.
        jdbc.update("DELETE FROM payment WHERE id = ?", payment.getId());
    }

    private Payment persistPayment(long schoolId, String studentId, String sessionLabel, long academicSessionId,
                                    String monthSelectionBitmask, int amountPaise) {
        // manualPaymentMode deliberately left null — mirrors an online/webhook-settled payment
        // (recordManualPayment always sets it; this simulates the RazorpayService-built shape,
        // which is the exact caller class this fix is about).
        Payment payment = new Payment(studentId, "IT Student", "6A", sessionLabel, monthSelectionBitmask, amountPaise,
                "PAY-" + studentId, "ORDER-" + studentId, java.time.LocalDateTime.now(), "success",
                0, 0, 0, 0, 0, 0, false, amountPaise, 0, 0);
        payment.setSchoolId(schoolId);
        payment.setAcademicSessionId(academicSessionId);
        payment.setRazorpaySignature("TEST-SIGNATURE");
        return paymentRepository.save(payment);
    }
}
