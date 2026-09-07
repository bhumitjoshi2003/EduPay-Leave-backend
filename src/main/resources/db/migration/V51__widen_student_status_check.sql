-- Production correctness fix, not part of the academic-session/promotion redesign.
--
-- student_status_check (added by the V1 baseline re-dump) only permits
-- 'ACTIVE', 'INACTIVE', 'UPCOMING' — but StudentStatus.java has defined
-- GRADUATED, TRANSFERRED, and WITHDRAWN for some time, and two live write
-- paths already try to persist them: StudentService.exitStudent (the generic
-- graduate/transfer/withdraw workflow) and StudentPromotionService's PASS_OUT
-- promotion-batch decision. Both would currently fail with a Postgres
-- CheckViolation the moment either path is exercised — this has evidently
-- never happened against this database (every student row here is ACTIVE).
--
-- Verified before writing this migration: every student.status write in the
-- codebase (StudentService, StudentPromotionService, StudentRepository's bulk
-- updateStatusUpcoming/updateStatusActive queries) only ever targets one of
-- the six StudentStatus enum values, so widening the constraint to match the
-- enum exactly is the complete, correct fix — no application code changes
-- are needed alongside it.
ALTER TABLE student DROP CONSTRAINT student_status_check;

ALTER TABLE student ADD CONSTRAINT student_status_check
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'UPCOMING', 'GRADUATED', 'TRANSFERRED', 'WITHDRAWN'));
