package com.indraacademy.ias_management.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnlinePaymentPricingCalculatorTest {

    @Test
    void grossUpRecoversGatewayChargeOnFinalCapturedAmount() {
        var result = OnlinePaymentPricingCalculator.calculate(1_000_000L, 0L, 200, 1800, 2_000L);

        assertThat(result.gatewayRecoveryFeePaise()).isEqualTo(24_219L);
        assertThat(result.onlineConvenienceFeePaise()).isEqualTo(26_219L);
        assertThat(result.totalPayablePaise()).isEqualTo(1_026_219L);
    }

    @Test
    void includesOtherCapturedNonConvenienceAmountsInGrossUpButNotPrincipal() {
        var result = OnlinePaymentPricingCalculator.calculate(10_000L, 2_500L, 200, 1800, 2_000L);

        assertThat(result.schoolLiabilityPrincipalPaise()).isEqualTo(10_000L);
        assertThat(result.otherCapturedNonConveniencePaise()).isEqualTo(2_500L);
        assertThat(result.totalPayablePaise()).isGreaterThan(14_500L);
    }

    @Test
    void zeroRateStillAppliesConfiguredTransactionFee() {
        var result = OnlinePaymentPricingCalculator.calculate(100L, 0L, 0, 1800, 2_000L);
        assertThat(result.gatewayRecoveryFeePaise()).isZero();
        assertThat(result.totalPayablePaise()).isEqualTo(2_100L);
    }

    @Test
    void exactArithmeticRoundsUpToNextPaise() {
        var result = OnlinePaymentPricingCalculator.calculate(1L, 0L, 1, 0, 0L);
        assertThat(result.gatewayRecoveryFeePaise()).isEqualTo(1L);
        assertThat(result.totalPayablePaise()).isEqualTo(2L);
    }

    @Test
    void rejectsInvalidInputsAndRates() {
        assertThatThrownBy(() -> OnlinePaymentPricingCalculator.calculate(-1, 0, 200, 1800, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OnlinePaymentPricingCalculator.calculate(1, 0, -1, 1800, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OnlinePaymentPricingCalculator.calculate(1, 0, 10_000, 1800, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OnlinePaymentPricingCalculator.calculate(1, 0, 200, 10_000, 2000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OnlinePaymentPricingCalculator.calculate(1, 0, 200, 1800, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsRepresentativeMoneyValues() {
        for (long principal : new long[]{100L, 9_900L, 10_000L, 99_999L, 1_000_000L, 100_000_000L}) {
            var result = OnlinePaymentPricingCalculator.calculate(principal, 0, 200, 1800, 2_000L);
            assertThat(result.totalPayablePaise()).isGreaterThan(principal);
            assertThat(result.totalPayablePaise()).isEqualTo(Math.addExact(principal, result.onlineConvenienceFeePaise()));
        }
    }
}
