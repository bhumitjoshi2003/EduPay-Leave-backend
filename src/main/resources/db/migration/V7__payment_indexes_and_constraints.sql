-- ============================================================================
-- V7: Payment indexes and constraints for data integrity and query performance
-- ============================================================================

-- Prevent duplicate payment recordings (idempotency for Razorpay callbacks)
ALTER TABLE fee_payment ADD CONSTRAINT uk_fee_payment_razorpay_id
    UNIQUE (school_id, razorpay_payment_id);

-- Performance indexes for payment queries
CREATE INDEX IF NOT EXISTS idx_fee_payment_school_razorpay
    ON fee_payment(school_id, razorpay_payment_id);

CREATE INDEX IF NOT EXISTS idx_fee_payment_student_date
    ON fee_payment(school_id, student_id, payment_date);

-- Performance indexes for invoice queries
CREATE INDEX IF NOT EXISTS idx_invoice_student_month
    ON invoice(school_id, student_id, billing_month);

-- Composite index for payment allocation lookups
CREATE INDEX IF NOT EXISTS idx_payment_alloc_combined
    ON payment_allocation(fee_payment_id, invoice_id);
