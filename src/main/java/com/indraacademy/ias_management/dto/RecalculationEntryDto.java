package com.indraacademy.ias_management.dto;

import java.math.BigDecimal;

/**
 * One month's result from either the Preview or Apply recalculation endpoint — the same
 * shape serves both, since both answer the same question ("what would/did this row's
 * amounts become"). For Preview, {@code ok} means "eligible to recalculate" and nothing has
 * been written. For Apply, {@code ok} means "successfully recalculated and persisted."
 * {@code message} carries the ineligibility/rejection reason when ok is false — never
 * populated alongside a true ok.
 * <p>
 * new* fields are null when ok is false: an ineligible/rejected row's amounts are never
 * computed for display (or, for Preview, are simply not meaningful to show next to a
 * rejection). old* fields are always populated when the row was found at all, so the
 * caller can show "here's what it currently says" even for a rejected row.
 * <p>
 * totalDue = baseAmountDue + busFeeDue in both old and new (baseAmountDue is already net of
 * discount — see FeeCalculationService.resolveSchoolFeeDue's javadoc; never subtract
 * discountAmount from it again here).
 */
public class RecalculationEntryDto {
    private Integer month;
    private boolean ok;
    private String message;

    private BigDecimal oldBaseAmountDue;
    private BigDecimal oldBusFeeDue;
    private BigDecimal oldDiscountAmount;
    private BigDecimal oldTotalDue;

    private BigDecimal newBaseAmountDue;
    private BigDecimal newBusFeeDue;
    private BigDecimal newDiscountAmount;
    private BigDecimal newTotalDue;

    public Integer getMonth() { return month; }
    public void setMonth(Integer month) { this.month = month; }

    public boolean isOk() { return ok; }
    public void setOk(boolean ok) { this.ok = ok; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public BigDecimal getOldBaseAmountDue() { return oldBaseAmountDue; }
    public void setOldBaseAmountDue(BigDecimal oldBaseAmountDue) { this.oldBaseAmountDue = oldBaseAmountDue; }

    public BigDecimal getOldBusFeeDue() { return oldBusFeeDue; }
    public void setOldBusFeeDue(BigDecimal oldBusFeeDue) { this.oldBusFeeDue = oldBusFeeDue; }

    public BigDecimal getOldDiscountAmount() { return oldDiscountAmount; }
    public void setOldDiscountAmount(BigDecimal oldDiscountAmount) { this.oldDiscountAmount = oldDiscountAmount; }

    public BigDecimal getOldTotalDue() { return oldTotalDue; }
    public void setOldTotalDue(BigDecimal oldTotalDue) { this.oldTotalDue = oldTotalDue; }

    public BigDecimal getNewBaseAmountDue() { return newBaseAmountDue; }
    public void setNewBaseAmountDue(BigDecimal newBaseAmountDue) { this.newBaseAmountDue = newBaseAmountDue; }

    public BigDecimal getNewBusFeeDue() { return newBusFeeDue; }
    public void setNewBusFeeDue(BigDecimal newBusFeeDue) { this.newBusFeeDue = newBusFeeDue; }

    public BigDecimal getNewDiscountAmount() { return newDiscountAmount; }
    public void setNewDiscountAmount(BigDecimal newDiscountAmount) { this.newDiscountAmount = newDiscountAmount; }

    public BigDecimal getNewTotalDue() { return newTotalDue; }
    public void setNewTotalDue(BigDecimal newTotalDue) { this.newTotalDue = newTotalDue; }
}
