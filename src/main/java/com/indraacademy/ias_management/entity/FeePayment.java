package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * New payment entity for Architecture C.
 * Named FeePayment to avoid collision with legacy Payment entity during migration.
 */
@Entity
@Table(name = "fee_payment", indexes = {
        @Index(name = "idx_fp_student_school",
                columnList = "school_id, student_id"),
        @Index(name = "idx_fp_razorpay_order",
                columnList = "razorpay_order_id")
})
public class FeePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    /** Amount in paise */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 20)
    private PaymentMode paymentMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    // Razorpay fields (nullable for manual payments)
    @Column(name = "razorpay_payment_id", length = 50)
    private String razorpayPaymentId;

    @Column(name = "razorpay_order_id", length = 50)
    private String razorpayOrderId;

    @Column(name = "razorpay_signature", length = 255)
    private String razorpaySignature;

    /** For manual payments: cheque number, UPI ref, etc. */
    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "received_by", length = 100)
    private String receivedBy;

    @Column(name = "payment_date", nullable = false)
    private LocalDateTime paymentDate;

    @OneToMany(mappedBy = "feePayment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaymentAllocation> allocations = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public FeePayment() {
        this.paymentDate = LocalDateTime.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public PaymentMode getPaymentMode() { return paymentMode; }
    public void setPaymentMode(PaymentMode paymentMode) { this.paymentMode = paymentMode; }

    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public String getRazorpayOrderId() { return razorpayOrderId; }
    public void setRazorpayOrderId(String razorpayOrderId) { this.razorpayOrderId = razorpayOrderId; }

    public String getRazorpaySignature() { return razorpaySignature; }
    public void setRazorpaySignature(String razorpaySignature) { this.razorpaySignature = razorpaySignature; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }

    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }

    public List<PaymentAllocation> getAllocations() { return allocations; }
    public void setAllocations(List<PaymentAllocation> allocations) { this.allocations = allocations; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public void addAllocation(PaymentAllocation allocation) {
        allocations.add(allocation);
        allocation.setFeePayment(this);
    }
}
