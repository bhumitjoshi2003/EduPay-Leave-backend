package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Many-to-many bridge between FeePayment and Invoice.
 * Supports partial payments (one payment across multiple invoices)
 * and multi-payment invoices (one invoice paid by multiple payments).
 */
@Entity
@Table(name = "payment_allocation",
        uniqueConstraints = @UniqueConstraint(name = "uq_payment_invoice",
                columnNames = {"fee_payment_id", "invoice_id"}))
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_payment_id", nullable = false)
    private FeePayment feePayment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    /** Amount from this payment allocated to this invoice, in paise */
    @Column(name = "amount_allocated", nullable = false)
    private long amountAllocated;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public PaymentAllocation() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public FeePayment getFeePayment() { return feePayment; }
    public void setFeePayment(FeePayment feePayment) { this.feePayment = feePayment; }

    public Invoice getInvoice() { return invoice; }
    public void setInvoice(Invoice invoice) { this.invoice = invoice; }

    public long getAmountAllocated() { return amountAllocated; }
    public void setAmountAllocated(long amountAllocated) { this.amountAllocated = amountAllocated; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
