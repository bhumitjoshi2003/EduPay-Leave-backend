-- The timetable is now a permissive schedule record: any number of rows may occupy the same
-- school/academic_session/class/section/day/period — including the same subject and/or the same
-- teacher. The application no longer adjudicates slot collisions; that is the ADMIN's job. This
-- migration removes the two DB-level constraints that previously enforced the old one-row-per-slot
-- rule (V55) and the now fully-removed correction-request feature's schema (V57).
--
-- V57's `revision` column on timetable_entry is KEPT — optimistic locking remains useful for
-- ordinary teacher/admin concurrent edits, independent of the correction-request feature.
--
-- `timetable_entry.simultaneous_group` is intentionally NOT dropped here. It is no longer written
-- or read by any live business path (TimetableService, TimetableBulkImportService,
-- TimetableSessionCopyWorker no longer group/validate by it — the copy worker still forwards its
-- value verbatim as historical data), but LegacyTimetableAdoptionWorker (a separate, one-time,
-- SUPER_ADMIN-only diagnostic/migration tool for adopting pre-Phase-F2 rows with
-- academic_session_id IS NULL) still reads it to classify legacy rows. Dropping the column now
-- would break that tool's compilation for no operational benefit — see the accompanying report.

-- 1. Drop the V55 partial unique indexes that limited a slot to a single ungrouped row. This is
--    the exact mechanism that would otherwise reject a second/third/... row in the same
--    school/session/class/day/period at the database level even if the application allowed it.
DROP INDEX IF EXISTS uq_timetable_entry_slot_ungrouped_no_section;
DROP INDEX IF EXISTS uq_timetable_entry_slot_ungrouped_with_section;

-- 2. Drop the correction-request feature's schema (V57). The table itself, its indexes, and its
--    foreign keys are dropped together with it (CASCADE via table drop); no timetable_entry rows
--    are touched.
DROP TABLE IF EXISTS timetable_correction_request;

-- 3. Drop the tenant-scoped teacher uniqueness constraint that existed solely to give the
--    correction-request table's composite foreign keys something to reference (teacher_id is
--    already globally unique via teacher_pkey). Nothing else in the schema references
--    teacher(school_id, teacher_id).
ALTER TABLE teacher DROP CONSTRAINT IF EXISTS uq_teacher_correction_tenant;
