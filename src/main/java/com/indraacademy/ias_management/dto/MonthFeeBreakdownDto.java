package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backend-authoritative per-fee-head breakdown for a single StudentFees month, backing the
 * parent-facing receipt/breakdown panel. Never fabricates a breakdown: lineItems is only
 * ever populated from real, persisted {@link com.indraacademy.ias_management.entity.StudentFeesLineItem}
 * rows (Phase 2+ generated months). A historical row generated before line items existed
 * has an empty lineItems list — lineItemBreakdownAvailable=false tells the caller to show
 * "breakdown unavailable" rather than inventing per-fee-head components, while schoolFeeDue
 * (from the same FeeCalculationService.resolveSchoolFeeDue the checkout quote uses) still
 * carries the trusted total when the row's snapshot itself is trustworthy. schoolFeeDue is
 * null only when the total itself is genuinely unknown — never conflated with "zero owed."
 *
 * SUM(lineItems.netAmount) == schoolFeeDue whenever lineItemBreakdownAvailable is true — this
 * is the exact reconciliation invariant proven by StudentFeesLineItem's DB constraints and
 * FeeCalculationService's line-item generation, now surfaced read-side.
 */
public class MonthFeeBreakdownDto {
    private String studentId;
    private String session;
    private Integer month;
    private List<FeeLineItemDto> lineItems;
    private boolean lineItemBreakdownAvailable;
    private BigDecimal schoolFeeDue;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }

    public List<FeeLineItemDto> getLineItems() { return lineItems; }
    public void setLineItems(List<FeeLineItemDto> lineItems) { this.lineItems = lineItems; }

    public boolean isLineItemBreakdownAvailable() { return lineItemBreakdownAvailable; }
    public void setLineItemBreakdownAvailable(boolean lineItemBreakdownAvailable) { this.lineItemBreakdownAvailable = lineItemBreakdownAvailable; }

    public BigDecimal getSchoolFeeDue() { return schoolFeeDue; }
    public void setSchoolFeeDue(BigDecimal schoolFeeDue) { this.schoolFeeDue = schoolFeeDue; }
}
