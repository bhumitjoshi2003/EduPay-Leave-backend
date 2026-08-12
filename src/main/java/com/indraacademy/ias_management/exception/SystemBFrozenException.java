package com.indraacademy.ias_management.exception;

/**
 * Thrown by any Invoice/FeePayment/CreditNote write path that creates or mutates financial
 * state. StudentFees → Payment → PaymentStudentFeesAllocation → Refund is the sole
 * canonical financial system as of the architecture decision this exception enforces —
 * see the "Fee & Payment Systems: Architecture Audit" this phase's freeze implements.
 * <p>
 * This is deliberately NOT a runtime-configurable flag (no application property, no
 * per-school toggle): it is a standing code-level decision, not an operational switch.
 * Reversing it is a future, deliberate code change (removing these calls), not a config
 * flip. Read paths (GET endpoints, DTO projections) are entirely unaffected — existing
 * invoices, payments, and credit notes remain fully readable.
 */
public class SystemBFrozenException extends RuntimeException {

    public SystemBFrozenException(String action) {
        super("The Invoice/FeePayment system no longer accepts new financial writes (" + action
                + "). StudentFees / Payment / manual-payment is the canonical fee system — "
                + "record this through the primary Fee Payment flow instead. Existing invoices, "
                + "payments, and credit notes remain visible and unaffected.");
    }
}
