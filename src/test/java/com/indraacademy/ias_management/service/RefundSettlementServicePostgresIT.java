package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.RefundRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refund-Integrity Hardening, Phase B — real-PostgreSQL proof for the concurrency/idempotency
 * guarantees no Mockito test can honestly make: genuine blocking of a concurrent reservation
 * attempt by the Payment row lock, and genuine CHECK-constraint/partial-unique-index behavior
 * from V63 interacting correctly with live reservation traffic. Follows the same *PostgresIT
 * convention as PaymentOrderRepositoryPostgresIT (V62) and PaymentSettlementServicePostgresIT.
 * <p>
 * None of these 8 scenarios exercise {@link RefundSettlementService#finalizeSuccessfulRefund}
 * (that requires a StudentFees/allocation fixture graph already covered by the Mockito suite in
 * RefundSettlementServiceTest) — they prove {@code reserve()} and {@code markFailedAndRelease()},
 * which is where the concurrency-sensitive capacity math actually lives.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RefundSettlementService.class, FeeCalculationService.class, AuditService.class,
        com.indraacademy.ias_management.util.SecurityUtil.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class RefundSettlementServicePostgresIT {

    private static final long SCHOOL = -98301L;

    // RefundSettlementService.finalizeSuccessfulRefund sends a notification — never exercised by
    // these 8 reserve()/markFailedAndRelease() scenarios, but the bean still needs a dependency
    // to wire against. Same reasoning as PaymentSettlementServicePostgresIT's equivalent mock.
    @MockBean private BusinessNotificationService businessNotifications;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundSettlementService refundSettlementService;

    /** Ends the @DataJpaTest-managed per-test transaction immediately, before any fixture is
     * inserted — otherwise a fixture row inserted through this transaction is invisible to a
     * genuinely separate JDBC connection (the concurrency test's raw-JDBC holder thread) and,
     * for reserve()/markFailedAndRelease() called from a different thread via
     * {@code CompletableFuture}, invisible even to the real service call (a different thread
     * does not share the test's thread-bound transaction). Matches the pattern already
     * established by PaymentSettlementServicePostgresIT for the same reason. */
    @BeforeEach
    void endTestManagedTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM allocation_refund WHERE student_fees_id IN (SELECT id FROM student_fees WHERE school_id = ?)", SCHOOL);
        jdbc.update("DELETE FROM payment_student_fees_allocation WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM student_fees WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM refund WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM payment WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id = ?", SCHOOL);
    }

    private static String normalizeJdbcUrl(String url) {
        return url.startsWith("jdbc:") ? url : "jdbc:" + url;
    }

    // ── Test 1: first reservation succeeds ──────────────────────────────────────────────────

    @Test
    void firstReservation_succeeds_reservesExactAmountAndAssignsProviderKey() {
        long paymentId = insertPayment(100000L, 0L);

        var result = refundSettlementService.reserve(paymentId, refundRequest(70000L, "first"), SCHOOL);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);
        assertThat(result.refund().getStatus()).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(result.refund().getProviderIdempotencyKey()).isNotBlank();
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L);
    }

    // ── Test 2: sequential over-refund rejected ─────────────────────────────────────────────

    @Test
    void sequentialOverRefund_secondRequestRejected_ledgerStaysAtFirstAmount() {
        long paymentId = insertPayment(100000L, 0L);

        var first = refundSettlementService.reserve(paymentId, refundRequest(70000L, "first"), SCHOOL);
        assertThat(first.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);

        var second = refundSettlementService.reserve(paymentId, refundRequest(40000L, "second, too much"), SCHOOL);
        assertThat(second.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);

        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L);
        assertThat(refundRowCount(paymentId)).isEqualTo(1); // no row created for the rejected attempt
    }

    // ── Test 3: exact remaining amount succeeds ─────────────────────────────────────────────

    @Test
    void exactRemainingAmount_succeeds_reachesFullAmountPaid() {
        long paymentId = insertPayment(100000L, 70000L); // as if a prior reservation already exists

        var result = refundSettlementService.reserve(paymentId, refundRequest(30000L, "exact remainder"), SCHOOL);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(100000L);
    }

    // ── Test 4: true concurrent refund race — real Postgres locking, not Mockito ────────────

    @Test
    void concurrentRefundRequests_serializedByThePaymentLock_exactlyOneReservationSurvives() throws Exception {
        long paymentId = insertPayment(100000L, 0L);

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        // Thread 1: raw JDBC, holds FOR UPDATE on the payment row, then simulates a winning
        // reservation (increments refunded_amount_paise by 70000, as reserve() itself would)
        // and commits.
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(
                    normalizeJdbcUrl(System.getenv("DB_URL")), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT * FROM payment WHERE id = " + paymentId + " FOR UPDATE");
                }
                holderHasLock.countDown();
                releaseHolder.await(5, TimeUnit.SECONDS);
                try (Statement st = conn.createStatement()) {
                    st.execute("UPDATE payment SET refunded_amount_paise = refunded_amount_paise + 70000 WHERE id = " + paymentId);
                }
                conn.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: the real reserve() call for the SAME amount against the SAME payment — must
        // block on the row lock, then correctly reject once it sees the reduced remaining
        // balance (100000 - 70000 = 30000 < 70000 requested).
        long waiterStart = System.currentTimeMillis();
        CompletableFuture<RefundSettlementService.ReservationResult> waiter = CompletableFuture.supplyAsync(() ->
                refundSettlementService.reserve(paymentId, refundRequest(70000L, "second racer"), SCHOOL));

        Thread.sleep(800);
        releaseHolder.countDown();

        RefundSettlementService.ReservationResult result = waiter.get(10, TimeUnit.SECONDS);
        holder.get(5, TimeUnit.SECONDS);
        long waiterFinished = System.currentTimeMillis();

        assertThat(waiterFinished - waiterStart).isGreaterThanOrEqualTo(750); // genuinely blocked
        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.REJECTED);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L); // never exceeds 100000
        assertThat(refundRowCount(paymentId)).isZero(); // reserve()'s own row was never created (rejected)
    }

    // ── Test 5: reservation release on definitive failure ───────────────────────────────────

    @Test
    void definitiveFailure_releasesExactlyItsReservedAmount() {
        long paymentId = insertPayment(100000L, 40000L);
        long refundId = insertPendingRefund(paymentId, 40000L);

        refundSettlementService.markFailedAndRelease(paymentId, refundId);

        assertThat(refundedAmountPaise(paymentId)).isZero();
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_FAILED);
    }

    // ── Test 6: duplicate failure processing does not double-release ───────────────────────

    @Test
    void duplicateFailureProcessing_doesNotDoubleReleaseOrGoNegative() {
        long paymentId = insertPayment(100000L, 40000L);
        long refundId = insertPendingRefund(paymentId, 40000L);

        refundSettlementService.markFailedAndRelease(paymentId, refundId);
        refundSettlementService.markFailedAndRelease(paymentId, refundId); // run again

        assertThat(refundedAmountPaise(paymentId)).isZero(); // not negative, not double-released
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_FAILED);
    }

    // ── Test 7: SUCCESS can never be released ───────────────────────────────────────────────

    @Test
    void successfulRefund_capacityIsNeverReleased() {
        long paymentId = insertPayment(100000L, 40000L);
        long refundId = insertRefundWithStatus(paymentId, 40000L, RefundSettlementService.STATUS_SUCCESS);

        refundSettlementService.markFailedAndRelease(paymentId, refundId);

        assertThat(refundedAmountPaise(paymentId)).isEqualTo(40000L); // untouched
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_SUCCESS); // untouched
    }

    // ── Test 8: idempotent logical retry — same client key twice ───────────────────────────

    @Test
    void idempotentLogicalRetry_sameClientKeyTwice_noSecondRowNoDoubleReservationNoSecondProviderKey() {
        long paymentId = insertPayment(100000L, 0L);
        RefundRequest first = refundRequest(50000L, "retry attempt 1");
        first.setIdempotencyKey("client-key-real-postgres-1");

        var firstResult = refundSettlementService.reserve(paymentId, first, SCHOOL);
        assertThat(firstResult.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);

        RefundRequest retry = refundRequest(50000L, "retry attempt 1");
        retry.setIdempotencyKey("client-key-real-postgres-1"); // identical logical retry

        var retryResult = refundSettlementService.reserve(paymentId, retry, SCHOOL);

        assertThat(retryResult.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.ALREADY_RESERVED);
        assertThat(retryResult.refund().getId()).isEqualTo(firstResult.refund().getId());
        assertThat(retryResult.refund().getProviderIdempotencyKey()).isEqualTo(firstResult.refund().getProviderIdempotencyKey());
        assertThat(refundRowCount(paymentId)).isEqualTo(1); // no second Refund row
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(50000L); // not incremented twice
    }

    /** Final pre-commit audit (Task 9): the sequential test above proves the idempotency-key
     * lookup works, but not that two genuinely CONCURRENT requests bearing the same client key
     * can't each slip past the check before the other commits. Since reserve() acquires the
     * Payment lock (findByIdForUpdate) BEFORE the idempotency-key lookup, the two calls must
     * fully serialize on that lock — the loser's lookup runs only after the winner's INSERT is
     * already committed and visible, so it finds the winner's row instead of creating its own. */
    @Test
    void concurrentSameClientIdempotencyKey_serializesOnPaymentLock_onlyOneRefundRowSurvives() throws Exception {
        long paymentId = insertPayment(100000L, 0L);
        String sharedKey = "client-key-concurrent-race";

        RefundRequest requestA = refundRequest(50000L, "concurrent same-key request");
        requestA.setIdempotencyKey(sharedKey);
        RefundRequest requestB = refundRequest(50000L, "concurrent same-key request");
        requestB.setIdempotencyKey(sharedKey);

        CompletableFuture<RefundSettlementService.ReservationResult> workerA = CompletableFuture.supplyAsync(() ->
                refundSettlementService.reserve(paymentId, requestA, SCHOOL));
        CompletableFuture<RefundSettlementService.ReservationResult> workerB = CompletableFuture.supplyAsync(() ->
                refundSettlementService.reserve(paymentId, requestB, SCHOOL));

        var resultA = workerA.get(10, TimeUnit.SECONDS);
        var resultB = workerB.get(10, TimeUnit.SECONDS);

        // Exactly one RESERVED (the winner of the lock race), the other ALREADY_RESERVED
        // (found the winner's committed row) — never both RESERVED.
        List<RefundSettlementService.ReservationOutcome> outcomes = List.of(resultA.outcome(), resultB.outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(
                RefundSettlementService.ReservationOutcome.RESERVED, RefundSettlementService.ReservationOutcome.ALREADY_RESERVED);

        // Both results point at the SAME logical refund and the SAME provider-operation key —
        // never a second row, never a second provider key minted for the loser.
        assertThat(resultA.refund().getId()).isEqualTo(resultB.refund().getId());
        assertThat(resultA.refund().getProviderIdempotencyKey()).isEqualTo(resultB.refund().getProviderIdempotencyKey());
        assertThat(refundRowCount(paymentId)).isEqualTo(1);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(50000L); // reserved exactly once, not twice
    }

    // ═══════════════════════════ Refund-Integrity Hardening, Phase C ═══════════════════════════
    // Reconciliation (resolveFromProviderState) against real Postgres — genuine re-check of
    // Refund status inside the Payment lock, not a Mockito stub standing in for it.

    // ── Test: PENDING -> SUCCESS reconciliation ─────────────────────────────────────────────

    @Test
    void pendingToSuccessReconciliation_finalizesAndKeepsCapacityConsumed() {
        long paymentId = insertPayment(100000L, 70000L); // already reserved
        long refundId = insertPendingRefundWithProviderId(paymentId, 70000L, "rfnd-recon-1");

        var result = refundSettlementService.resolveFromProviderState("rfnd-recon-1",
                RazorpayService.PROVIDER_STATUS_PROCESSED, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.FINALIZED);
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L); // still consumed, not released
    }

    // ── Test: duplicate terminal SUCCESS -> no duplicate financial side effects ─────────────

    @Test
    void duplicateSuccessReconciliation_secondCallIsNoOp() {
        long paymentId = insertPayment(100000L, 70000L);
        long refundId = insertPendingRefundWithProviderId(paymentId, 70000L, "rfnd-recon-2");

        var first = refundSettlementService.resolveFromProviderState("rfnd-recon-2",
                RazorpayService.PROVIDER_STATUS_PROCESSED, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);
        var second = refundSettlementService.resolveFromProviderState("rfnd-recon-2",
                RazorpayService.PROVIDER_STATUS_PROCESSED, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(first.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.FINALIZED);
        assertThat(second.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L); // not incremented/decremented twice
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
    }

    // ── Test: provider still PENDING -> no local mutation beyond metadata sync ──────────────

    @Test
    void providerStillPending_noMutationBeyondProviderIdSync() {
        long paymentId = insertPayment(100000L, 70000L);
        long refundId = insertPendingRefund(paymentId, 70000L); // no providerRefundId yet
        String providerRefundId = "rfnd-recon-3";
        // reserve()'s own provider key generation is unrelated; simulate "provider id now known"
        // exactly like PaymentService.callProviderAndFinalize's recordProviderRefundId would.
        refundSettlementService.recordProviderRefundId(paymentId, refundId, providerRefundId);

        var result = refundSettlementService.resolveFromProviderState(providerRefundId,
                RazorpayService.PROVIDER_STATUS_PENDING, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L); // unchanged
    }

    // ── Test: PENDING -> FAILED, reservation released exactly once ──────────────────────────

    @Test
    void pendingToFailedReconciliation_releasesReservationExactlyOnce() {
        long paymentId = insertPayment(100000L, 70000L);
        long refundId = insertPendingRefundWithProviderId(paymentId, 70000L, "rfnd-recon-4");

        var result = refundSettlementService.resolveFromProviderState("rfnd-recon-4",
                RazorpayService.PROVIDER_STATUS_FAILED, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.RELEASED);
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_FAILED);
        assertThat(refundedAmountPaise(paymentId)).isZero();
    }

    // ── Test: duplicate FAILED -> no double release ─────────────────────────────────────────

    @Test
    void duplicateFailedReconciliation_noDoubleRelease() {
        long paymentId = insertPayment(100000L, 70000L);
        long refundId = insertPendingRefundWithProviderId(paymentId, 70000L, "rfnd-recon-5");

        refundSettlementService.resolveFromProviderState("rfnd-recon-5", RazorpayService.PROVIDER_STATUS_FAILED,
                paymentProviderId(paymentId), 70000L, "INR", "RAZORPAY_WEBHOOK", "SYSTEM", null);
        var second = refundSettlementService.resolveFromProviderState("rfnd-recon-5", RazorpayService.PROVIDER_STATUS_FAILED,
                paymentProviderId(paymentId), 70000L, "INR", "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(second.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refundedAmountPaise(paymentId)).isZero(); // not negative
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_FAILED);
    }

    // ── Test: already-SUCCESS / already-FAILED webhook arrival -> no-op (Cases D/E) ─────────

    @Test
    void alreadySuccessWebhookArrival_isNoOp() {
        long paymentId = insertPayment(100000L, 70000L);
        long refundId = insertRefundWithStatus(paymentId, 70000L, RefundSettlementService.STATUS_SUCCESS);
        jdbc.update("UPDATE refund SET provider_refund_id = ? WHERE id = ?", "rfnd-recon-6", refundId);

        var result = refundSettlementService.resolveFromProviderState("rfnd-recon-6",
                RazorpayService.PROVIDER_STATUS_PROCESSED, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L);
    }

    @Test
    void alreadyFailedWebhookArrival_isNoOp() {
        long paymentId = insertPayment(100000L, 0L);
        long refundId = insertRefundWithStatus(paymentId, 70000L, RefundSettlementService.STATUS_FAILED);
        jdbc.update("UPDATE refund SET provider_refund_id = ? WHERE id = ?", "rfnd-recon-7", refundId);

        var result = refundSettlementService.resolveFromProviderState("rfnd-recon-7",
                RazorpayService.PROVIDER_STATUS_FAILED, paymentProviderId(paymentId), 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.NO_OP);
        assertThat(refundedAmountPaise(paymentId)).isZero(); // already 0, stays 0
    }

    // ── Test: provider identity mismatch -> rejected, zero financial mutation ───────────────

    @Test
    void providerPaymentIdMismatch_rejectedWithoutAnyMutation() {
        long paymentId = insertPayment(100000L, 70000L);
        long refundId = insertPendingRefundWithProviderId(paymentId, 70000L, "rfnd-recon-8");

        var result = refundSettlementService.resolveFromProviderState("rfnd-recon-8",
                RazorpayService.PROVIDER_STATUS_PROCESSED, "pay_SOMEONE_ELSES_PAYMENT", 70000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.REJECTED_MISMATCH);
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(70000L); // unchanged either direction
    }

    // ── Test: provider-success / local-finalization-failure, then successful retry ─────────

    /** Task 11's exact scenario against a real database: the provider refund already succeeded
     * (providerRefundId persisted), but the FIRST local finalize attempt hits a genuine
     * allocation-ledger inconsistency (an allocation already fully consumed by a different,
     * unrelated refund) and throws — proving the refund survives as PENDING with its
     * providerRefundId intact rather than losing all record of the provider operation. Once a
     * second, still-untouched allocation exists for the same payment, reconciliation retries
     * finalize ONLY (no second createRefund call is even possible from this method — it only
     * ever calls finalizeSuccessfulRefund/markFailedAndRelease) and succeeds. */
    @Test
    void providerSuccessLocalFinalizationFailure_recoveredByRetryingFinalizeOnly() {
        long paymentId = insertPayment(200000L, 200000L);
        long refundId = insertPendingRefundWithProviderId(paymentId, 200000L, "rfnd-recon-9");

        long month1Fees = insertStudentFees(1, 2000);
        long exhaustedAllocation = insertAllocation(paymentId, month1Fees, 1, 100000L);
        // A different, unrelated refund already fully consumed this allocation.
        long otherRefundId = insertRefundWithStatus(paymentId, 100000L, RefundSettlementService.STATUS_SUCCESS);
        insertAllocationRefund(exhaustedAllocation, otherRefundId, month1Fees, 100000L);

        // The first finalize attempt genuinely throws (allocation-ledger inconsistency) — exactly
        // what a webhook/reconcileRefund caller catches and logs (see RazorpayService), never
        // propagated as a silent NO_OP. Calling the service directly here (bypassing that
        // wrapper) must show the same real exception, proving the transaction rolled back
        // correctly rather than the outcome being papered over.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> refundSettlementService.resolveFromProviderState(
                        "rfnd-recon-9", RazorpayService.PROVIDER_STATUS_PROCESSED, paymentProviderId(paymentId), 200000L, "INR",
                        "RAZORPAY_WEBHOOK", "SYSTEM", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reconcile");
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_PENDING);
        assertThat(jdbc.queryForObject("SELECT provider_refund_id FROM refund WHERE id = ?", String.class, refundId))
                .isEqualTo("rfnd-recon-9"); // survived the failed finalize attempt
        assertThat(refundedAmountPaise(paymentId)).isEqualTo(200000L); // still consumed, never released

        // The underlying problem is fixed: a second, untouched allocation now exists.
        long month2Fees = insertStudentFees(2, 2000);
        insertAllocation(paymentId, month2Fees, 2, 100000L);

        var secondAttempt = refundSettlementService.resolveFromProviderState("rfnd-recon-9",
                RazorpayService.PROVIDER_STATUS_PROCESSED, paymentProviderId(paymentId), 200000L, "INR",
                "RAZORPAY_WEBHOOK", "SYSTEM", null);

        assertThat(secondAttempt.outcome()).isEqualTo(RefundSettlementService.ReconciliationOutcome.FINALIZED);
        assertThat(refundStatus(refundId)).isEqualTo(RefundSettlementService.STATUS_SUCCESS);
        assertThat(jdbc.queryForObject("SELECT provider_refund_id FROM refund WHERE id = ?", String.class, refundId))
                .isEqualTo("rfnd-recon-9"); // same provider id throughout -- never re-created
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    private RefundRequest refundRequest(long amountPaise, String reason) {
        RefundRequest req = new RefundRequest();
        req.setAmount(amountPaise);
        req.setReason(reason);
        return req;
    }

    /** Financial AcademicSession Authority, Phase B3/B4: reserve() must propagate
     * Payment.academicSessionId onto the new Refund row unchanged — never a fresh
     * AcademicSession lookup, and never left behind even though every other Refund field here
     * is freshly constructed. */
    @Test
    void reserve_propagatesAcademicSessionIdFromPaymentUnchanged() {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2025-2026', DATE '2025-04-01', DATE '2026-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        long paymentId = insertPayment(100000L, 0L);
        jdbc.update("UPDATE payment SET academic_session_id = ? WHERE id = ?", sessionId, paymentId);

        var result = refundSettlementService.reserve(paymentId, refundRequest(70000L, "session-propagation"), SCHOOL);

        assertThat(result.outcome()).isEqualTo(RefundSettlementService.ReservationOutcome.RESERVED);
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM refund WHERE id = ?", Long.class, result.refund().getId()))
                .isEqualTo(sessionId);
    }

    private long insertPayment(long amountPaidPaise, long refundedAmountPaise) {
        String paymentId = "RSVC-IT-PAY-" + System.nanoTime();
        // Every primitive (never-null) int/long/boolean field on the Payment entity must get a
        // real value here — Hibernate cannot map a NULL column into a primitive field when
        // reserve()/markFailedAndRelease() read this row back via paymentRepository.
        jdbc.update("INSERT INTO payment " +
                        "(school_id, student_id, student_name, class_name, session, month, amount, " +
                        "payment_id, order_id, payment_date, status, razorpay_signature, amount_paid, " +
                        "refunded_amount_paise, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, paid_manually) " +
                        "VALUES (?, 'RSVC-IT-STUDENT', 'IT Student', '6A', '2025-2026', '100000000000', ?, " +
                        "?, ?, ?, 'success', 'sig', ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, false)",
                SCHOOL, amountPaidPaise, paymentId, paymentId + "-ORDER", LocalDateTime.now(), amountPaidPaise, refundedAmountPaise);
        return jdbc.queryForObject("SELECT id FROM payment WHERE payment_id = ?", Long.class, paymentId);
    }

    private long insertPendingRefund(long paymentId, long amountPaise) {
        return insertRefundWithStatus(paymentId, amountPaise, RefundSettlementService.STATUS_PENDING);
    }

    private long insertPendingRefundWithProviderId(long paymentId, long amountPaise, String providerRefundId) {
        long refundId = insertPendingRefund(paymentId, amountPaise);
        jdbc.update("UPDATE refund SET provider_refund_id = ? WHERE id = ?", providerRefundId, refundId);
        return refundId;
    }

    private String paymentProviderId(long paymentId) {
        return jdbc.queryForObject("SELECT payment_id FROM payment WHERE id = ?", String.class, paymentId);
    }

    private long insertStudentFees(int month, long amountPaidRupees) {
        jdbc.update("INSERT INTO student_fees (student_id, school_id, year, month, paid, amount_paid) " +
                        "VALUES ('RSVC-IT-STUDENT', ?, '2025-2026', ?, true, ?)",
                SCHOOL, month, java.math.BigDecimal.valueOf(amountPaidRupees));
        return jdbc.queryForObject(
                "SELECT id FROM student_fees WHERE student_id = 'RSVC-IT-STUDENT' AND school_id = ? AND month = ? ORDER BY id DESC LIMIT 1",
                Long.class, SCHOOL, month);
    }

    private long insertAllocation(long paymentId, long studentFeesId, int month, long amountPaise) {
        jdbc.update("INSERT INTO payment_student_fees_allocation " +
                        "(payment_id, student_fees_id, school_id, student_id, session, month, amount_paise, created_at) " +
                        "VALUES (?, ?, ?, 'RSVC-IT-STUDENT', '2025-2026', ?, ?, ?)",
                paymentId, studentFeesId, SCHOOL, month, amountPaise, LocalDateTime.now());
        return jdbc.queryForObject(
                "SELECT id FROM payment_student_fees_allocation WHERE payment_id = ? AND student_fees_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, paymentId, studentFeesId);
    }

    private void insertAllocationRefund(long allocationId, long refundId, long studentFeesId, long amountPaise) {
        jdbc.update("INSERT INTO allocation_refund (allocation_id, refund_id, student_fees_id, amount_paise, created_at) " +
                        "VALUES (?, ?, ?, ?, ?)",
                allocationId, refundId, studentFeesId, amountPaise, LocalDateTime.now());
    }

    private long insertRefundWithStatus(long paymentId, long amountPaise, String status) {
        jdbc.update("INSERT INTO refund " +
                        "(payment_id, school_id, student_id, session, months_refunded, amount_paise, " +
                        "status, legacy_approximation, created_at, provider_idempotency_key) " +
                        "VALUES (?, ?, 'RSVC-IT-STUDENT', '2025-2026', '000000000000', ?, ?, false, ?, ?)",
                paymentId, SCHOOL, amountPaise, status, LocalDateTime.now(), "RSVC-IT-KEY-" + System.nanoTime());
        return jdbc.queryForObject(
                "SELECT id FROM refund WHERE payment_id = ? ORDER BY id DESC LIMIT 1", Long.class, paymentId);
    }

    private long refundedAmountPaise(long paymentId) {
        return jdbc.queryForObject("SELECT refunded_amount_paise FROM payment WHERE id = ?", Long.class, paymentId);
    }

    private int refundRowCount(long paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE payment_id = ?", Integer.class, paymentId);
    }

    private String refundStatus(long refundId) {
        return jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refundId);
    }
}
