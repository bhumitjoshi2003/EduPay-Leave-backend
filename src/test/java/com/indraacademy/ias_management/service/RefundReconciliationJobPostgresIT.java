package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Refund-Integrity Hardening, Phase D — real-PostgreSQL proof for the scheduled reconciliation
 * job's candidate-selection JPQL and for genuine concurrent-reconciliation safety (Task 7):
 * two real threads resolving the SAME provider refund state for the SAME refund, relying on
 * nothing but the Payment row lock and the status re-check already proven in Phase C — no
 * distributed lock exists or is needed (see {@link RefundReconciliationJob}'s own javadoc for
 * why). Follows the established *PostgresIT convention.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RefundSettlementService.class, RefundReconciliationJob.class, FeeCalculationService.class, AuditService.class,
        com.indraacademy.ias_management.util.SecurityUtil.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class RefundReconciliationJobPostgresIT {

    private static final long SCHOOL = -98401L;

    @MockBean private BusinessNotificationService businessNotifications;
    @MockBean private RazorpayService razorpayService;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundSettlementService refundSettlementService;
    @Autowired private RefundReconciliationJob reconciliationJob;
    @Autowired private com.indraacademy.ias_management.repository.RefundRepository refundRepository;

    @BeforeEach
    void endTestManagedTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        ReflectionTestUtils.setField(reconciliationJob, "enabled", true);
        ReflectionTestUtils.setField(reconciliationJob, "pendingMinAgeMinutes", 5L);
        ReflectionTestUtils.setField(reconciliationJob, "batchSize", 25);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM refund WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM payment WHERE school_id = ?", SCHOOL);
    }

    // ── Candidate query (real JPQL correctness) ─────────────────────────────────────────────

    @Test
    void candidateQuery_excludesNonPendingAndNullProviderIdAndTooFresh_includesStaleWithProviderId() {
        long paymentId = insertPayment(400000L, 300000L);
        long staleWithProviderId = insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_PENDING, "rfnd-stale", tenMinutesAgo());
        insertRefund(paymentId, 50000L, RefundSettlementService.STATUS_PENDING, null, tenMinutesAgo()); // no provider id
        insertRefund(paymentId, 50000L, RefundSettlementService.STATUS_PENDING, "rfnd-fresh", LocalDateTime.now()); // too fresh
        insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_SUCCESS, "rfnd-done", tenMinutesAgo()); // already terminal

        List<com.indraacademy.ias_management.entity.Refund> candidates = refundRepository
                .findStalePendingRefundsWithProviderId(LocalDateTime.now().minusMinutes(5), PageRequest.of(0, 25));

        assertThat(candidates).extracting(com.indraacademy.ias_management.entity.Refund::getId)
                .containsExactly(staleWithProviderId); // exactly the one real candidate, nothing else
    }

    @Test
    void successfulRefund_neverReappearsAsACandidate() {
        long paymentId = insertPayment(100000L, 100000L);
        insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_SUCCESS, "rfnd-success", tenMinutesAgo());

        List<com.indraacademy.ias_management.entity.Refund> candidates = refundRepository
                .findStalePendingRefundsWithProviderId(LocalDateTime.now().minusMinutes(5), PageRequest.of(0, 25));

        assertThat(candidates).isEmpty();
    }

    // ── Job-level: real candidate query + isolation across a mocked reconcileRefund ─────────

    @Test
    void jobBatch_realCandidateFromPostgres_oneThrowsAnotherStillProcessed() {
        long paymentId = insertPayment(400000L, 200000L);
        long refundA = insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_PENDING, "rfnd-job-a", tenMinutesAgo());
        long refundB = insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_PENDING, "rfnd-job-b", tenMinutesAgo());
        doThrow(new RuntimeException("simulated provider outage")).when(razorpayService).reconcileRefund(refundA);

        reconciliationJob.reconcileBatch();

        verify(razorpayService).reconcileRefund(refundA);
        verify(razorpayService).reconcileRefund(refundB); // still reached despite A's failure
    }

    @Test
    void jobBatch_disabled_neverQueriesOrCallsProvider() {
        insertRefund(insertPayment(100000L, 100000L), 100000L, RefundSettlementService.STATUS_PENDING, "rfnd-disabled", tenMinutesAgo());
        ReflectionTestUtils.setField(reconciliationJob, "enabled", false);

        reconciliationJob.poll();

        verifyNoInteractions(razorpayService);
    }

    // ── Reconciliation decision logic under real concurrency (Task 7) ───────────────────────

    @Test
    void twoWorkersSameRefund_providerProcessed_exactlyOneFinalization() throws Exception {
        long paymentId = insertPayment(200000L, 200000L);
        long refundId = insertRefund(paymentId, 200000L, RefundSettlementService.STATUS_PENDING, "rfnd-race-success", tenMinutesAgo());
        String paymentProviderId = paymentProviderId(paymentId);

        CompletableFuture<RefundSettlementService.ReconciliationResult> workerA = CompletableFuture.supplyAsync(() ->
                refundSettlementService.resolveFromProviderState("rfnd-race-success", RazorpayService.PROVIDER_STATUS_PROCESSED,
                        paymentProviderId, 200000L, "INR", "WORKER_A", "SYSTEM", null));
        CompletableFuture<RefundSettlementService.ReconciliationResult> workerB = CompletableFuture.supplyAsync(() ->
                refundSettlementService.resolveFromProviderState("rfnd-race-success", RazorpayService.PROVIDER_STATUS_PROCESSED,
                        paymentProviderId, 200000L, "INR", "WORKER_B", "SYSTEM", null));

        var resultA = workerA.get(10, TimeUnit.SECONDS);
        var resultB = workerB.get(10, TimeUnit.SECONDS);

        // Exactly one FINALIZED, the other NO_OP (whichever won the Payment row lock race) —
        // never both FINALIZED, never both NO_OP.
        List<RefundSettlementService.ReconciliationOutcome> outcomes = List.of(resultA.outcome(), resultB.outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(
                RefundSettlementService.ReconciliationOutcome.FINALIZED, RefundSettlementService.ReconciliationOutcome.NO_OP);

        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(200000L); // still consumed, not doubled, not released
    }

    @Test
    void twoWorkersSameRefund_providerFailed_reservationReleasedExactlyOnce() throws Exception {
        long paymentId = insertPayment(200000L, 100000L);
        long refundId = insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_PENDING, "rfnd-race-fail", tenMinutesAgo());
        String paymentProviderId = paymentProviderId(paymentId);

        CompletableFuture<RefundSettlementService.ReconciliationResult> workerA = CompletableFuture.supplyAsync(() ->
                refundSettlementService.resolveFromProviderState("rfnd-race-fail", RazorpayService.PROVIDER_STATUS_FAILED,
                        paymentProviderId, 100000L, "INR", "WORKER_A", "SYSTEM", null));
        CompletableFuture<RefundSettlementService.ReconciliationResult> workerB = CompletableFuture.supplyAsync(() ->
                refundSettlementService.resolveFromProviderState("rfnd-race-fail", RazorpayService.PROVIDER_STATUS_FAILED,
                        paymentProviderId, 100000L, "INR", "WORKER_B", "SYSTEM", null));

        var resultA = workerA.get(10, TimeUnit.SECONDS);
        var resultB = workerB.get(10, TimeUnit.SECONDS);

        List<RefundSettlementService.ReconciliationOutcome> outcomes = List.of(resultA.outcome(), resultB.outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(
                RefundSettlementService.ReconciliationOutcome.RELEASED, RefundSettlementService.ReconciliationOutcome.NO_OP);

        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_FAILED);
        assertThat(refundedAmountPaise(paymentId)).isZero(); // released exactly once, never negative
    }

    // ── Provider-pending stays untouched ─────────────────────────────────────────────────────

    @Test
    void stalePendingProviderStillPending_noMutationBeyondMetadataSync() {
        long paymentId = insertPayment(200000L, 100000L);
        long refundId = insertRefund(paymentId, 100000L, RefundSettlementService.STATUS_PENDING, "rfnd-still-pending", tenMinutesAgo());

        var result = refundSettlementService.resolveFromProviderState("rfnd-still-pending", RazorpayService.PROVIDER_STATUS_PENDING,
                paymentProviderId(paymentId), 100000L, "INR", "SYSTEM_RECONCILIATION", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(100000L);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private LocalDateTime tenMinutesAgo() {
        return LocalDateTime.now().minusMinutes(10);
    }

    private long insertPayment(long amountPaidPaise, long refundedAmountPaise) {
        String paymentId = "RJOB-IT-PAY-" + System.nanoTime();
        jdbc.update("INSERT INTO payment " +
                        "(school_id, student_id, student_name, class_name, session, month, amount, " +
                        "payment_id, order_id, payment_date, status, razorpay_signature, amount_paid, " +
                        "refunded_amount_paise, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, paid_manually) " +
                        "VALUES (?, 'RJOB-IT-STUDENT', 'IT Student', '6A', '2025-2026', '100000000000', ?, " +
                        "?, ?, ?, 'success', 'sig', ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)",
                SCHOOL, amountPaidPaise, paymentId, paymentId + "-ORDER", LocalDateTime.now(), amountPaidPaise, refundedAmountPaise);
        return jdbc.queryForObject("SELECT id FROM payment WHERE payment_id = ?", Long.class, paymentId);
    }

    private long insertRefund(long paymentId, long amountPaise, String status, String providerRefundId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO refund " +
                        "(payment_id, school_id, student_id, session, months_refunded, amount_paise, " +
                        "status, legacy_approximation, created_at, provider_refund_id) " +
                        "VALUES (?, ?, 'RJOB-IT-STUDENT', '2025-2026', '000000000000', ?, ?, false, ?, ?)",
                paymentId, SCHOOL, amountPaise, status, createdAt, providerRefundId);
        return jdbc.queryForObject(
                "SELECT id FROM refund WHERE payment_id = ? AND provider_refund_id IS NOT DISTINCT FROM ? ORDER BY id DESC LIMIT 1",
                Long.class, paymentId, providerRefundId);
    }

    private String paymentProviderId(long paymentId) {
        return jdbc.queryForObject("SELECT payment_id FROM payment WHERE id = ?", String.class, paymentId);
    }

    private long refundedAmountPaise(long paymentId) {
        return jdbc.queryForObject("SELECT refunded_amount_paise FROM payment WHERE id = ?", Long.class, paymentId);
    }

    private String refundStatus(long refundId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refundId);
    }
}
