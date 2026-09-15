-- Financial AcademicSession Authority, Phase D2 — fee-workflow schema/entity foundation only.
--
-- student_fee_assignment and fee_generation_batch are the two tables that gate the active,
-- assignment-driven fee-generation workflow (FeeWorkflowService) — they currently identify
-- "which academic session" purely by a raw VARCHAR label (academic_session), never validated
-- against a real AcademicSession row anywhere except deep inside per-student generation (see
-- the Phase D1 audit). This migration adds a nullable, tenant-safe authoritative reference
-- alongside — never in place of — that existing string, which remains the permanent,
-- immutable historical/display snapshot exactly as it is today.
--
-- Deliberately additive and inert, matching V64's own precedent exactly: no column is
-- backfilled, no column is made NOT NULL, no existing constraint/index is touched, and no
-- application code in this phase reads or writes academic_session_id. In particular,
-- uq_student_fee_assignment, ck_student_fee_assignment_status,
-- idx_student_fee_assignment_school_session_status, fk_fee_generation_retry_batch, and
-- idx_fee_generation_batch_school_session are all left completely untouched.
--
-- Composite tenant-safe FK, matching the exact pattern already established by
-- V53__student_enrollment_foundation.sql's fk_student_enrollment_session and reused by
-- V64__financial_academic_session_authority_foundation.sql for the core financial ledger:
-- academic_session already carries a UNIQUE(school_id, id) constraint
-- (uq_academic_session_school_id, added in V53), which is what makes
-- FOREIGN KEY (school_id, academic_session_id) REFERENCES academic_session(school_id, id)
-- possible — a plain single-column FK to academic_session.id would be referentially valid but
-- would not by itself prevent a row from pointing at a session belonging to a DIFFERENT
-- school; the composite form makes that a hard database-level impossibility rather than an
-- application-discipline assumption.
--
-- ON DELETE RESTRICT (not CASCADE, not SET NULL), for the same reason V53/V64 chose it: a
-- school cannot delete an AcademicSession that a workflow/audit record still references, and
-- that record's own session reference must never silently disappear as a side effect of an
-- unrelated session-management action.

ALTER TABLE student_fee_assignment
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_student_fee_assignment_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

ALTER TABLE fee_generation_batch
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_fee_generation_batch_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

-- No index is added on academic_session_id on either table in this phase: nothing in D2 reads
-- or writes it yet (that begins in a later phase), so there is no query for an index to serve.
-- Adding one speculatively would be pure write-amplification with no benefit until a real
-- query need materializes — reconsidered explicitly once that happens, exactly as V64 deferred
-- the same decision for the core financial ledger.
