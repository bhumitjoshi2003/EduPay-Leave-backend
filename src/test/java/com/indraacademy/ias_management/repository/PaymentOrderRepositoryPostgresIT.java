package com.indraacademy.ias_management.repository;

import com.indraacademy.ias_management.entity.PaymentOrder;
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
 * Razorpay Payment-Integrity Hardening, Phase A (V62) — proof against a real, PROD-shaped
 * PostgreSQL database with Flyway enabled. H2 cannot be trusted to validate PostgreSQL's
 * partial unique index semantics the same way it can validate ordinary constraints, so this
 * follows the same *PostgresIT convention as ClassTeacherResponsibilityRepositoryPostgresIT
 * etc. — skipped unless DB_URL is set, never run against the default H2 test database.
 *
 * <p>Covers exactly what Phase A changed: the new uq_payment_order_id_razorpay partial unique
 * index, and PaymentOrderRepository's new findByOrderIdForUpdate locked finder. Does not (and
 * cannot, with any repository-level test) prove real concurrent blocking semantics of
 * PESSIMISTIC_WRITE — that would need two genuinely concurrent transactions and is not
 * something a single-threaded repository test can honestly claim to demonstrate.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class PaymentOrderRepositoryPostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentOrderRepository repository;

    private static final long SCHOOL_A = -96001L;

    // ── PaymentOrder: existing + new locked finder ──────────────────────────────────────────

    @Test
    void unlockedFinderStillWorksUnchanged() {
        PaymentOrder order = repository.saveAndFlush(paymentOrder("V62-IT-ORDER-1"));

        assertThat(repository.findByOrderId("V62-IT-ORDER-1")).contains(order);
        assertThat(repository.findByOrderIdAndSchoolId("V62-IT-ORDER-1", SCHOOL_A)).contains(order);
    }

    @Test
    void lockedFinderLoadsTheSameRowAsTheUnlockedFinder() {
        PaymentOrder order = repository.saveAndFlush(paymentOrder("V62-IT-ORDER-2"));

        var locked = repository.findByOrderIdForUpdate("V62-IT-ORDER-2");

        assertThat(locked).isPresent();
        assertThat(locked.get().getId()).isEqualTo(order.getId());
        assertThat(locked.get().isConsumed()).isFalse();
    }

    @Test
    void lockedFinderReturnsEmptyForAnUnknownOrderId() {
        assertThat(repository.findByOrderIdForUpdate("V62-IT-NO-SUCH-ORDER")).isEmpty();
    }

    // ── V62: uq_payment_order_id_razorpay partial unique index ──────────────────────────────

    @Test
    void razorpayPathRejectsASecondPaymentForTheSameOrderId() {
        insertPayment("V62-IT-PAY-1", "V62-IT-DUP-ORDER", null);

        assertThatThrownBy(() -> insertPayment("V62-IT-PAY-2", "V62-IT-DUP-ORDER", null))
                .isInstanceOf(DataAccessException.class);
    }

    /** The regression this migration exists to avoid: StudentFeesService.recordManualPayment
     * hardcodes order_id to the literal "Manual payment" for every manual payment ever
     * recorded. A naive UNIQUE(order_id) would make it impossible to ever record a second
     * manual payment anywhere — the partial index (WHERE manual_payment_mode IS NULL) must
     * NOT apply to these rows. */
    @Test
    void manualPaymentSentinelOrderIdCanRepeatFreely() {
        insertPayment("V62-IT-MANUAL-PAY-1", "Manual payment", "CASH");

        // Must not throw — this is real, current, intentional behavior.
        insertPayment("V62-IT-MANUAL-PAY-2", "Manual payment", "CHEQUE");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment WHERE order_id = 'Manual payment' AND payment_id IN (?, ?)",
                Integer.class, "V62-IT-MANUAL-PAY-1", "V62-IT-MANUAL-PAY-2");
        assertThat(count).isEqualTo(2);
    }

    private PaymentOrder paymentOrder(String orderId) {
        PaymentOrder order = new PaymentOrder();
        order.setOrderId(orderId);
        order.setSchoolId(SCHOOL_A);
        order.setStudentId("V62-IT-STUDENT");
        order.setClassName("6A");
        order.setSession("2025-2026");
        order.setMonth("000000000000");
        order.setAmount(10000);
        return order;
    }

    private void insertPayment(String paymentId, String orderId, String manualPaymentMode) {
        jdbc.update("INSERT INTO payment " +
                        "(school_id, student_id, student_name, class_name, session, month, amount, " +
                        "payment_id, order_id, payment_date, status, razorpay_signature, amount_paid, manual_payment_mode) " +
                        "VALUES (?, 'V62-IT-STUDENT', 'IT Student', '6A', '2025-2026', '000000000000', 10000, " +
                        "?, ?, ?, 'success', 'sig', 10000, ?)",
                SCHOOL_A, paymentId, orderId, LocalDateTime.now(), manualPaymentMode);
    }
}
