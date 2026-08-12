package com.indraacademy.ias_management.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The authoritative, immutable per-charge breakdown of one StudentFees row's
 * {@code baseAmountDue}/{@code busFeeDue} — "August 2026: Tuition Fee ₹2,000, Laboratory
 * Fee ₹600, Bus Fee ₹800, Scholarship Discount −₹400". Written exactly once, in the same
 * pass as the parent row's snapshot (FeeCalculationService.computeMonthSnapshot), from the
 * SAME gross/discount figures that produce baseAmountDue/discountAmount/busFeeDue — never a
 * second, independent calculation. Never updated or deleted afterward by ordinary
 * generation: a later edit to FeeHead/FeeStructureRule/StudentFeeConfig/bus fee structure
 * must not change what an already-generated bill says it charged. Every field that could
 * otherwise drift if looked up live later (name, frequency, discount reason) is copied in
 * at write time instead.
 * <p>
 * Purely a read-side audit/display enrichment — never consulted by payment allocation,
 * refund reversal, or "is this month paid" logic, all of which continue to use
 * StudentFees.baseAmountDue/busFeeDue/amountPaid/paid exactly as before. Money movement is
 * unaffected by this table's existence.
 * <p>
 * <b>The one controlled exception</b>: {@code StudentFeesRecalculationService} (Phase 5A)
 * may set a row's {@code supersededAt} — never any other field, and never a hard delete —
 * when an admin explicitly recalculates the parent StudentFees row's snapshot. The
 * superseded row still reads exactly as it always did (an immutable historical fact); only
 * its "is this currently the active line item" status changes. Every other reader must
 * filter to {@code supersededAt IS NULL} to see the current, authoritative set — see
 * {@code StudentFeesLineItemRepository.findByStudentFeesIdAndSupersededAtIsNullOrderById}.
 */
@Entity
@Table(name = "student_fees_line_item")
@Data
public class StudentFeesLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_fees_id", nullable = false)
    private Long studentFeesId;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    /** Academic session label, e.g. "2025-2026" — matches StudentFees.year. */
    @Column(name = "session", nullable = false)
    private String session;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_item_type", nullable = false, length = 20)
    private LineItemType lineItemType;

    /** Null for a BUS line item — bus fee has no FeeHead. */
    @Column(name = "fee_head_id")
    private Long feeHeadId;

    /** Snapshot of FeeHead.code at generation time. Null for BUS. */
    @Column(name = "fee_head_code", length = 30)
    private String feeHeadCode;

    /** Snapshot of FeeHead.name (or a fixed "Bus Fee" label) at generation time — what the
     * bill actually says, immune to a later FeeHead rename or deletion. */
    @Column(name = "fee_head_name", length = 200)
    private String feeHeadName;

    /** Snapshot of FeeHead.frequency.name() at generation time. Null for BUS. */
    @Column(name = "frequency", length = 20)
    private String frequency;

    /** All amounts in paise, matching FeeStructureRule.amount / PaymentStudentFeesAllocation's
     * own convention — exact integer arithmetic, no rounding drift possible. */
    @Column(name = "gross_amount_paise", nullable = false)
    private long grossAmountPaise;

    @Column(name = "discount_amount_paise", nullable = false)
    private long discountAmountPaise;

    @Column(name = "net_amount_paise", nullable = false)
    private long netAmountPaise;

    /** Snapshot of the StudentFeeConfig.configType that produced discountAmountPaise, if any
     * (WAIVER / DISCOUNT_PERCENT / DISCOUNT_FIXED / CUSTOM_AMOUNT) — explains WHY, not just
     * how much, so "original charge → discount/waiver → final charge" is fully reconstructable
     * from this one row. Null when there was no discount. */
    @Column(name = "discount_config_type", length = 30)
    private String discountConfigType;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** NULL for the current, authoritative line item. Set once, to the recalculation
     * timestamp, when StudentFeesRecalculationService supersedes this row with a freshly
     * computed replacement — never cleared afterward, never set for any other reason. */
    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
