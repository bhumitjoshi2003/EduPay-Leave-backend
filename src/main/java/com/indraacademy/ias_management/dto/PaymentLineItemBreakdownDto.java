package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backend-authoritative fee-head breakdown for a single Payment, backing the PDF receipt and
 * the "Payment Details" screen (Phase 4 — receipts & payment-detail read-path alignment).
 * lineItems is the aggregate, across every StudentFees month this payment's
 * PaymentStudentFeesAllocation rows reference, of each month's real StudentFeesLineItem
 * rows — grouped by fee head so "Tuition Fee" across two paid months shows as one combined
 * row. Never fabricated: lineItemBreakdownAvailable is true only when EVERY covered month
 * has real line-item data; otherwise lineItems is empty and the caller must show
 * totalSchoolFeeDue (the trusted total, from the same resolveSchoolFeeDue the checkout quote
 * and month-breakdown endpoints use) with a "detailed breakdown unavailable" fallback —
 * never a partial or invented composition. totalSchoolFeeDue itself is null only when even
 * the total is genuinely unknown (e.g. a payment with no allocation rows at all, predating
 * the allocation ledger) — never conflated with zero owed.
 *
 * Deliberately excludes lateFee/platformFee/amount/amountPaid/refund data — those remain
 * sourced directly from the Payment entity (PaymentResponseDTO / the receipt's own fields),
 * unaffected by and independent of this breakdown.
 */
public class PaymentLineItemBreakdownDto {
    private String paymentId;
    private List<FeeLineItemDto> lineItems;
    private boolean lineItemBreakdownAvailable;
    private BigDecimal totalSchoolFeeDue;

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public List<FeeLineItemDto> getLineItems() { return lineItems; }
    public void setLineItems(List<FeeLineItemDto> lineItems) { this.lineItems = lineItems; }

    public boolean isLineItemBreakdownAvailable() { return lineItemBreakdownAvailable; }
    public void setLineItemBreakdownAvailable(boolean lineItemBreakdownAvailable) { this.lineItemBreakdownAvailable = lineItemBreakdownAvailable; }

    public BigDecimal getTotalSchoolFeeDue() { return totalSchoolFeeDue; }
    public void setTotalSchoolFeeDue(BigDecimal totalSchoolFeeDue) { this.totalSchoolFeeDue = totalSchoolFeeDue; }
}
