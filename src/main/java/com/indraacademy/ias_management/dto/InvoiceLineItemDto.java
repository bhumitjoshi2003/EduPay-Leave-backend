package com.indraacademy.ias_management.dto;

public class InvoiceLineItemDto {
    private Long id;
    private Long feeHeadId;
    private String feeHeadCode;
    private String description;
    private long baseAmount;
    private long discountAmount;
    private long netAmount;

    public InvoiceLineItemDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getFeeHeadId() { return feeHeadId; }
    public void setFeeHeadId(Long feeHeadId) { this.feeHeadId = feeHeadId; }

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
