package com.indraacademy.ias_management.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Razorpay Refund-Integrity Hardening, Phase A (V63) — proof against a real, PROD-shaped
 * PostgreSQL database with Flyway enabled. H2 cannot be trusted to validate PostgreSQL's CHECK
 * constraint NULL-handling or partial unique index semantics, so this follows the same
 * *PostgresIT convention PaymentOrderRepositoryPostgresIT established for V62.
 *
 * <p>Covers exactly what V63 added: payment.refunded_amount_paise's two CHECK constraints
 * (never negative, never exceeding amount_paid — including the NULL-amount_paid edge case a
 * naive constraint would silently let through) and refund.provider_idempotency_key's partial
 * unique index. No application code (PaymentService.processRefund, RazorpayService.createRefund)
 * is exercised here — this is schema-level proof only; those methods are unchanged by Phase A.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class RefundIntegrityFoundationPostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;

    private static final long SCHOOL_A = -96301L;

    // ── payment.refunded_amount_paise ───────────────────────────────────────────────────────

    @Test
    void freshPaymentDefaultsRefundedAmountToZero() {
        Long id = insertPayment("V63-IT-PAY-1", 10000);

        Long refunded = jdbc.queryForObject(
                "SELECT refunded_amount_paise FROM payment WHERE id = ?", Long.class, id);
        assertThat(refunded).isZero();
    }

    @Test
    void negativeRefundedAmountIsRejected() {
        Long id = insertPayment("V63-IT-PAY-2", 10000);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment SET refunded_amount_paise = -1 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refundedAmountExceedingAmountPaidIsRejected() {
        Long id = insertPayment("V63-IT-PAY-3", 10000);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment SET refunded_amount_paise = 10001 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void refundedAmountEqualToAmountPaidIsAllowed() {
        Long id = insertPayment("V63-IT-PAY-4", 10000);

        // Must not throw — a full refund is the normal, expected end state.
        jdbc.update("UPDATE payment SET refunded_amount_paise = 10000 WHERE id = ?", id);

        Long refunded = jdbc.queryForObject(
                "SELECT refunded_amount_paise FROM payment WHERE id = ?", Long.class, id);
        assertThat(refunded).isEqualTo(10000L);
    }

    /** The exact hole a naive {@code CHECK (refunded_amount_paise <= amount_paid)} would leave
     * open: Postgres treats a NULL comparison as UNKNOWN, and UNKNOWN passes a CHECK. Confirms
     * the constraint was written as {@code amount_paid IS NOT NULL AND ...}, not the naive form —
     * since refunded_amount_paise is itself NOT NULL DEFAULT 0, a row can never actually reach
     * amount_paid IS NULL at all once this constraint exists: even the attempt to null out
     * amount_paid on an existing row (refunded_amount_paise already 0) is rejected outright,
     * which is the strongest possible closure of the hole — not merely "handled once reached". */
    @Test
    void amountPaidCanNeverBeNulledOutOnceTheGuardExists() {
        Long id = insertPayment("V63-IT-PAY-5", 10000);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE payment SET amount_paid = NULL WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    // ── refund.provider_idempotency_key ─────────────────────────────────────────────────────

    @Test
    void twoRefundRowsWithTheSameNonNullProviderIdempotencyKeyAreRejected() {
        Long paymentId = insertPayment("V63-IT-PAY-6", 10000);
        insertRefund(paymentId, "V63-IT-PROVIDER-KEY-DUP");

        assertThatThrownBy(() -> insertRefund(paymentId, "V63-IT-PROVIDER-KEY-DUP"))
                .isInstanceOf(DataAccessException.class);
    }

    /** Existing refund behavior has not migrated to the new key yet (Phase A adds only schema),
     * so every refund row today's unchanged code creates still has a NULL provider key — the
     * partial index must not affect these rows at all. */
    @Test
    void multipleRefundRowsWithNullProviderIdempotencyKeyRemainAllowed() {
        Long paymentId = insertPayment("V63-IT-PAY-7", 10000);

        // Must not throw.
        insertRefund(paymentId, null);
        insertRefund(paymentId, null);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund WHERE payment_id = ? AND provider_idempotency_key IS NULL",
                Integer.class, paymentId);
        assertThat(count).isEqualTo(2);
    }

    private Long insertPayment(String paymentId, int amountPaid) {
        jdbc.update("INSERT INTO payment " +
                        "(school_id, student_id, student_name, class_name, session, month, amount, " +
                        "payment_id, order_id, payment_date, status, razorpay_signature, amount_paid) " +
                        "VALUES (?, 'V63-IT-STUDENT', 'IT Student', '6A', '2025-2026', '000000000000', ?, " +
                        "?, ?, ?, 'success', 'sig', ?)",
                SCHOOL_A, amountPaid, paymentId, paymentId + "-ORDER", LocalDateTime.now(), amountPaid);
        return jdbc.queryForObject("SELECT id FROM payment WHERE payment_id = ?", Long.class, paymentId);
    }

    private void insertRefund(Long paymentId, String providerIdempotencyKey) {
        jdbc.update("INSERT INTO refund " +
                        "(payment_id, school_id, student_id, session, months_refunded, amount_paise, " +
                        "status, legacy_approximation, created_at, provider_idempotency_key) " +
                        "VALUES (?, ?, 'V63-IT-STUDENT', '2025-2026', '000000000000', 1000, " +
                        "'success', false, ?, ?)",
                paymentId, SCHOOL_A, LocalDateTime.now(), providerIdempotencyKey);
    }
}
