-- Phase 5A (explicit, audited recalculation): StudentFeesLineItem rows were originally
-- designed as write-once-never-touched (V35). Recalculation needs a way to replace a row's
-- line items with a freshly computed set WITHOUT destroying the old ones — the old rows are
-- financial evidence of "what the bill used to say" and must stay queryable, not be deleted
-- or silently overwritten.
--
-- superseded_at is NULL for the current, authoritative set of line items for a StudentFees
-- row; recalculation sets it (once, to the recalculation timestamp) on the previously-active
-- rows before inserting the new set with superseded_at still NULL. A row's superseded_at is
-- never cleared once set. Every existing row keeps superseded_at NULL (nothing here changes
-- what any already-generated bill currently says).
ALTER TABLE student_fees_line_item ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMP NULL;

-- The V35 uniqueness rules ("at most one line item per fee head / at most one BUS line item
-- per StudentFees row") must now apply only within the ACTIVE set — a recalculation needs a
-- new active row for the same fee head to coexist with the now-superseded old one.
DROP INDEX IF EXISTS uq_sfli_studentfees_feehead;
DROP INDEX IF EXISTS uq_sfli_studentfees_bus;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sfli_studentfees_feehead_active
    ON student_fees_line_item(student_fees_id, fee_head_id)
    WHERE fee_head_id IS NOT NULL AND superseded_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sfli_studentfees_bus_active
    ON student_fees_line_item(student_fees_id)
    WHERE line_item_type = 'BUS' AND superseded_at IS NULL;

-- Read paths (receipt, payment breakdown, month breakdown) filter on this; index it directly
-- rather than relying on the composite indexes above catching every query shape.
CREATE INDEX IF NOT EXISTS idx_sfli_studentfees_active ON student_fees_line_item(student_fees_id) WHERE superseded_at IS NULL;
