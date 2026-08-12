package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Server-side record of a Razorpay order at the moment it's created — the thing
 * {@link com.indraacademy.ias_management.service.RazorpayService#verifyPayment} should
 * trust for business data (studentId, amount, months, class, session), instead of the
 * client-supplied "orderDetails" map it used to trust. A Razorpay signature only proves
 * "a payment was captured for orderId X" — it says nothing about which student or amount
 * that order was actually created for. This row is that missing link.
 *
 * schoolId is stamped from the authenticated caller at creation time (never client-supplied)
 * and re-checked at verify time as a second tenant guard beyond the signature itself.
 */
@Entity
@Table(name = "payment_order", indexes = {
    @Index(name = "idx_payment_order_student", columnList = "student_id, school_id")
})
@Data
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column(name = "class_name", nullable = false)
    private String className;

    @Column(name = "session", nullable = false)
    private String session;

    @Column(name = "month", nullable = false)
    private String month;

    @Column(name = "amount", nullable = false)
    private int amount;

    @Column(name = "bus_fee")
    private int busFee;

    @Column(name = "tuition_fee")
    private int tuitionFee;

    @Column(name = "annual_charges")
    private int annualCharges;

    @Column(name = "lab_charges")
    private int labCharges;

    @Column(name = "eca_project")
    private int ecaProject;

    @Column(name = "examination_fee")
    private int examinationFee;

    @Column(name = "additional_charges")
    private int additionalCharges;

    @Column(name = "late_fees")
    private int lateFees;

    @Column(name = "platform_fee")
    private int platformFee;

    /** Flipped true once a payment against this order has been successfully verified —
     * a second verify attempt against the same order is treated as a replay, not a new
     * legitimate payment, even if it carries a different (paymentId, signature) pair. */
    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
