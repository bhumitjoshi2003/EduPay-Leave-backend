package com.indraacademy.ias_management.entity;

/**
 * Distinguishes WHY a StudentFees row's snapshotted amount is what it is — required so a
 * legitimate ₹0 (a full waiver, an off-schedule month for a QUARTERLY/SEMI_ANNUAL/ANNUAL
 * head, an OPT_OUT) is never confused with "we couldn't actually calculate this." Consumers
 * reading an already-generated row (payment, reminders, checkout) must treat only COMPUTED
 * as trustworthy; NO_ACTIVE_RULE_THIS_MONTH means the stored amount (typically 0) should not
 * be relied on without a live fallback or explicit admin recalculation.
 *
 * COMPUTED: rules were found and evaluated; baseAmountDue may legitimately be zero.
 * NO_ACTIVE_RULE_THIS_MONTH: no FeeStructureRule was active as of this specific asOfDate — a
 *   configuration gap for this particular month, distinct from a confidently-computed zero.
 */
public enum SnapshotStatus {
    COMPUTED,
    NO_ACTIVE_RULE_THIS_MONTH
}
