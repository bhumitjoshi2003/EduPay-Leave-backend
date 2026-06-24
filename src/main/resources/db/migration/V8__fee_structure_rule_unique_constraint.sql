-- V8: Add unique constraint on fee_structure_rule to prevent duplicate fee heads
--     per school + session + class combination.
--
-- Before adding the constraint, remove any pre-existing duplicates by keeping
-- only the most recently created rule for each (school_id, academic_session_id,
-- class_name, fee_head_id) group. This ensures a safe migration on existing data.

-- Step 1: Delete duplicate rows, keeping the one with the highest id per group
DELETE FROM fee_structure_rule
WHERE id NOT IN (
    SELECT MAX(id)
    FROM fee_structure_rule
    GROUP BY school_id, academic_session_id, class_name, fee_head_id
);

-- Step 2: Add the unique constraint
ALTER TABLE fee_structure_rule
    ADD CONSTRAINT uq_fsr_school_session_class_feehead
        UNIQUE (school_id, academic_session_id, class_name, fee_head_id);
