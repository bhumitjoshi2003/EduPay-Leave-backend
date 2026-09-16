package com.indraacademy.ias_management.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Platform-level pricing configuration for online fee payments. */
@Component
@Validated
@ConfigurationProperties(prefix = "payment")
public class PaymentPricingProperties {
    private final Gateway gateway = new Gateway();

    @Min(0)
    private Long edunexifyTransactionFeePaise;

    public Gateway getGateway() { return gateway; }
    public Long getEdunexifyTransactionFeePaise() { return edunexifyTransactionFeePaise; }
    public void setEdunexifyTransactionFeePaise(Long value) { this.edunexifyTransactionFeePaise = value; }

    @jakarta.annotation.PostConstruct
    void requireExplicitConfiguration() {
        if (gateway.rateBps == null || gateway.taxRateBps == null || edunexifyTransactionFeePaise == null) {
            throw new IllegalStateException("Online payment pricing configuration must be explicitly provided");
        }
    }

    public static class Gateway {
        @Min(0) @Max(9999)
        private Integer rateBps;
        @Min(0) @Max(9999)
        private Integer taxRateBps;

        public Integer getRateBps() { return rateBps; }
        public void setRateBps(Integer value) { this.rateBps = value; }
        public Integer getTaxRateBps() { return taxRateBps; }
        public void setTaxRateBps(Integer value) { this.taxRateBps = value; }
    }
}
