package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row per successfully-processed refund event against a Payment — append-only, never
 * mutated after insert. A single Payment may have several rows here (successive partial
 * refunds); {@code SUM(amountPaise) WHERE paymentId = X} is the authoritative "how much of
 * this payment has been refunded so far," used both to reject an over-refund/duplicate
 * refund and to compute how much remains refundable.
 */
@Entity
@Table(name = "refund")
@Data
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "session", nullable = false)
    private String session;

    /** 12-char '0'/'1' bitmask of the months this specific refund event actually reversed —
     * a subset of the original payment's month selection for a partial refund. */
    @Column(name = "months_refunded", nullable = false)
    private String monthsRefunded;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "reason")
    private String reason;

    /** Razorpay's refund id; NULL for a manual (cash/cheque/etc) payment. */
    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "initiated_by")
    private String initiatedBy;

    /** True when this refund had to fall back to the old oldest-month-first approximation
     * against Payment.month because the payment being refunded predates the allocation
     * ledger (payment_student_fees_allocation) and has no allocation rows to reverse
     * precisely. False (the normal case going forward) means the reversal is exact, derived
     * from real persisted allocations. */
    @Column(name = "legacy_approximation", nullable = false)
    private boolean legacyApproximation;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
