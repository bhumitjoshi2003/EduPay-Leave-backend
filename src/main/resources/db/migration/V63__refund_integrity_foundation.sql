-- Refund-integrity foundation phase: schema-only groundwork for the future Reserve -> Call ->
-- Finalize refund workflow (see the refund audit and Razorpay provider/API validation that
-- preceded this migration). Two independent, currently-inert additions — no existing code path
-- (PaymentService.processRefund, RazorpayService.createRefund) populates, reads, or enforces
-- either of these yet; this migration changes no runtime behavior.
--
-- 1. refund.provider_idempotency_key — will hold a server-generated UUID, persisted BEFORE the
--    Razorpay refund-creation call and sent as the X-Refund-Idempotency header (a future phase's
--    concern). Nullable, so every refund row created by today's unchanged code continues to
--    insert exactly as before. A partial unique index (the same "some rows intentionally share
--    a value" pattern V62 already used for payment.order_id) guarantees two DIFFERENT refund
--    rows can never carry the same non-null provider key once that future phase starts
--    populating it, while every row that predates it (key always NULL) is entirely unaffected.
--
-- 2. payment.refunded_amount_paise — a DB-level aggregate guard mirroring V62's own philosophy
--    of "the invariant is enforced by the database, not only by application logic." It is NOT a
--    replacement for the allocation_refund ledger, which remains the sole authority for WHICH
--    allocations were reversed and by how much — this is purely a fast, always-consistent
--    running total that can never legally exceed what was actually collected. Not populated or
--    incremented by any code yet (every row's value is 0, from DEFAULT); wiring it into
--    processRefund is explicit future work, not part of this migration.
--
--    payment.amount_paid was inspected directly in the baseline schema (V1__baseline_schema.sql)
--    before writing the constraint below: it is `integer` with NO NOT NULL constraint —
--    nullable at the database level — despite the Java entity mapping it to a primitive int
--    (which Hibernate always sends as a real value, never SQL NULL, on every insert this
--    application's own code has ever performed; but the column itself does not forbid some
--    other write path from leaving it NULL). A naive `CHECK (refunded_amount_paise <=
--    amount_paid)` would be UNSAFE: Postgres CHECK constraints treat a NULL comparison as
--    UNKNOWN, and UNKNOWN *passes* a CHECK (only a definite FALSE fails it) — so a row with a
--    NULL amount_paid would let refunded_amount_paise take ANY non-negative value with no
--    enforcement at all, silently defeating the guard for exactly the row that most needs one.
--    The constraint below closes that hole by requiring amount_paid to be non-null before
--    allowing any refunded_amount_paise, so a NULL amount_paid row fails loudly at write time
--    instead of silently bypassing the guard.
ALTER TABLE refund
    ADD COLUMN IF NOT EXISTS provider_idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_provider_idempotency_key
    ON refund (provider_idempotency_key)
    WHERE provider_idempotency_key IS NOT NULL;

-- BIGINT to match the sibling ledger tables' own amount_paise columns (refund, allocation_refund,
-- payment_student_fees_allocation are all BIGINT) rather than payment.amount_paid's narrower
-- `integer` — this column is compared against amount_paid below, which Postgres widens
-- implicitly, so the mismatch in column width is not a correctness concern.
ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS refunded_amount_paise BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment
    ADD CONSTRAINT chk_payment_refunded_amount_not_negative
        CHECK (refunded_amount_paise >= 0);

ALTER TABLE payment
    ADD CONSTRAINT chk_payment_refunded_amount_not_exceeding_paid
        CHECK (amount_paid IS NOT NULL AND refunded_amount_paise <= amount_paid);
