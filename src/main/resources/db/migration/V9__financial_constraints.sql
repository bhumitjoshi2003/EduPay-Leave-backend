-- Integrity-hardening phase, part 2: DB-level protections for the financial tables that
-- were previously enforced only by application code (or not enforced at all). Each
-- constraint below is checked against the actual business model before being added —
-- see the phase report for the ones considered and deliberately NOT added (e.g. a
-- cross-row "reversal cannot exceed its allocation" check, which is already guaranteed by
-- the payment-row pessimistic lock serializing all refund activity against a given
-- payment's allocations, and isn't expressible as a single-row CHECK constraint anyway).

-- payment_student_fees_allocation, allocation_refund, and refund are all new tables
-- introduced this session (V31/V32) — every row in them was written by code that already
-- guarantees a positive amount (RefundRequest.amount has @Min(1); allocation/reversal
-- amounts are always a Math.min(...) of two already-positive quantities). No pre-existing
-- data risk, safe to add unconditionally.
ALTER TABLE payment_student_fees_allocation ADD CONSTRAINT chk_allocation_amount_positive CHECK (amount_paise > 0);
ALTER TABLE allocation_refund ADD CONSTRAINT chk_allocation_refund_amount_positive CHECK (amount_paise > 0);
ALTER TABLE refund ADD CONSTRAINT chk_refund_amount_positive CHECK (amount_paise > 0);

-- payment.payment_id (the Razorpay payment id, or a MANUAL_<uuid> string for manual
-- payments) has only ever been checked for uniqueness at the application level
-- (PaymentRepository.existsByPaymentId in RazorpayService.verifyPayment) — a real TOCTOU
-- race between two near-simultaneous verify calls for the same Razorpay payment id (e.g. a
-- duplicate webhook alongside a client-triggered verify) could both pass that check before
-- either commits, producing two Payment rows — and now, two independent sets of allocations
-- — for one real-world payment.
--
-- payment is a pre-existing, Hibernate-auto-ddl-managed table (not Flyway-managed — see the
-- original audit's D2 finding), so unlike the three tables above this DOES carry pre-existing
-- data risk. Before this migration is deployed against a real database, run:
--
--   SELECT payment_id, COUNT(*) FROM payment GROUP BY payment_id HAVING COUNT(*) > 1;
--
-- payment_id has been treated as a de-facto unique idempotency key everywhere in the
-- application since long before this phase, so duplicates are not expected — but this
-- cannot be verified without access to the actual target database. If the query above
-- returns rows, this migration will fail and each group needs the same manual review
-- described in V33 (never auto-merged, since payment_student_fees_allocation now has FKs to
-- these rows too).
ALTER TABLE payment ADD CONSTRAINT uq_payment_payment_id UNIQUE (payment_id);

-- manual_reference_number (added in V30, this session) was already checked for per-school
-- uniqueness at the application level (PaymentRepository.
-- existsByManualReferenceNumberAndSchoolId in StudentFeesService.recordManualPayment) before
-- a manual payment is recorded — this closes the same class of TOCTOU race for that check.
-- Lower risk than payment_id above (the column and its app-level check were both introduced
-- together this session), but run this first if in doubt:
--
--   SELECT school_id, manual_reference_number, COUNT(*) FROM payment
--   WHERE manual_reference_number IS NOT NULL
--   GROUP BY school_id, manual_reference_number HAVING COUNT(*) > 1;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_school_manual_reference
    ON payment (school_id, manual_reference_number)
    WHERE manual_reference_number IS NOT NULL;
