package com.indraacademy.ias_management.entity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "student_fees", indexes = {
    @Index(name = "idx_student_fees_student_year_month", columnList = "student_id, year, month")
})
@Data
public class StudentFees {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "student_id")
    private String studentId;

    @Column(name = "class_name")
    private String className;

    @Column(name = "class_id")
    private Long classId;

    @Column(name = "month")
    private Integer month;

    /** Derived/cache state, never an independent source of truth — computed exclusively from
     * PaymentStudentFeesAllocation/AllocationRefund (see StudentFeesService.markFeesAsPaid
     * and PaymentService.recomputeStudentFeesNetState, the only two places this is written).
     * Safe to read anywhere for display/filtering; never write it directly outside those two
     * methods — StudentFeesController.updateStudentFees deliberately cannot set it. */
    @Column(name = "paid")
    private Boolean paid;

    @Column(name = "takes_bus")
    private Boolean takesBus;

    @Column(name = "year")
    private String year;

    @Column(name = "distance")
    private Double distance;

    /** Derived from the allocation ledger: true iff this row's net-paid amount includes
     * money from at least one payment with a manualPaymentMode set (cash/cheque/UPI/bank
     * transfer), computed the same way in both markFeesAsPaid and
     * PaymentService.recomputeStudentFeesNetState. For a row funded by a MIX of manual and
     * gateway payments this only indicates "some manual money is present," not "the most
     * recent payment was manual" — there is no single boolean that can fully represent a
     * mixed-funding row; consult payment_student_fees_allocation joined to payment for the
     * exact breakdown if that distinction matters. */
    @Column(name = "manually_paid")
    private Boolean manuallyPaid;

    /** Derived from the allocation ledger: the net amount (after any reversals) specifically
     * contributed by manual-mode payments to this row — not the row's full amountPaid. Zero
     * whenever manuallyPaid is false. See the manuallyPaid Javadoc for the mixed-funding
     * caveat. */
    @Column(name = "manual_payment_received")
    private BigDecimal manualPaymentReceived;

    /** Derived/cache state — the row's net allocation across every payment that ever funded
     * it, minus any refunds. See the {@code paid} field's Javadoc; the same rule applies. */
    @Column(name = "amount_paid")
    private BigDecimal amountPaid;

    @Column(name = "base_amount_due", precision = 15, scale = 2)
    private BigDecimal baseAmountDue;

    @Column(name = "bus_fee_due", precision = 15, scale = 2)
    private BigDecimal busFeeDue;

    @Column(name = "discount_amount", precision = 15, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "amount_computed_at")
    private LocalDateTime amountComputedAt;

    @Column(name = "amount_rule_snapshot", columnDefinition = "TEXT")
    private String amountRuleSnapshot;

    /** Native, strongly-typed twin of amount_rule_snapshot's embedded "status" field — added
     * so consumers (payment, reminders, checkout) can check trustworthiness in one indexed
     * column read instead of parsing the JSON blob on every access. See SnapshotStatus. */
    @Enumerated(EnumType.STRING)
    @Column(name = "snapshot_status", length = 30)
    private SnapshotStatus snapshotStatus;

    /** Not persisted — populated per-request by StudentFeesService.getStudentFees from the
     * allocation ledger (payment_student_fees_allocation joined to payment), the same source
     * manuallyPaid/manualPaymentReceived are derived from. One of: a manualPaymentMode value
     * (CASH/CHEQUE/BANK_TRANSFER/UPI/OTHER), "RAZORPAY", "MIXED" (net-positive contributions
     * from more than one distinct source), or null (nothing net-allocated yet). This is what
     * lets the UI show the true funding source instead of the coarse manuallyPaid boolean —
     * see the manuallyPaid Javadoc's mixed-funding caveat above. */
    @Transient
    private String paymentProvenance;

    public StudentFees() {
        this.manuallyPaid = false;
        this.manualPaymentReceived = null;
        this.amountPaid = null;
    }

    public StudentFees(String studentId, String className, Integer month, Boolean paid, Boolean takesBus, String year, Double distance, BigDecimal manualPaymentReceived, BigDecimal amountPaid) {
        this.studentId = studentId;
        this.className = className;
        this.month = month;
        this.paid = paid;
        this.takesBus = takesBus;
        this.year = year;
        this.distance = distance;
        this.manuallyPaid = false;
        this.manualPaymentReceived = manualPaymentReceived;
        this.amountPaid = amountPaid;
    }

    public Long getId() {
        return id;
    }

    public String getStudentId() {
        return studentId;
    }

    public String getClassName() {
        return className;
    }

    public Integer getMonth() {
        return month;
    }

    public Boolean getPaid() {
        return paid;
    }

    public Boolean getTakesBus() {
        return takesBus;
    }

    public String getYear() {
        return year;
    }

    public Double getDistance() {
        return distance;
    }

    public Boolean getManuallyPaid() {
        return manuallyPaid;
    }

    public BigDecimal getManualPaymentReceived() {
        return manualPaymentReceived;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public void setDistance(Double distance) {
        this.distance = distance;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void setMonth(Integer month) {
        this.month = month;
    }

    public void setPaid(Boolean paid) {
        this.paid = paid;
    }

    public void setTakesBus(Boolean takesBus) {
        this.takesBus = takesBus;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public void setManuallyPaid(Boolean manuallyPaid) {
        this.manuallyPaid = manuallyPaid;
    }

    public void setManualPaymentReceived(BigDecimal manualPaymentReceived) {
        this.manualPaymentReceived = manualPaymentReceived;
    }

    public void setAmountPaid(BigDecimal amountPaid) {
        this.amountPaid = amountPaid;
    }

    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }

    public BigDecimal getBaseAmountDue() { return baseAmountDue; }
    public void setBaseAmountDue(BigDecimal baseAmountDue) { this.baseAmountDue = baseAmountDue; }

    public BigDecimal getBusFeeDue() { return busFeeDue; }
    public void setBusFeeDue(BigDecimal busFeeDue) { this.busFeeDue = busFeeDue; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public LocalDateTime getAmountComputedAt() { return amountComputedAt; }
    public void setAmountComputedAt(LocalDateTime amountComputedAt) { this.amountComputedAt = amountComputedAt; }

    public String getAmountRuleSnapshot() { return amountRuleSnapshot; }
    public void setAmountRuleSnapshot(String amountRuleSnapshot) { this.amountRuleSnapshot = amountRuleSnapshot; }
}