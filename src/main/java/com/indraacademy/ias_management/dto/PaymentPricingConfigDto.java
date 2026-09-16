package com.indraacademy.ias_management.dto;

import com.indraacademy.ias_management.entity.PaymentPricingConfig;

import java.time.Instant;

/** Platform Admin-facing view of one pricing version — unlike the parent-facing checkout
 * quote, this intentionally exposes the full internal component split (gateway rate/tax,
 * Edunexify fee) since these users manage the pricing model itself. Never returned to a
 * non-platform-admin caller (see PlatformPaymentPricingController's authorization). */
public class PaymentPricingConfigDto {
    private Long id;
    private String gatewayProvider;
    private Integer gatewayRateBps;
    private Integer gatewayTaxRateBps;
    private Long edunexifyTransactionFeePaise;
    private Instant effectiveFrom;
    private Instant createdAt;
    private String createdBy;
    private Instant cancelledAt;
    private String cancelledBy;
    /** CURRENT | SCHEDULED | HISTORICAL | CANCELLED — derived server-side (against the same
     * Clock PaymentPricingService uses) so the frontend never has to reimplement the
     * effective-time comparison itself. */
    private String status;

    public static PaymentPricingConfigDto from(PaymentPricingConfig c, String status) {
        PaymentPricingConfigDto dto = new PaymentPricingConfigDto();
        dto.id = c.getId();
        dto.gatewayProvider = c.getGatewayProvider();
        dto.gatewayRateBps = c.getGatewayRateBps();
        dto.gatewayTaxRateBps = c.getGatewayTaxRateBps();
        dto.edunexifyTransactionFeePaise = c.getEdunexifyTransactionFeePaise();
        dto.effectiveFrom = c.getEffectiveFrom();
        dto.createdAt = c.getCreatedAt();
        dto.createdBy = c.getCreatedBy();
        dto.cancelledAt = c.getCancelledAt();
        dto.cancelledBy = c.getCancelledBy();
        dto.status = status;
        return dto;
    }

    public Long getId() { return id; }
    public String getGatewayProvider() { return gatewayProvider; }
    public Integer getGatewayRateBps() { return gatewayRateBps; }
    public Integer getGatewayTaxRateBps() { return gatewayTaxRateBps; }
    public Long getEdunexifyTransactionFeePaise() { return edunexifyTransactionFeePaise; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancelledBy() { return cancelledBy; }
    public String getStatus() { return status; }
}
