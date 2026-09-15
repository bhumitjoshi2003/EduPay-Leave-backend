-- Financial AcademicSession Authority, Phase B1 — database/entity foundation only.
--
-- The core financial ledger (student_fees, student_fees_line_item, payment_order, payment,
-- payment_student_fees_allocation, refund) currently identifies "which academic session" a
-- row belongs to purely by a raw VARCHAR label (year/session/academicYear), never validated
-- against a real AcademicSession row on four of these six tables (see the Phase A audit).
-- This migration adds a nullable, tenant-safe authoritative reference alongside — never in
-- place of — that existing string, which remains the permanent, immutable historical/display
-- snapshot exactly as it is today.
--
-- Deliberately additive and inert: no column is backfilled, no column is made NOT NULL, no
-- existing constraint/index is touched, and no application code in this phase reads or writes
-- academic_session_id. It exists purely so a later phase can populate and eventually rely on
-- it without ever needing a second schema migration for the column itself.
--
-- Composite tenant-safe FK, matching the exact pattern already established by
-- V53__student_enrollment_foundation.sql's fk_student_enrollment_session: academic_session
-- already carries a UNIQUE(school_id, id) constraint (uq_academic_session_school_id, added in
-- that same V53 migration), which is what makes FOREIGN KEY (school_id, academic_session_id)
-- REFERENCES academic_session(school_id, id) possible — a plain single-column FK to
-- academic_session.id would be referentially valid but would not by itself prevent a row from
-- pointing at a session belonging to a DIFFERENT school; the composite form makes that a hard
-- database-level impossibility rather than an application-discipline assumption.
--
-- ON DELETE RESTRICT (not CASCADE, not SET NULL) for the same reason V53 chose it for
-- student_enrollment: a school cannot delete an AcademicSession that a real financial record
-- still references, and a financial record's own session reference must never silently
-- disappear as a side effect of an unrelated session-management action.

ALTER TABLE student_fees
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_student_fees_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

ALTER TABLE student_fees_line_item
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_student_fees_line_item_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

ALTER TABLE payment_order
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_payment_order_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_payment_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

ALTER TABLE payment_student_fees_allocation
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_payment_student_fees_allocation_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

ALTER TABLE refund
    ADD COLUMN IF NOT EXISTS academic_session_id BIGINT,
    ADD CONSTRAINT fk_refund_academic_session FOREIGN KEY (school_id, academic_session_id)
        REFERENCES academic_session (school_id, id) ON DELETE RESTRICT;

-- No index is added on academic_session_id on any of the six tables in this phase: nothing in
-- B1 reads or writes it yet (that begins in a later phase), so there is no query for an index
-- to serve. Adding one speculatively would be pure write-amplification with no benefit until
-- a real query need materializes — reconsidered explicitly once that happens.
