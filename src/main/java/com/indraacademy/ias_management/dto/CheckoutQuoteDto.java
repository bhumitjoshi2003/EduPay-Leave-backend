package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Backend-authoritative checkout quote for a set of selected academic months: school fee
 * due (from each month's stable StudentFees snapshot) + late fee (computed live, per the
 * existing tiered business rule) + platform/payment fee (payment-time only, never part of
 * the original school debt) = totalAmount. Angular displays these values as-is; it must
 * never recompute any of them itself.
 *
 * unresolvedMonths lists any requested month whose fee could not be confidently determined
 * (no trustworthy snapshot and no dynamic rule to fall back on) — schoolFeeDue/totalAmount
 * only cover the RESOLVED months; a non-empty unresolvedMonths means the quote is partial
 * and the caller must not treat it as the full amount owed.
 */
public class CheckoutQuoteDto {
    private String studentId;
    private String session;
    private List<Integer> months;
    private BigDecimal schoolFeeDue;
    private BigDecimal lateFee;
    private BigDecimal platformFee;
    private BigDecimal totalAmount;
    private List<Integer> unresolvedMonths;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public List<Integer> getMonths() { return months; }
    public void setMonths(List<Integer> months) { this.months = months; }

    public BigDecimal getSchoolFeeDue() { return schoolFeeDue; }
    public void setSchoolFeeDue(BigDecimal schoolFeeDue) { this.schoolFeeDue = schoolFeeDue; }

    public BigDecimal getLateFee() { return lateFee; }
    public void setLateFee(BigDecimal lateFee) { this.lateFee = lateFee; }

    public BigDecimal getPlatformFee() { return platformFee; }
    public void setPlatformFee(BigDecimal platformFee) { this.platformFee = platformFee; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public List<Integer> getUnresolvedMonths() { return unresolvedMonths; }
    public void setUnresolvedMonths(List<Integer> unresolvedMonths) { this.unresolvedMonths = unresolvedMonths; }
}
