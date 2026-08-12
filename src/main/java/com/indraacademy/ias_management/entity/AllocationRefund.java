package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * How much of one specific {@link PaymentStudentFeesAllocation} a specific {@link Refund}
 * event reversed. Append-only. A refund that spans several months/allocations produces one
 * row per allocation it touches, so exactly which allocations were reversed — and by how
 * much each — is a persisted fact, never re-derived or guessed at refund time.
 */
@Entity
@Table(name = "allocation_refund")
@Data
public class AllocationRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    @Column(name = "refund_id", nullable = false)
    private Long refundId;

    /** Denormalized from the allocation at write time — avoids a join for the common "net
     * remaining for this StudentFees row" query. */
    @Column(name = "student_fees_id", nullable = false)
    private Long studentFeesId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
