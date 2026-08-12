package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;

/**
 * Admin-submitted request to record a manual (cash/cheque/UPI/bank-transfer) payment.
 * Deliberately carries only what the admin actually observed — who paid, which months,
 * how much was received, and how/by what reference — never a client-computed fee
 * breakdown. StudentFeesService.recordManualPayment re-derives the authoritative amount
 * owed from each selected month's StudentFees snapshot (the same
 * FeeCalculationService.resolveSchoolFeeDue path Razorpay/reminders use) and validates
 * amountReceived against it; the request cannot force a month to be marked paid by
 * supplying an arbitrary total.
 */
public class ManualPaymentRequest {
    private String studentId;
    private String studentName;
    private String className;
    private String session;
    /** 12-char '0'/'1' bitmask, bit i = academic month i+1 — same convention used
     * everywhere else in the fee module (Payment.month, PaymentController's decoder). */
    private String monthSelectionString;
    /** What the admin says was physically received, in rupees. */
    private BigDecimal amountReceived;
    private String paymentMode;
    private String referenceNumber;
    /** Optional extra charge (e.g. a prior-dues adjustment), in rupees — added to the first
     * selected month's total, mirroring markFeesAsPaid's existing Payment.additionalCharges
     * semantics. Never used to reduce what's owed. */
    private Integer additionalCharges;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public String getMonthSelectionString() { return monthSelectionString; }
    public void setMonthSelectionString(String monthSelectionString) { this.monthSelectionString = monthSelectionString; }

    public BigDecimal getAmountReceived() { return amountReceived; }
    public void setAmountReceived(BigDecimal amountReceived) { this.amountReceived = amountReceived; }

    public String getPaymentMode() { return paymentMode; }
    public void setPaymentMode(String paymentMode) { this.paymentMode = paymentMode; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public Integer getAdditionalCharges() { return additionalCharges; }
    public void setAdditionalCharges(Integer additionalCharges) { this.additionalCharges = additionalCharges; }
}
