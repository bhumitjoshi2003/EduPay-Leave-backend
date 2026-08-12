package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;

/**
 * Read-side view of a single {@link com.indraacademy.ias_management.entity.StudentFeesLineItem}
 * row, in rupees. grossAmount/discountAmount/netAmount always satisfy
 * netAmount = grossAmount - discountAmount, mirroring the DB-enforced invariant on the
 * underlying entity.
 */
public class FeeLineItemDto {
    private String lineItemType;
    private String feeHeadCode;
    private String feeHeadName;
    private String discountConfigType;
    private BigDecimal grossAmount;
    private BigDecimal discountAmount;
    private BigDecimal netAmount;

    public String getLineItemType() { return lineItemType; }
    public void setLineItemType(String lineItemType) { this.lineItemType = lineItemType; }

    public String getFeeHeadCode() { return feeHeadCode; }
    public void setFeeHeadCode(String feeHeadCode) { this.feeHeadCode = feeHeadCode; }

    public String getFeeHeadName() { return feeHeadName; }
    public void setFeeHeadName(String feeHeadName) { this.feeHeadName = feeHeadName; }

    public String getDiscountConfigType() { return discountConfigType; }
    public void setDiscountConfigType(String discountConfigType) { this.discountConfigType = discountConfigType; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
}
