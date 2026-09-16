package com.indraacademy.ias_management.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
    /** Parent-facing values are paise-native and unambiguous. */
    private long schoolFeePaise;
    private long onlineConvenienceFeePaise;
    private long totalPayablePaise;
    private String currency = "INR";

    /** schoolLiabilityPrincipalPaise is an internal accounting input, never serialized — the
     * parent-facing aggregate is schoolFeePaise above. additionalChargesPaise/lateFeePaise ARE
     * serialized: unlike the gateway/Edunexify component split (which must never reach a
     * parent), these are pre-existing, legitimate itemized breakdown lines the Fees UI already
     * shows ("Late Fee Applied", "Unapplied Leave Charge") — hiding them would be an unrelated
     * UX regression, not something this refactor's locked design asked for. */
    @JsonIgnore private long schoolLiabilityPrincipalPaise;
    private long additionalChargesPaise;
    private long lateFeePaise;
    private List<Integer> unresolvedMonths;

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getSession() { return session; }
    public void setSession(String session) { this.session = session; }

    public List<Integer> getMonths() { return months; }
    public void setMonths(List<Integer> months) { this.months = months; }

    public long getSchoolFeePaise() { return schoolFeePaise; }
    public void setSchoolFeePaise(long value) { this.schoolFeePaise = value; }
    public long getOnlineConvenienceFeePaise() { return onlineConvenienceFeePaise; }
    public void setOnlineConvenienceFeePaise(long value) { this.onlineConvenienceFeePaise = value; }
    public long getTotalPayablePaise() { return totalPayablePaise; }
    public void setTotalPayablePaise(long value) { this.totalPayablePaise = value; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public long getSchoolLiabilityPrincipalPaise() { return schoolLiabilityPrincipalPaise; }
    public void setSchoolLiabilityPrincipalPaise(long value) { this.schoolLiabilityPrincipalPaise = value; }
    public long getAdditionalChargesPaise() { return additionalChargesPaise; }
    public void setAdditionalChargesPaise(long value) { this.additionalChargesPaise = value; }
    public long getLateFeePaise() { return lateFeePaise; }
    public void setLateFeePaise(long value) { this.lateFeePaise = value; }

    public List<Integer> getUnresolvedMonths() { return unresolvedMonths; }
    public void setUnresolvedMonths(List<Integer> unresolvedMonths) { this.unresolvedMonths = unresolvedMonths; }
}
