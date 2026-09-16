package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-PostgreSQL proof for what a Mockito test cannot honestly make: the partial unique index
 * (uq_ppc_provider_effective_from_active) genuinely rejecting a duplicate active schedule, and
 * the real SQL "latest non-cancelled effective_from <= now" selection actually picking the
 * right row. Effective-time boundaries are tested by inserting past/future timestamps relative
 * to the real wall clock (no sleep needed) rather than injecting a fake Clock — PaymentPricingService
 * itself uses the real ClockConfig bean here, exactly as it does in production.
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentPricingService.class, com.indraacademy.ias_management.config.ClockConfig.class})
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class PaymentPricingConfigPostgresIT {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("DB_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("DB_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentPricingService paymentPricingService;

    /** Ends the @DataJpaTest-managed per-test transaction immediately, before any fixture is
     * inserted — otherwise PaymentPricingService.createVersion's own @Transactional boundary
     * merely joins this same outer test transaction instead of behaving like the genuine
     * top-level transaction it is in production. That distinction matters here specifically:
     * the duplicate-schedule test deliberately triggers a real DataIntegrityViolationException,
     * and Postgres aborts the WHOLE transaction on any SQL error until an explicit rollback —
     * if that error happened inside a transaction this test still needed for its own later
     * assertions/cleanup, every subsequent statement would fail too. Matches the pattern
     * already established by RefundSettlementServicePostgresIT for the same reason. */
    @BeforeEach
    void endTestManagedTransaction() {
        TestTransaction.flagForCommit();
        TestTransaction.end();
    }

    // payment_pricing_config is deliberately global/platform-level (Task 27 — no school_id to
    // scope by), and GatewayProvider currently declares only RAZORPAY (Task 29 — no multi-
    // gateway behavior yet), so this suite runs against a disposable, freshly-created database
    // (the same convention every other *PostgresIT class in this repo already relies on) and
    // simply clears every row for that one provider between tests.
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_pricing_config WHERE gateway_provider = ?",
                PaymentPricingService.GatewayProvider.RAZORPAY.name());
    }

    @Test
    void noConfiguration_resolveActive_throwsPricingUnavailable() {
        assertThatThrownBy(() -> paymentPricingService.resolveActive(providerEnum()))
                .isInstanceOf(PaymentPricingService.PricingUnavailableException.class);
    }

    @Test
    void immediateConfiguration_isActiveRightAway() {
        insert(200, 1800, 2000L, Instant.now().minusSeconds(5), null);

        var active = paymentPricingService.resolveActive(providerEnum());

        assertThat(active.getGatewayRateBps()).isEqualTo(200);
    }

    @Test
    void futureConfiguration_notActiveBeforeItsEffectiveTime() {
        // Only a future row exists — nothing should resolve yet.
        insert(220, 1800, 2000L, Instant.now().plusSeconds(3600), null);

        assertThatThrownBy(() -> paymentPricingService.resolveActive(providerEnum()))
                .isInstanceOf(PaymentPricingService.PricingUnavailableException.class);
    }

    @Test
    void futureConfiguration_becomesActiveOnceItsEffectiveTimeHasPassed() {
        // Simulate "the scheduled time has now arrived" by inserting a row whose effective_from
        // is already in the past relative to the real wall clock — no sleep needed, this is
        // exactly what resolveActive sees once real time catches up to a genuinely scheduled row.
        insert(220, 1800, 2000L, Instant.now().minusSeconds(1), null);

        var active = paymentPricingService.resolveActive(providerEnum());

        assertThat(active.getGatewayRateBps()).isEqualTo(220);
    }

    @Test
    void latestEffectiveConfiguration_wins_overAnOlderStillActiveOne() {
        insert(200, 1800, 2000L, Instant.now().minusSeconds(7200), null);
        insert(220, 1800, 2000L, Instant.now().minusSeconds(10), null);

        var active = paymentPricingService.resolveActive(providerEnum());

        assertThat(active.getGatewayRateBps()).isEqualTo(220);
    }

    @Test
    void cancelledFutureConfiguration_neverActivates_evenAfterItsEffectiveTimeArrives() {
        Long id = insert(220, 1800, 2000L, Instant.now().minusSeconds(1), null);
        jdbc.update("UPDATE payment_pricing_config SET cancelled_at = now(), cancelled_by = 'admin' WHERE id = ?", id);
        // The only surviving candidate is now the older, still-active 200bps row.
        insert(200, 1800, 2000L, Instant.now().minusSeconds(7200), null);

        var active = paymentPricingService.resolveActive(providerEnum());

        assertThat(active.getGatewayRateBps()).isEqualTo(200);
    }

    @Test
    void duplicateActiveEffectiveFrom_rejectedCleanly_neverAGeneric500() {
        Instant effectiveFrom = Instant.now().plusSeconds(3600);
        paymentPricingService.createVersion(providerEnum(), 200, 1800, 2000L, effectiveFrom, "admin");

        assertThatThrownBy(() -> paymentPricingService.createVersion(providerEnum(), 220, 1800, 2000L, effectiveFrom, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already scheduled");
    }

    @Test
    void historicalConfiguration_cannotBeCancelled_immutabilityEnforced() {
        Long id = insert(200, 1800, 2000L, Instant.now().minusSeconds(7200), null);

        assertThatThrownBy(() -> paymentPricingService.cancelScheduled(id, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still-scheduled");

        // Never mutated — still not cancelled.
        assertThat(jdbc.queryForObject("SELECT cancelled_at FROM payment_pricing_config WHERE id = ?",
                java.sql.Timestamp.class, id)).isNull();
    }

    @Test
    void scheduledConfiguration_canBeCancelled_beforeItEverActivates() {
        var created = paymentPricingService.createVersion(
                providerEnum(), 220, 1800, 2000L, Instant.now().plusSeconds(3600), "admin");

        paymentPricingService.cancelScheduled(created.getId(), "ops-admin");

        assertThat(jdbc.queryForObject("SELECT cancelled_by FROM payment_pricing_config WHERE id = ?",
                String.class, created.getId())).isEqualTo("ops-admin");
        // Cancelling it frees the effective_from slot for a genuine replacement schedule.
        var replacement = paymentPricingService.createVersion(
                providerEnum(), 225, 1800, 2000L, created.getEffectiveFrom(), "admin");
        assertThat(replacement.getId()).isNotEqualTo(created.getId());
    }

    private PaymentPricingService.GatewayProvider providerEnum() {
        return PaymentPricingService.GatewayProvider.RAZORPAY;
    }

    private Long insert(int rateBps, int taxRateBps, long feePaise, Instant effectiveFrom, String cancelledBy) {
        return jdbc.queryForObject(
                "INSERT INTO payment_pricing_config (gateway_provider, gateway_rate_bps, gateway_tax_rate_bps, " +
                        "edunexify_transaction_fee_paise, effective_from, created_at, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, now(), 'it-test') RETURNING id",
                Long.class, PaymentPricingService.GatewayProvider.RAZORPAY.name(), rateBps, taxRateBps, feePaise,
                java.sql.Timestamp.from(effectiveFrom));
    }
}
