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

    /** Authoritative session reference (Phase B1, foundation only) — see StudentFees.
     * academicSessionId for full rationale. Not yet populated or read anywhere. */
    @Column(name = "academic_session_id")
    private Long academicSessionId;

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

    /** Server-generated (never client-supplied) key persisted BEFORE the Razorpay refund-create
     * call and sent as the X-Refund-Idempotency header — distinct from {@link #idempotencyKey},
     * which is an optional client-facing double-click guard checked only against already-
     * committed rows. NULL for every refund row until a future phase starts populating it and
     * actually sending it to Razorpay; the partial unique index on this column (V63) only
     * constrains non-null values, so existing/legacy rows are unaffected. Not yet read or
     * written by {@code PaymentService.processRefund} or {@code RazorpayService.createRefund}. */
    @Column(name = "provider_idempotency_key")
    private String providerIdempotencyKey;

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
