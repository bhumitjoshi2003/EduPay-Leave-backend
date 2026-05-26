package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "invoice_line_item")
public class InvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fee_head_id", nullable = false)
    private FeeHead feeHead;

    /** Snapshot of fee head code at invoice creation time (immutable record) */
    @Column(name = "fee_head_code", nullable = false, length = 30)
    private String feeHeadCode;

    @Column(name = "description", length = 200)
    private String description;

    /** All amounts in paise */
    @Column(name = "base_amount", nullable = false)
    private long baseAmount;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    public InvoiceLineItem() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Invoice getInvoice() { return invoice; }
    public void setInvoice(Invoice invoice) { this.invoice = invoice; }

    public FeeHead getFeeHead() { return feeHead; }
    public void setFeeHead(FeeHead feeHead) { this.feeHead = feeHead; }

    public String getFeeHeadCode() { return feeHeadCode; }
    public void setFeeHeadCode(String feeHeadCode) { this.feeHeadCode = feeHeadCode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getBaseAmount() { return baseAmount; }
    public void setBaseAmount(long baseAmount) { this.baseAmount = baseAmount; }

    public long getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(long discountAmount) { this.discountAmount = discountAmount; }

    public long getNetAmount() { return netAmount; }
    public void setNetAmount(long netAmount) { this.netAmount = netAmount; }
}
