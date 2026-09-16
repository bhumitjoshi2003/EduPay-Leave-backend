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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Razorpay Payment-Integrity Hardening — real-PostgreSQL proof for the guarantees no Mockito
 * test can honestly make: genuine transactional rollback across multiple tables, and genuine
 * blocking of a concurrent settlement attempt by the PaymentOrder row lock (Phase B), plus —
 * Phase C — that the same lock and idempotency guarantees hold when the racing attempt is
 * webhook-sourced rather than client-sourced. Follows the same *PostgresIT convention as
 * ClassTeacherActivationPostgresIT (DataJpaTest + real Postgres, gated by DB_URL, fixtures
 * committed via TestTransaction so a second thread/connection can see them).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentSettlementService.class, AttendanceService.class, StudentFeesService.class,
        AcademicSessionService.class, FeeCalculationService.class, AuditService.class,
        com.indraacademy.ias_management.util.SecurityUtil.class,
        StudentTemporalMembershipResolver.class,
        org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class PaymentSettlementServicePostgresIT {

    private static final long SCHOOL = -98001L;

    // StudentFeesService depends on BusinessNotificationService, whose own dependency chain
    // (NotificationPublisher -> NotificationPublicationTransaction -> NotificationRecipientResolver
    // -> ParentPortalService -> ...) is entirely unrelated to what this test exercises —
    // PaymentSettlementService.settle() never sends a notification itself (RazorpayService
    // does, strictly after settle() returns, which is outside the scope of this test). A
    // lenient mock avoids wiring that whole unrelated chain just to satisfy the field.
    @MockBean private BusinessNotificationService businessNotifications;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentSettlementService settlementService;

    @BeforeEach
    void seedSchool() {
        jdbc.update("INSERT INTO school (id, active, created_at, name, plan, slug, " +
                        "academic_year_start_month, periods_per_day) VALUES (?, true, CURRENT_TIMESTAMP, ?, " +
                        "'TRIAL', ?, 4, 8)",
                SCHOOL, "psi-it-school", "psi-it-school");
        TestTransaction.flagForCommit();
        TestTransaction.end();
        com.indraacademy.ias_management.util.SchoolContext.set(SCHOOL);
    }

    @AfterEach
    void cleanUp() {
        com.indraacademy.ias_management.util.SchoolContext.clear();
        jdbc.update("DELETE FROM attendance WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM payment_student_fees_allocation WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM payment WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM payment_order WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM student_fees WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM academic_session WHERE school_id = ?", SCHOOL);
        jdbc.update("DELETE FROM school WHERE id = ?", SCHOOL);
    }

    // ── Genuine rollback: allocation failure must undo the Payment + PaymentOrder writes ────

    @Test
    void allocationFailure_rollsBackPaymentAndPaymentOrderTogether() {
        // No matching student_fees row for the order's month — markFeesAsPaid's Pass 1 finds
        // nothing to allocate and throws, deep inside the same transaction settle() opened.
        String orderId = "psi-it-order-rollback";
        String paymentId = "psi-it-pay-rollback";
        insertPaymentOrder(orderId, "psi-it-student-1", "100000000000");

        assertThatThrownBy(() -> settlementService.settle(orderId, paymentId, "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY))
                .isInstanceOf(IllegalStateException.class);

        // Fresh reads via JdbcTemplate, after the transaction settle() owned has genuinely
        // rolled back (TestTransaction.end() in @BeforeEach means this test method runs
        // without an ambient wrapping transaction of its own).
        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE payment_id = ?", Integer.class, paymentId);
        assertThat(paymentCount).isZero();

        Boolean consumed = jdbc.queryForObject(
                "SELECT consumed FROM payment_order WHERE order_id = ?", Boolean.class, orderId);
        assertThat(consumed).isFalse();
    }

    // ── Idempotency against a real, already-committed Payment row ───────────────────────────

    @Test
    void samePaymentIdAgainstRealRow_isIdempotent_noDuplicateCreated() {
        String orderId = "psi-it-order-idem";
        String paymentId = "psi-it-pay-idem";
        insertPaymentOrder(orderId, "psi-it-student-2", "010000000000");
        markOrderConsumed(orderId);
        insertPayment(paymentId, orderId, "psi-it-student-2", SCHOOL);

        PaymentSettlementService.SettlementResult result = settlementService.settle(orderId, paymentId, "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.ALREADY_SETTLED);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE payment_id = ?", Integer.class, paymentId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void onlineGrossCapture_allocatesOnlyStudentFeesPrincipal_platformAndLeaveStaySeparate() {
        String orderId = "psi-it-order-principal";
        String paymentId = "psi-it-pay-principal";
        String studentId = "psi-it-student-principal";
        Long studentFeesId = jdbc.queryForObject(
                "INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, discount_amount, snapshot_status) " +
                        "VALUES (?, ?, '6A', 1, false, false, '2025-2026', 0, false, 0, 1000, 0, 0, 'COMPUTED') RETURNING id",
                Long.class, SCHOOL, studentId);
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, month, " +
                        "amount, bus_fee, tuition_fee, annual_charges, lab_charges, eca_project, examination_fee, " +
                        "additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', '100000000000', 104000, 0, 0, 0, 0, 0, 0, 2500, 0, 1500, false, ?)",
                orderId, SCHOOL, studentId, LocalDateTime.now());

        PaymentSettlementService.SettlementResult result = settlementService.settle(
                orderId, paymentId, "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(jdbc.queryForObject("SELECT amount FROM payment WHERE payment_id = ?", Integer.class, paymentId))
                .isEqualTo(104000);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_paise) FROM payment_student_fees_allocation WHERE payment_id = ?",
                Long.class, result.payment().getId())).isEqualTo(100000L);
        assertThat(jdbc.queryForObject("SELECT amount_paid FROM student_fees WHERE id = ?", java.math.BigDecimal.class, studentFeesId))
                .isEqualByComparingTo("1000.00");
    }

    /** Financial AcademicSession Authority, Phase B3/B4: PaymentOrder.academicSessionId (as
     * populated by RazorpayService.createOrder) must propagate unchanged onto Payment during
     * settlement, and onward onto every allocation created for it — never a fresh lookup, per
     * the settlement lock's existing idempotency/authority guarantees. */
    @Test
    void settlement_propagatesAcademicSessionIdFromPaymentOrderToPaymentAndAllocations() {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2025-2026', DATE '2025-04-01', DATE '2026-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        String orderId = "psi-it-order-sessionid";
        String paymentId = "psi-it-pay-sessionid";
        String studentId = "psi-it-student-sessionid";
        jdbc.update("INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '6A', 1, false, false, '2025-2026', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED')",
                SCHOOL, studentId, sessionId);
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, " +
                        "academic_session_id, month, amount, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', ?, '100000000000', 1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ?)",
                orderId, SCHOOL, studentId, sessionId, LocalDateTime.now());

        PaymentSettlementService.SettlementResult result = settlementService.settle(
                orderId, paymentId, "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(jdbc.queryForObject("SELECT academic_session_id FROM payment WHERE payment_id = ?", Long.class, paymentId))
                .isEqualTo(sessionId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_student_fees_allocation WHERE payment_id = ? AND academic_session_id = ?",
                Integer.class, result.payment().getId(), sessionId))
                .isGreaterThan(0);
    }

    /** Financial AcademicSession Authority, Phase C2 — the correct-row selection proof: a
     * second StudentFees row exists for the SAME student/month but a DIFFERENT session, whose
     * {@code year} label is deliberately malformed (matches neither session's real label). Note
     * that a label-based query would ALSO have avoided this row here, since it doesn't share the
     * target session's real label — the DB's UNIQUE(school_id, student_id, year, month)
     * constraint makes a genuine same-label, different-session collision for one student/month
     * impossible to construct at all. What this proves instead is the same thing the malformed-
     * label identity test proves (see StudentFeesServiceSessionAuthorityPostgresIT): the
     * authoritative-id lookup correctly ignores a row whose label snapshot has been corrupted/
     * never matched its own session, selecting only the row whose academic_session_id actually
     * agrees with the PaymentOrder's — the decoy row must remain completely untouched (unpaid,
     * no allocation). */
    @Test
    void settlement_selectsOnlyTheAuthoritativeSessionsLiability_leavesADecoyOtherSessionRowUntouched() {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2025-2026', DATE '2025-04-01', DATE '2026-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        Long decoySessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2024-2025', DATE '2024-04-01', DATE '2025-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        String orderId = "psi-it-order-correctrow";
        String paymentId = "psi-it-pay-correctrow";
        String studentId = "psi-it-student-correctrow";
        // The decoy: same student, same month, a DIFFERENT session — with a deliberately
        // malformed year label matching neither session's real label (see the class-level
        // comment on why this, not a same-label collision, is the meaningful scenario here).
        Long decoyStudentFeesId = jdbc.queryForObject(
                "INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '6A', 1, false, false, '2099-2100', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED') RETURNING id",
                Long.class, SCHOOL, studentId, decoySessionId);
        Long targetStudentFeesId = jdbc.queryForObject(
                "INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '6A', 1, false, false, '2025-2026', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED') RETURNING id",
                Long.class, SCHOOL, studentId, sessionId);
        // Amount (200000 paise = ₹2000) comfortably exceeds base due (₹1000) plus any possible
        // date-dependent late fee (max 30*21=₹630, per StudentFeesService.calculateLateFees) —
        // the point of this test is correct-row selection, not exercising the late-fee schedule.
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, " +
                        "academic_session_id, month, amount, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', ?, '100000000000', 200000, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ?)",
                orderId, SCHOOL, studentId, sessionId, LocalDateTime.now());

        PaymentSettlementService.SettlementResult result = settlementService.settle(
                orderId, paymentId, "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(jdbc.queryForObject("SELECT paid FROM student_fees WHERE id = ?", Boolean.class, targetStudentFeesId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT paid FROM student_fees WHERE id = ?", Boolean.class, decoyStudentFeesId)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_student_fees_allocation WHERE student_fees_id = ?", Integer.class, decoyStudentFeesId))
                .isZero();
    }

    @Test
    void consumedOrderWithDifferentPaymentId_realRow_isRejected() {
        String orderId = "psi-it-order-conflict";
        String existingPaymentId = "psi-it-pay-existing";
        String newPaymentId = "psi-it-pay-new-attempt";
        insertPaymentOrder(orderId, "psi-it-student-3", "010000000000");
        markOrderConsumed(orderId);
        insertPayment(existingPaymentId, orderId, "psi-it-student-3", SCHOOL);

        PaymentSettlementService.SettlementResult result = settlementService.settle(orderId, newPaymentId, "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
        assertThat(count).isEqualTo(1); // still just the original — no second row created
    }

    // ── Genuine concurrency: the PaymentOrder lock actually blocks a racing settlement ──────

    @Test
    void concurrentSettlement_isSerializedByThePaymentOrderLock_neverCreatesTwoPayments() throws Exception {
        String orderId = "psi-it-order-race";
        insertPaymentOrder(orderId, "psi-it-student-4", "010000000000");

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        // Thread 1: raw JDBC, holds FOR UPDATE on the payment_order row, then simulates a
        // winning settlement (inserts Payment, flips consumed) and commits — exactly what
        // settle() itself would have done inside its own transaction.
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(
                    normalizeJdbcUrl(System.getenv("DB_URL")), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT * FROM payment_order WHERE order_id = '" + orderId + "' FOR UPDATE");
                }
                holderHasLock.countDown();
                releaseHolder.await(5, TimeUnit.SECONDS);
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO payment (school_id, student_id, student_name, class_name, session, " +
                            "month, amount, payment_id, order_id, payment_date, status, razorpay_signature, amount_paid) " +
                            "VALUES (" + SCHOOL + ", 'psi-it-student-4', 'IT Student', '6A', '2025-2026', " +
                            "'010000000000', 250000, 'psi-it-pay-holder', '" + orderId + "', now(), 'success', 'sig', 250000)");
                    st.execute("UPDATE payment_order SET consumed = true WHERE order_id = '" + orderId + "'");
                }
                conn.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: the real settlement service, for a DIFFERENT paymentId against the same
        // still-locked order — must block until the holder above releases and commits.
        long waiterStart = System.currentTimeMillis();
        CompletableFuture<PaymentSettlementService.SettlementResult> waiter = CompletableFuture.supplyAsync(() -> {
            com.indraacademy.ias_management.util.SchoolContext.set(SCHOOL);
            try {
                return settlementService.settle(orderId, "psi-it-pay-waiter", "sig", SCHOOL, PaymentSettlementService.SettlementSource.CLIENT_VERIFY);
            } finally {
                com.indraacademy.ias_management.util.SchoolContext.clear();
            }
        });

        Thread.sleep(800);
        releaseHolder.countDown();

        PaymentSettlementService.SettlementResult result = waiter.get(10, TimeUnit.SECONDS);
        holder.get(5, TimeUnit.SECONDS);
        long waiterFinished = System.currentTimeMillis();

        // Blocked for real, not just "ran after by coincidence."
        assertThat(waiterFinished - waiterStart).isGreaterThanOrEqualTo(750);

        // The holder's commit won the race; the waiter — a different paymentId against a now-
        // consumed order — must be rejected, never silently treated as a second success.
        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);

        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
        assertThat(paymentCount).isEqualTo(1); // exactly one Payment row for this order, ever
    }

    /** Phase C: the same guarantee as above, but the racing settlement attempt is
     * webhook-sourced rather than client-sourced — proving the PaymentOrder lock serializes
     * BOTH paths identically (settle() doesn't branch its locking/idempotency logic on
     * SettlementSource; it only affects which razorpaySignature sentinel gets written), and
     * that "one Payment row per Razorpay order" holds regardless of which path wins. */
    @Test
    void concurrentWebhookAndClientVerify_isSerializedByTheSameLock_exactlyOnePaymentSurvives() throws Exception {
        String orderId = "psi-it-order-race-webhook";
        insertPaymentOrder(orderId, "psi-it-student-5", "010000000000");

        CountDownLatch holderHasLock = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);

        // Thread 1: raw JDBC, holds FOR UPDATE, then simulates a winning CLIENT_VERIFY
        // settlement and commits — the mechanism is identical regardless of which source
        // actually wins, so simulating either side here proves the same guarantee for both.
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(
                    normalizeJdbcUrl(System.getenv("DB_URL")), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"))) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SELECT * FROM payment_order WHERE order_id = '" + orderId + "' FOR UPDATE");
                }
                holderHasLock.countDown();
                releaseHolder.await(5, TimeUnit.SECONDS);
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT INTO payment (school_id, student_id, student_name, class_name, session, " +
                            "month, amount, payment_id, order_id, payment_date, status, razorpay_signature, amount_paid) " +
                            "VALUES (" + SCHOOL + ", 'psi-it-student-5', 'IT Student', '6A', '2025-2026', " +
                            "'010000000000', 250000, 'psi-it-pay-client-winner', '" + orderId + "', now(), 'success', 'sig', 250000)");
                    st.execute("UPDATE payment_order SET consumed = true WHERE order_id = '" + orderId + "'");
                }
                conn.commit();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(holderHasLock.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: the real settlement service, called exactly as the webhook-recovery path
        // would call it (RAZORPAY_WEBHOOK source, null client signature) — must block on the
        // same PaymentOrder lock, then correctly reject once it sees the order already
        // consumed by a different paymentId.
        long waiterStart = System.currentTimeMillis();
        CompletableFuture<PaymentSettlementService.SettlementResult> waiter = CompletableFuture.supplyAsync(() -> {
            com.indraacademy.ias_management.util.SchoolContext.set(SCHOOL);
            try {
                return settlementService.settle(orderId, "psi-it-pay-webhook-loser", null, SCHOOL,
                        PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK);
            } finally {
                com.indraacademy.ias_management.util.SchoolContext.clear();
            }
        });

        Thread.sleep(800);
        releaseHolder.countDown();

        PaymentSettlementService.SettlementResult result = waiter.get(10, TimeUnit.SECONDS);
        holder.get(5, TimeUnit.SECONDS);
        long waiterFinished = System.currentTimeMillis();

        assertThat(waiterFinished - waiterStart).isGreaterThanOrEqualTo(750);
        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.REJECTED);

        Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId);
        assertThat(paymentCount).isEqualTo(1);
    }

    /** Required-Correctness-Fix-1, Task 8 — the single most important proof in this fix: a
     * genuine Razorpay webhook-recovery settlement (RAZORPAY_WEBHOOK source, no client signature)
     * must succeed end-to-end with SchoolContext completely absent, exactly as it runs in
     * production (JwtAuthFilter never touches /api/webhooks/*, so SchoolContext.get() is null
     * for the entire request). Before this fix, StudentFeesService.markFeesAsPaid derived
     * schoolId from securityUtil.getSchoolId() instead of payment.getSchoolId(), so this exact
     * scenario threw IllegalStateException and rolled back the whole settlement. The existing
     * concurrent-webhook test in this class sets SchoolContext before calling settle() and only
     * proves lock/rejection behavior for the LOSING attempt — it never reaches markFeesAsPaid,
     * so it does NOT prove webhook safety. This test does: SchoolContext is cleared before the
     * settle() call and never repopulated. */
    @Test
    void webhookRecoverySettlement_succeedsWithNoAmbientSchoolContext_studentFeesAllocatedAndOrderConsumed() {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2025-2026', DATE '2025-04-01', DATE '2026-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        String orderId = "psi-it-order-webhook-nocontext";
        String paymentId = "psi-it-pay-webhook-nocontext";
        String studentId = "psi-it-student-webhook-nocontext";
        Long studentFeesId = jdbc.queryForObject(
                "INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '6A', 1, false, false, '2025-2026', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED') RETURNING id",
                Long.class, SCHOOL, studentId, sessionId);
        // amount is in paise (PaymentSettlementService.buildPayment reads paymentOrder.getAmount()
        // directly as amountInPaise) and deliberately overpays the ₹1000 (=100000 paise) base
        // due — markFeesAsPaid computes a today-relative late fee (up to ₹630 per the
        // calculateLateFees tiers) inside the same allocation call, so an exact-due order would
        // flakily under-pay depending on the current date. 200000 paise (₹2000) comfortably
        // covers base due + worst-case late fee regardless of when this test runs (same overpay
        // pattern used in StudentFeesServiceSessionAuthorityPostgresIT's persistPayment helper).
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, " +
                        "academic_session_id, month, amount, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', ?, '100000000000', 200000, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ?)",
                orderId, SCHOOL, studentId, sessionId, LocalDateTime.now());

        // The crux of the test: no code below this point ever calls SchoolContext.set(...)
        // again — settle() runs exactly as it would for a real, unauthenticated /api/webhooks/*
        // request.
        com.indraacademy.ias_management.util.SchoolContext.clear();

        PaymentSettlementService.SettlementResult result = settlementService.settle(
                orderId, paymentId, null, SCHOOL, PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(result.payment()).isNotNull();
        assertThat(result.payment().getSchoolId()).isEqualTo(SCHOOL);

        assertThat(jdbc.queryForObject("SELECT consumed FROM payment_order WHERE order_id = ?", Boolean.class, orderId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE payment_id = ? AND school_id = ?",
                        Integer.class, paymentId, SCHOOL))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT paid FROM student_fees WHERE id = ?", Boolean.class, studentFeesId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payment_student_fees_allocation WHERE payment_id = ? AND academic_session_id = ?",
                        Integer.class, result.payment().getId(), sessionId))
                .isGreaterThan(0);
    }

    /** Fix B — AttendanceService webhook tenant context: updateChargePaidAfterPayment had its
     * own instance of the exact bug fixed for markFeesAsPaid in Fix 1 — it derived schoolId
     * from securityUtil.getSchoolId() (ambient SchoolContext), which is never populated for a
     * genuine /api/webhooks/* request, so the attendance charge-paid side effect silently
     * no-op'd (logged a warning, never threw) for every real webhook settlement. Now
     * PaymentSettlementService passes its own already-validated schoolId explicitly. This
     * proves the side effect actually happens, with SchoolContext completely absent. */
    @Test
    void webhookRecoverySettlement_updatesAttendanceChargePaid_withNoAmbientSchoolContext() {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2025-2026', DATE '2025-04-01', DATE '2026-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        String orderId = "psi-it-order-webhook-attendance";
        String paymentId = "psi-it-pay-webhook-attendance";
        String studentId = "psi-it-student-webhook-attendance";
        jdbc.update("INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '6A', 1, false, false, '2025-2026', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED')",
                SCHOOL, studentId, sessionId);
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, " +
                        "academic_session_id, month, amount, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', ?, '100000000000', 200000, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ?)",
                orderId, SCHOOL, studentId, sessionId, LocalDateTime.now());
        jdbc.update("INSERT INTO attendance (school_id, student_id, class_name, date, status, charge_paid) " +
                "VALUES (?, ?, '6A', DATE '2025-06-15', 'PRESENT', false)", SCHOOL, studentId);

        com.indraacademy.ias_management.util.SchoolContext.clear();

        PaymentSettlementService.SettlementResult result = settlementService.settle(
                orderId, paymentId, null, SCHOOL, PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK);

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(jdbc.queryForObject(
                        "SELECT charge_paid FROM attendance WHERE school_id = ? AND student_id = ?",
                        Boolean.class, SCHOOL, studentId))
                .isTrue();
    }

    /** Fix B, defense-in-depth: a stale/wrong ambient SchoolContext must never redirect or
     * suppress the attendance side effect — PaymentSettlementService's own validated schoolId
     * is authoritative regardless of whatever the current thread happens to have set. -1L is
     * a schoolId that doesn't even exist, deliberately, to prove it's never consulted at all. */
    @Test
    void webhookRecoverySettlement_attendanceUpdate_ignoresWrongAmbientSchoolContext() {
        Long sessionId = jdbc.queryForObject(
                "INSERT INTO academic_session (school_id, label, start_date, end_date, is_current, created_at) " +
                        "VALUES (?, '2025-2026', DATE '2025-04-01', DATE '2026-03-31', false, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, SCHOOL);
        String orderId = "psi-it-order-webhook-attendance-wrongctx";
        String paymentId = "psi-it-pay-webhook-attendance-wrongctx";
        String studentId = "psi-it-student-webhook-attendance-wrongctx";
        jdbc.update("INSERT INTO student_fees (school_id, student_id, class_name, month, paid, takes_bus, year, " +
                        "academic_session_id, distance, manually_paid, amount_paid, base_amount_due, bus_fee_due, " +
                        "discount_amount, snapshot_status) VALUES (?, ?, '6A', 1, false, false, '2025-2026', ?, " +
                        "0, false, 0, 1000, 0, 0, 'COMPUTED')",
                SCHOOL, studentId, sessionId);
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, " +
                        "academic_session_id, month, amount, bus_fee, tuition_fee, annual_charges, lab_charges, " +
                        "eca_project, examination_fee, additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', ?, '100000000000', 200000, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ?)",
                orderId, SCHOOL, studentId, sessionId, LocalDateTime.now());
        jdbc.update("INSERT INTO attendance (school_id, student_id, class_name, date, status, charge_paid) " +
                "VALUES (?, ?, '6A', DATE '2025-06-15', 'PRESENT', false)", SCHOOL, studentId);

        // Deliberately wrong, nonexistent ambient context — must be completely ignored.
        com.indraacademy.ias_management.util.SchoolContext.set(-1L);
        PaymentSettlementService.SettlementResult result = settlementService.settle(
                orderId, paymentId, null, SCHOOL, PaymentSettlementService.SettlementSource.RAZORPAY_WEBHOOK);
        com.indraacademy.ias_management.util.SchoolContext.clear();

        assertThat(result.outcome()).isEqualTo(PaymentSettlementService.Outcome.SETTLED);
        assertThat(jdbc.queryForObject(
                        "SELECT charge_paid FROM attendance WHERE school_id = ? AND student_id = ?",
                        Boolean.class, SCHOOL, studentId))
                .isTrue();
    }

    private static String normalizeJdbcUrl(String url) {
        return url.startsWith("jdbc:") ? url : "jdbc:" + url;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** Called from within @Test bodies, after @BeforeEach has already ended the default
     * test-managed transaction — this runs as a plain, immediately-committing JDBC statement,
     * not inside another TestTransaction cycle (there is none left to end here). */
    private void insertPaymentOrder(String orderId, String studentId, String month) {
        jdbc.update("INSERT INTO payment_order (order_id, school_id, student_id, class_name, session, month, " +
                        "amount, bus_fee, tuition_fee, annual_charges, lab_charges, eca_project, examination_fee, " +
                        "additional_charges, late_fees, platform_fee, consumed, created_at) " +
                        "VALUES (?, ?, ?, '6A', '2025-2026', ?, 250000, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, ?)",
                orderId, SCHOOL, studentId, month, LocalDateTime.now());
    }

    private void markOrderConsumed(String orderId) {
        jdbc.update("UPDATE payment_order SET consumed = true WHERE order_id = ?", orderId);
    }

    private void insertPayment(String paymentId, String orderId, String studentId, long schoolId) {
        jdbc.update("INSERT INTO payment " +
                        "(school_id, student_id, student_name, class_name, session, month, amount, " +
                        "payment_id, order_id, payment_date, status, razorpay_signature, amount_paid) " +
                        "VALUES (?, ?, 'IT Student', '6A', '2025-2026', '010000000000', 250000, " +
                        "?, ?, ?, 'success', 'sig', 250000)",
                schoolId, studentId, paymentId, orderId, LocalDateTime.now());
    }
}
