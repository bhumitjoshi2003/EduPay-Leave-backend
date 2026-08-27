-- Collapse StudentFeeAssignmentStatus from 8 values to 6. PENDING_CONFIGURATION was never
-- written by any code path; GENERATING was always overwritten to a final status before its
-- owning transaction committed, so it was never an observable committed value under the
-- current synchronous per-student generation design. Neither should exist in live data, but
-- backfill defensively rather than risk the constraint swap failing against unexpected rows.
UPDATE student_fee_assignment
SET status = 'NOT_ASSIGNED'
WHERE status = 'PENDING_CONFIGURATION';

UPDATE student_fee_assignment
SET status = 'GENERATION_FAILED'
WHERE status = 'GENERATING';

ALTER TABLE student_fee_assignment DROP CONSTRAINT ck_student_fee_assignment_status;
ALTER TABLE student_fee_assignment ADD CONSTRAINT ck_student_fee_assignment_status
    CHECK (status IN ('NOT_ASSIGNED','READY','GENERATED','PARTIALLY_GENERATED','EXCLUDED','GENERATION_FAILED'));
