package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.entity.PaymentPricingConfig;
import com.indraacademy.ias_management.repository.PaymentPricingConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure business-logic coverage for PaymentPricingService, using a fixed injectable Clock —
 * never a wall-clock sleep — so effective-time boundaries are deterministic. Real DB-constraint
 * behavior (the partial unique index, actual effective-time SQL ordering) is covered instead by
 * PaymentPricingConfigPostgresIT, which no Mockito test can honestly prove. */
@ExtendWith(MockitoExtension.class)
class PaymentPricingServiceTest {

    @Mock private PaymentPricingConfigRepository repository;

    private PaymentPricingService service;
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new PaymentPricingService();
        ReflectionTestUtils.setField(service, "repository", repository);
        ReflectionTestUtils.setField(service, "clock", fixedClock);
    }

    @Test
    void resolveActive_noConfiguration_throwsPricingUnavailable() {
        when(repository.findActive("RAZORPAY", NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveActive(PaymentPricingService.GatewayProvider.RAZORPAY))
                .isInstanceOf(PaymentPricingService.PricingUnavailableException.class)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveActive_configurationExists_returnsIt() {
        PaymentPricingConfig config = config(200, 1800, 2000L);
        when(repository.findActive("RAZORPAY", NOW)).thenReturn(Optional.of(config));

        assertThat(service.resolveActive(PaymentPricingService.GatewayProvider.RAZORPAY)).isSameAs(config);
    }

    @Test
    void createVersion_negativeGatewayRateBps_rejected() {
        assertThatThrownBy(() -> service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, -1, 1800, 2000L, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createVersion_gatewayRateBpsAtUpperBound_rejected() {
        assertThatThrownBy(() -> service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 10_000, 1800, 2000L, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createVersion_negativeGatewayTaxRateBps_rejected() {
        assertThatThrownBy(() -> service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 200, -1, 2000L, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createVersion_negativeEdunexifyFee_rejected() {
        assertThatThrownBy(() -> service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 200, 1800, -1L, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createVersion_explicitPastEffectiveFrom_rejected() {
        Instant past = NOW.minusSeconds(60);
        assertThatThrownBy(() -> service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 200, 1800, 2000L, past, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("past");
    }

    @Test
    void createVersion_nullEffectiveFrom_meansImmediateAtServerClock() {
        when(repository.save(any(PaymentPricingConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentPricingConfig created = service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 200, 1800, 2000L, null, "admin");

        assertThat(created.getEffectiveFrom()).isEqualTo(NOW);
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
        assertThat(created.getCreatedBy()).isEqualTo("admin");
    }

    @Test
    void createVersion_explicitFutureEffectiveFrom_preserved() {
        Instant future = NOW.plusSeconds(3600);
        when(repository.save(any(PaymentPricingConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentPricingConfig created = service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 220, 1800, 2000L, future, "admin");

        assertThat(created.getEffectiveFrom()).isEqualTo(future);
    }

    @Test
    void createVersion_duplicateEffectiveFrom_translatesToCleanBusinessConflict() {
        when(repository.save(any(PaymentPricingConfig.class)))
                .thenThrow(new DataIntegrityViolationException("uq_ppc_provider_effective_from_active"));

        assertThatThrownBy(() -> service.createVersion(
                PaymentPricingService.GatewayProvider.RAZORPAY, 200, 1800, 2000L, null, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancelScheduled_alreadyCancelled_rejected() {
        PaymentPricingConfig config = config(220, 1800, 2000L);
        config.setId(5L);
        config.setEffectiveFrom(NOW.plusSeconds(3600));
        config.setCancelledAt(NOW.minusSeconds(10));
        config.setCancelledBy("someone");
        when(repository.findById(5L)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.cancelScheduled(5L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already cancelled");
    }

    @Test
    void cancelScheduled_alreadyEffective_rejected() {
        PaymentPricingConfig config = config(200, 1800, 2000L);
        config.setId(6L);
        config.setEffectiveFrom(NOW.minusSeconds(10)); // already active/historical
        when(repository.findById(6L)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.cancelScheduled(6L, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still-scheduled");
    }

    @Test
    void cancelScheduled_stillFuture_succeeds() {
        PaymentPricingConfig config = config(220, 1800, 2000L);
        config.setId(7L);
        config.setEffectiveFrom(NOW.plusSeconds(3600));
        when(repository.findById(7L)).thenReturn(Optional.of(config));
        when(repository.save(any(PaymentPricingConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentPricingConfig cancelled = service.cancelScheduled(7L, "admin");

        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW);
        assertThat(cancelled.getCancelledBy()).isEqualTo("admin");
        verify(repository).save(config);
    }

    private PaymentPricingConfig config(int rateBps, int taxRateBps, long feePaise) {
        PaymentPricingConfig config = new PaymentPricingConfig();
        config.setGatewayProvider("RAZORPAY");
        config.setGatewayRateBps(rateBps);
        config.setGatewayTaxRateBps(taxRateBps);
        config.setEdunexifyTransactionFeePaise(feePaise);
        config.setEffectiveFrom(NOW);
        config.setCreatedAt(NOW);
        return config;
    }
}
