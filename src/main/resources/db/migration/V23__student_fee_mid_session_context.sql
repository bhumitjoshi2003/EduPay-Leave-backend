-- Persist the policy context used to generate a fee row so later safe recalculation can
-- reproduce the same mid-session result instead of accidentally restoring a full month.
ALTER TABLE student_fees
    ADD COLUMN billing_effective_date DATE,
    ADD COLUMN mid_session_fee_policy VARCHAR(30),
    ADD COLUMN proration_factor NUMERIC(10,8);

ALTER TABLE student_fees
    ADD CONSTRAINT ck_student_fees_mid_session_policy
    CHECK (mid_session_fee_policy IS NULL OR mid_session_fee_policy IN
           ('FROM_EFFECTIVE_MONTH', 'NEXT_MONTH', 'PRORATE_JOINING_MONTH'));
