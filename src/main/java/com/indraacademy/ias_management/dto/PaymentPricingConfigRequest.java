package com.indraacademy.ias_management.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Platform Admin's request to schedule a new payment pricing version. Safe internal units
 * only (basis points, paise) — the frontend converts from friendly percent/rupee display
 * values before sending, never the other way around. {@code effectiveFrom == null} means
 * "effective immediately", resolved against the server clock by PaymentPricingService. */
public class PaymentPricingConfigRequest {

    @NotBlank
    private String gatewayProvider;

    @NotNull @Min(0)
    private Integer gatewayRateBps;

    @NotNull @Min(0)
    private Integer gatewayTaxRateBps;

    @NotNull @Min(0)
    private Long edunexifyTransactionFeePaise;

    /** Null means "effective immediately". */
    private Instant effectiveFrom;

    public String getGatewayProvider() { return gatewayProvider; }
    public void setGatewayProvider(String gatewayProvider) { this.gatewayProvider = gatewayProvider; }

    public Integer getGatewayRateBps() { return gatewayRateBps; }
    public void setGatewayRateBps(Integer gatewayRateBps) { this.gatewayRateBps = gatewayRateBps; }

    public Integer getGatewayTaxRateBps() { return gatewayTaxRateBps; }
    public void setGatewayTaxRateBps(Integer gatewayTaxRateBps) { this.gatewayTaxRateBps = gatewayTaxRateBps; }

    public Long getEdunexifyTransactionFeePaise() { return edunexifyTransactionFeePaise; }
    public void setEdunexifyTransactionFeePaise(Long edunexifyTransactionFeePaise) { this.edunexifyTransactionFeePaise = edunexifyTransactionFeePaise; }

    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
}
