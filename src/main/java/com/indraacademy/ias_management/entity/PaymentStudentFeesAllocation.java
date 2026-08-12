package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Exactly how much of a given Payment was allocated to a given StudentFees row. Immutable
 * once written — a refund never mutates or deletes an allocation, it records a reversal
 * against it in {@link AllocationRefund}. SUM(amountPaise) across a payment's allocation
 * rows always equals that payment's amountPaid; SUM(amountPaise) across a StudentFees row's
 * allocations, minus SUM(amountPaise) of any AllocationRefund rows against them, is that
 * row's current net-paid amount — the authoritative basis for StudentFees.paid/amountPaid.
 */
@Entity
@Table(name = "payment_student_fees_allocation")
@Data
public class PaymentStudentFeesAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "student_fees_id", nullable = false)
    private Long studentFeesId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "session", nullable = false)
    private String session;

    @Column(name = "month", nullable = false)
    private Integer month;

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
