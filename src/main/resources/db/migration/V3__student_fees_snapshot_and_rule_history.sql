-- Fees module redesign, sub-phase 1a: give each StudentFees row a stable, auditable
-- snapshot of what was actually due for that month, computed once at generation time
-- and never silently recomputed when an admin later edits Fee Structure or Bus Structure.
--
-- base_amount_due / bus_fee_due are nullable: NULL means "never computed" (a backfill
-- gap, filled in later by an out-of-band batch job — see sub-phase 1g), a real 0.00 means
-- "computed, and nothing was actually due this month." These are different states and
-- must never be conflated (same "empty != zero" principle already applied elsewhere in
-- this module for get_fee_defaulters).
--
-- student_fees predates Flyway management (created purely via Hibernate ddl-auto, see the
-- audit's D2 finding) — IF NOT EXISTS guards against ddl-auto already having created a
-- same-named column before this migration runs.
ALTER TABLE student_fees ADD COLUMN IF NOT EXISTS base_amount_due NUMERIC(15,2);
ALTER TABLE student_fees ADD COLUMN IF NOT EXISTS bus_fee_due NUMERIC(15,2);
ALTER TABLE student_fees ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(15,2) NOT NULL DEFAULT 0;
ALTER TABLE student_fees ADD COLUMN IF NOT EXISTS amount_computed_at TIMESTAMP;
ALTER TABLE student_fees ADD COLUMN IF NOT EXISTS amount_rule_snapshot TEXT;

-- fee_structure_rule's existing unique constraint (V8) is on the bare 4-tuple
-- (school_id, academic_session_id, class_name, fee_head_id) with no date-range component —
-- it physically forbids an old (closed-out) and new (current) rule for the same fee head
-- from ever coexisting, which is exactly what the new asOf-dated snapshot/backfill/
-- recalculate logic needs to be able to do (FeeRuleService.saveRulesForClass moves from
-- hard delete-then-insert to close-out-then-insert as part of this same sub-phase).
--
-- Replace it with a partial unique index that only constrains *open-ended* rules: at most
-- one currently-open (effective_until IS NULL) rule per fee head may exist at a time, which
-- is the actual invariant that matters (prevents two "current" prices for the same fee head)
-- while allowing unlimited closed-out historical rows to coexist. A full overlap-preventing
-- exclusion constraint (requiring the btree_gist extension) is deferred to a separate, higher-
-- risk migration (see the plan's Phase 6) rather than bundled into this already-large phase.
ALTER TABLE fee_structure_rule DROP CONSTRAINT IF EXISTS uq_fsr_school_session_class_feehead;

CREATE UNIQUE INDEX IF NOT EXISTS uq_fsr_open_ended_rule
    ON fee_structure_rule (school_id, academic_session_id, class_name, fee_head_id)
    WHERE effective_until IS NULL;

-- Cross-session dedup guard for ONE_TIME fee heads: without this, a continuing student
-- would be re-charged a ONE_TIME fee (e.g. admission fee) every session, since generation
-- now charges it unconditionally in a student's first row of *each* generation pass unless
-- something remembers it was already charged. Mirrors InvoiceGenerationService's existing
-- in-memory billedFeeHeadIds pattern, but persisted so it survives across app restarts and
-- across the legacy StudentFees generation path specifically (the Invoice system's own
-- dedup already works via its own table and is unaffected by this).
CREATE TABLE IF NOT EXISTS student_one_time_fee_charged (
    id            BIGSERIAL PRIMARY KEY,
    school_id     BIGINT NOT NULL,
    student_id    VARCHAR(50) NOT NULL,
    fee_head_id   BIGINT NOT NULL,
    charged_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_sotfc_student_feehead UNIQUE (school_id, student_id, fee_head_id)
);
