package com.indraacademy.ias_management.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPricingPropertiesTest {
    private final jakarta.validation.Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void missingConfigurationFailsClosed() {
        assertThatThrownBy(new PaymentPricingProperties()::requireExplicitConfiguration)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidConfiguredValuesFailValidation() {
        PaymentPricingProperties properties = configured(-1, 10_000, -1);
        assertThat(validator.validate(properties)).isNotEmpty();
        assertThat(validator.validate(properties.getGateway())).hasSize(2);
    }

    @Test
    void validExplicitConfigurationPasses() {
        PaymentPricingProperties properties = configured(200, 1800, 2000);
        properties.requireExplicitConfiguration();
        assertThat(validator.validate(properties)).isEmpty();
        assertThat(validator.validate(properties.getGateway())).isEmpty();
    }

    private PaymentPricingProperties configured(int rate, int tax, long fee) {
        PaymentPricingProperties properties = new PaymentPricingProperties();
        properties.getGateway().setRateBps(rate);
        properties.getGateway().setTaxRateBps(tax);
        properties.setEdunexifyTransactionFeePaise(fee);
        return properties;
    }
}
