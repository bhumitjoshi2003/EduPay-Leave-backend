-- Line-item phase: the authoritative, immutable per-fee-head (and bus) breakdown behind
-- each StudentFees row's baseAmountDue/busFeeDue — "what was this month's charge actually
-- made of." Written once, alongside the parent row, by the exact same calculation pass that
-- already produces baseAmountDue/discountAmount/busFeeDue (FeeCalculationService.
-- computeMonthSnapshot) — never a second, independent computation. Never updated or deleted
-- afterward; a later edit to FeeHead/FeeStructureRule/StudentFeeConfig/bus fee structure
-- must not change what an already-generated bill says it charged, which is why every
-- human-readable field (name, frequency) is copied in at write time rather than joined live.
--
-- Purely additive and read-side: nothing here is consulted by payment allocation, refund
-- reversal, or paid/unpaid logic, all of which continue to use StudentFees' own stored
-- columns exactly as before. Existing StudentFees rows get NO line items retroactively —
-- see the phase report for why a backfill is proposed, not performed, here.
CREATE TABLE IF NOT EXISTS student_fees_line_item (
    id BIGSERIAL PRIMARY KEY,
    student_fees_id BIGINT NOT NULL REFERENCES student_fees(id),
    school_id BIGINT NOT NULL,
    student_id VARCHAR(255) NOT NULL,
    session VARCHAR(50) NOT NULL,
    month INTEGER NOT NULL,
    line_item_type VARCHAR(20) NOT NULL,
    -- Intentionally NOT "ON DELETE CASCADE" or "ON DELETE SET NULL": if a FeeHead is ever
    -- deleted, the line item (and its own copied-in code/name/frequency) must survive
    -- unchanged — only the FK linkage becomes unresolvable, which is fine, since nothing
    -- reads through it; fee_head_name etc. are what a bill/report actually displays.
    fee_head_id BIGINT REFERENCES fee_head(id),
    fee_head_code VARCHAR(30),
    fee_head_name VARCHAR(200),
    frequency VARCHAR(20),
    gross_amount_paise BIGINT NOT NULL,
    discount_amount_paise BIGINT NOT NULL DEFAULT 0,
    net_amount_paise BIGINT NOT NULL,
    discount_config_type VARCHAR(30),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_sfli_gross_nonneg CHECK (gross_amount_paise >= 0),
    CONSTRAINT chk_sfli_discount_nonneg CHECK (discount_amount_paise >= 0),
    -- Per-row reconciliation: net always equals gross minus discount, enforced at the DB
    -- level so this can never drift even if a future code change forgets to keep them in
    -- sync. The cross-row invariant (SUM(net) across a StudentFees row's line items equals
    -- that row's baseAmountDue + busFeeDue) is an application-level guarantee, verified by
    -- tests, and not expressible as a single-row CHECK constraint.
    CONSTRAINT chk_sfli_net_consistent CHECK (net_amount_paise = gross_amount_paise - discount_amount_paise)
);

CREATE INDEX IF NOT EXISTS idx_sfli_student_fees_id ON student_fees_line_item(student_fees_id);
CREATE INDEX IF NOT EXISTS idx_sfli_student_session ON student_fees_line_item(school_id, student_id, session);

-- At most one line item per (StudentFees row, fee head) — a given fee head appears once in
-- a month's breakdown, never duplicated.
CREATE UNIQUE INDEX IF NOT EXISTS uq_sfli_studentfees_feehead
    ON student_fees_line_item(student_fees_id, fee_head_id) WHERE fee_head_id IS NOT NULL;

-- At most one BUS line item per StudentFees row (fee_head_id is NULL for all BUS rows, so a
-- plain unique index on (student_fees_id, fee_head_id) would not catch duplicates here —
-- NULLs are mutually distinct for uniqueness purposes).
CREATE UNIQUE INDEX IF NOT EXISTS uq_sfli_studentfees_bus
    ON student_fees_line_item(student_fees_id) WHERE line_item_type = 'BUS';
