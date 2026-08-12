-- Allocation-ledger phase: makes payment/refund accounting authoritative instead of derived
-- from Payment.month bitmasks and StudentFees' latest-cumulative-state fields. Two new
-- append-only tables:
--
--   payment_student_fees_allocation — one row per (payment, StudentFees row) pair, recording
--   exactly how much of that payment was allocated to that specific academic month. A
--   payment covering 3 months produces up to 3 rows here; SUM(amount_paise) across a
--   payment's rows always equals that payment's amountPaid.
--
--   allocation_refund — one row per (refund event, allocation) pair, recording exactly how
--   much of a given allocation a given refund reversed. A partial refund against a
--   multi-month payment can reverse some allocations fully and others partially, or not at
--   all; this table is the precise record of which.
--
-- Both are populated only going forward. Existing Payment rows predating this migration have
-- no allocation rows — see the phase report for why backfilling them is not attempted.
CREATE TABLE IF NOT EXISTS payment_student_fees_allocation (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payment(id),
    student_fees_id BIGINT NOT NULL REFERENCES student_fees(id),
    school_id BIGINT NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    session VARCHAR(50) NOT NULL,
    month INTEGER NOT NULL,
    amount_paise BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_allocation_payment_studentfees UNIQUE (payment_id, student_fees_id)
);
CREATE INDEX IF NOT EXISTS idx_allocation_payment_id ON payment_student_fees_allocation(payment_id);
CREATE INDEX IF NOT EXISTS idx_allocation_student_fees_id ON payment_student_fees_allocation(student_fees_id);

CREATE TABLE IF NOT EXISTS allocation_refund (
    id BIGSERIAL PRIMARY KEY,
    allocation_id BIGINT NOT NULL REFERENCES payment_student_fees_allocation(id),
    refund_id BIGINT NOT NULL REFERENCES refund(id),
    student_fees_id BIGINT NOT NULL,
    amount_paise BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_allocation_refund_allocation_id ON allocation_refund(allocation_id);
CREATE INDEX IF NOT EXISTS idx_allocation_refund_refund_id ON allocation_refund(refund_id);
CREATE INDEX IF NOT EXISTS idx_allocation_refund_student_fees_id ON allocation_refund(student_fees_id);

-- Distinguishes a refund reversed via the exact allocation ledger (the normal case, going
-- forward) from one that had to fall back to the old oldest-month-first approximation
-- against Payment.month because the payment being refunded predates this migration and has
-- no allocation rows at all. Queryable so these can be reviewed/reconciled separately.
ALTER TABLE refund ADD COLUMN IF NOT EXISTS legacy_approximation BOOLEAN NOT NULL DEFAULT false;

-- Retroactive FK now that refund is no longer brand-new — safe because every existing
-- refund row (added this session) already references a real payment. (Postgres has no
-- "ADD CONSTRAINT IF NOT EXISTS"; Flyway's own applied-migrations tracking is what prevents
-- this from re-running and erroring on a duplicate constraint.)
ALTER TABLE refund ADD CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment(id);
