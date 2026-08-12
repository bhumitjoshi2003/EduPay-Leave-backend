-- Refund correctness phase: the current model has no way to represent a refund beyond a
-- blunt Payment.status="refunded" string flip — no record of how much has been refunded so
-- far, no link back to which months were reversed, and nothing to detect a duplicate refund
-- request or support genuine partial refunds. This table is the minimal addition needed to
-- represent that state: one row per successfully-processed refund event (a payment may have
-- several, for successive partial refunds), append-only, never mutated after insert.
CREATE TABLE IF NOT EXISTS refund (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    school_id BIGINT NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    session VARCHAR(50) NOT NULL,
    -- 12-char '0'/'1' bitmask of the months THIS refund event actually reversed (a subset of
    -- the original payment's month selection) — not necessarily every month the payment
    -- covered, since a partial refund may only reach some of them.
    months_refunded VARCHAR(12) NOT NULL,
    amount_paise BIGINT NOT NULL,
    reason VARCHAR(500),
    -- Razorpay's refund id for a gateway refund; NULL for a manual (cash/cheque/etc) payment,
    -- which has no gateway leg to refund.
    provider_refund_id VARCHAR(100),
    status VARCHAR(30) NOT NULL,
    -- Optional client-supplied idempotency token so a retried/duplicated refund request for
    -- the same payment is rejected rather than double-processed.
    idempotency_key VARCHAR(100),
    initiated_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refund_payment_id ON refund(payment_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_payment_idempotency
    ON refund(payment_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
