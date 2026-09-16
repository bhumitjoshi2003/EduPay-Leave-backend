package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * A single effective-dated, platform-level (never school-scoped) payment pricing version.
 * Immutable once persisted — never updated in place except for the cancellation fields, and
 * only while still scheduled in the future (see PaymentPricingService). A PaymentOrder/Payment
 * never recalculates from this table; it only records which version (paymentPricingConfigId)
 * produced its own already-persisted snapshot.
 */
@Entity
@Table(name = "payment_pricing_config")
@Data
public class PaymentPricingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "gateway_provider", nullable = false, length = 30)
    private String gatewayProvider;

    @Column(name = "gateway_rate_bps", nullable = false)
    private Integer gatewayRateBps;

    @Column(name = "gateway_tax_rate_bps", nullable = false)
    private Integer gatewayTaxRateBps;

    @Column(name = "edunexify_transaction_fee_paise", nullable = false)
    private Long edunexifyTransactionFeePaise;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;
}
