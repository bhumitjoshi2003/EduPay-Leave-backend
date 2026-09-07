-- Phase F3: now that new writes resolve canonical class_id/section_id and every new row carries
-- a real academic_session_id (see V54), the timetable business-key protection F1/F2 deliberately
-- deferred can finally be added correctly.
--
-- Two partial unique indexes, not one plain composite UNIQUE, for the same reason V54 used two
-- for class_teacher_responsibility: PostgreSQL treats every NULL as distinct, so a plain
-- UNIQUE(school_id, academic_session_id, class_id, section_id, day, period_number) would let
-- unlimited "ordinary" duplicate rows through for every sectionless class (section_id NULL).
--
-- Both indexes additionally require simultaneous_group IS NULL and academic_session_id IS NOT
-- NULL:
--   - simultaneous_group IS NULL restricts this to the ordinary (single-occupant) case. A
--     grouped slot's invariants (matching tag, matching start/end time, no duplicate
--     subject/teacher pair, but MULTIPLE rows legitimately sharing one slot) cannot be expressed
--     as a plain uniqueness constraint at all — that model stays in TimetableValidationService,
--     as instructed, rather than forcing a wrong-shaped DB constraint.
--   - academic_session_id IS NOT NULL means every one of PROD's 640 legacy rows (all
--     academic_session_id = NULL) is completely outside both indexes' scope — this migration
--     validates against zero of them and cannot fail or slow down because of them.
--
-- Teacher-overlap protection (a teacher can't be double-booked) remains application-level only,
-- as instructed: start_time/end_time are still VARCHAR "HH:mm", not a real time/range type, so a
-- correct EXCLUDE/GIST constraint would require a separate, larger type-change migration — out
-- of scope here rather than forcing an incorrect substitute.
CREATE UNIQUE INDEX uq_timetable_entry_slot_ungrouped_no_section
    ON timetable_entry (school_id, academic_session_id, class_id, day, period_number)
    WHERE simultaneous_group IS NULL AND section_id IS NULL AND academic_session_id IS NOT NULL;

CREATE UNIQUE INDEX uq_timetable_entry_slot_ungrouped_with_section
    ON timetable_entry (school_id, academic_session_id, class_id, section_id, day, period_number)
    WHERE simultaneous_group IS NULL AND section_id IS NOT NULL AND academic_session_id IS NOT NULL;

-- Canonical-ID enforcement: now that session-scoped writes always resolve real class_id/section_id
-- (see TimetableService), the DB should reject any future row that doesn't reference a real,
-- same-school class/section — not just trust application code. Both FKs use MATCH SIMPLE (the
-- PostgreSQL default), so a NULL class_id (every legacy row) skips the check entirely; only rows
-- that DO populate class_id are validated. Column order matches section's own composite key
-- (school_id, id, class_id), exactly as V53/V54 already established.
ALTER TABLE timetable_entry
    ADD CONSTRAINT fk_timetable_entry_class FOREIGN KEY (school_id, class_id)
    REFERENCES school_class (school_id, id) ON DELETE RESTRICT;

ALTER TABLE timetable_entry
    ADD CONSTRAINT fk_timetable_entry_section FOREIGN KEY (school_id, section_id, class_id)
    REFERENCES section (school_id, id, class_id) ON DELETE RESTRICT;
