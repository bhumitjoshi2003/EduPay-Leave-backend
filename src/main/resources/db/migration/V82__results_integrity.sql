-- Results Phase 1: school-safe uniqueness, referential integrity, result publishing and mark
-- metadata. Existing results/marks data carries no value that needs preserving (confirmed before
-- this release), so incompatible rows are removed rather than repaired.

-- ── 1. Clean up rows the new constraints cannot accept ───────────────────────────────────────
DELETE FROM student_mark WHERE school_id IS NULL
    OR exam_subject_entry_id NOT IN (SELECT id FROM exam_subject_entry);
DELETE FROM exam_subject_entry WHERE school_id IS NULL
    OR exam_config_id NOT IN (SELECT id FROM exam_config);
DELETE FROM student_mark WHERE exam_subject_entry_id NOT IN (SELECT id FROM exam_subject_entry);
DELETE FROM assessment_group_exam_mapping WHERE exam_config_id NOT IN (SELECT id FROM exam_config WHERE school_id IS NOT NULL);
DELETE FROM student_mark WHERE exam_subject_entry_id IN (
    SELECT e.id FROM exam_subject_entry e JOIN exam_config c ON c.id = e.exam_config_id WHERE c.school_id IS NULL);
DELETE FROM exam_subject_entry WHERE exam_config_id IN (SELECT id FROM exam_config WHERE school_id IS NULL);
DELETE FROM exam_config WHERE school_id IS NULL;
DELETE FROM class_subject WHERE school_id IS NULL;
-- A mark/entry must belong to the same school as its parent row.
DELETE FROM student_mark m USING exam_subject_entry e
    WHERE e.id = m.exam_subject_entry_id AND e.school_id <> m.school_id;
DELETE FROM student_mark WHERE exam_subject_entry_id IN (
    SELECT e.id FROM exam_subject_entry e JOIN exam_config c ON c.id = e.exam_config_id WHERE e.school_id <> c.school_id);
DELETE FROM exam_subject_entry e USING exam_config c
    WHERE c.id = e.exam_config_id AND e.school_id <> c.school_id;
-- assessment_group_result is a derived cache; it is rebuilt on demand and may hold duplicates.
DELETE FROM assessment_group_result;

-- ── 2. School-safe uniqueness (the V1 constraints ignored school_id) ─────────────────────────
ALTER TABLE exam_config DROP CONSTRAINT IF EXISTS uknysqeyvylll8i8xcqcl3ti2i3;
ALTER TABLE class_subject DROP CONSTRAINT IF EXISTS uktgbvi51if9cyreqll0yem061s;
ALTER TABLE exam_subject_entry DROP CONSTRAINT IF EXISTS ukpt3821q5ksk31l8dh0n4faoda;

ALTER TABLE exam_config ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE exam_subject_entry ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE student_mark ALTER COLUMN school_id SET NOT NULL;
ALTER TABLE class_subject ALTER COLUMN school_id SET NOT NULL;

ALTER TABLE exam_config
    ADD CONSTRAINT uq_exam_config_school_session_class_exam UNIQUE (school_id, session, class_name, exam_name);
ALTER TABLE class_subject
    ADD CONSTRAINT uq_class_subject_school_class_subject UNIQUE (school_id, class_name, subject_name);
ALTER TABLE exam_subject_entry
    ADD CONSTRAINT uq_exam_subject_entry_exam_subject UNIQUE (exam_config_id, subject_name);
ALTER TABLE assessment_group_result
    ADD CONSTRAINT uq_assessment_group_result_student_group_session
        UNIQUE (school_id, student_id, assessment_group_id, session);

-- ── 3. Referential integrity. RESTRICT, never CASCADE: deleting an exam or subject that still has
--       marks must be an explicit, reviewed action, not a side effect of one confirmation. ───────
ALTER TABLE exam_subject_entry
    ADD CONSTRAINT fk_exam_subject_entry_exam_config
        FOREIGN KEY (exam_config_id) REFERENCES exam_config(id) ON DELETE RESTRICT;
ALTER TABLE student_mark
    ADD CONSTRAINT fk_student_mark_exam_subject_entry
        FOREIGN KEY (exam_subject_entry_id) REFERENCES exam_subject_entry(id) ON DELETE RESTRICT;

-- ── 4. Result publishing: every exam's results start as DRAFT (hidden from students/parents). ──
ALTER TABLE exam_config ADD COLUMN result_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE exam_config ADD CONSTRAINT ck_exam_config_result_status CHECK (result_status IN ('DRAFT', 'PUBLISHED'));
ALTER TABLE exam_config ADD COLUMN published_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE exam_config ADD COLUMN published_by VARCHAR(255);
ALTER TABLE exam_config ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

-- ── 5. Mark metadata: who created / last changed a mark, when, and an optimistic-lock version. ─
ALTER TABLE student_mark RENAME COLUMN entered_by TO updated_by;
ALTER TABLE student_mark ADD COLUMN created_by VARCHAR(255);
ALTER TABLE student_mark ADD COLUMN created_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE student_mark ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
UPDATE student_mark SET created_by = updated_by, created_at = COALESCE(updated_at, now()), updated_at = COALESCE(updated_at, now());
ALTER TABLE student_mark ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE student_mark ALTER COLUMN updated_at SET NOT NULL;

-- ── 6. Indexes for the hot result lookups ────────────────────────────────────────────────────
CREATE INDEX idx_exam_config_school_session_class ON exam_config (school_id, session, class_name);
CREATE INDEX idx_exam_subject_entry_school_exam ON exam_subject_entry (school_id, exam_config_id);
CREATE INDEX idx_student_mark_school_entry ON student_mark (school_id, exam_subject_entry_id);
CREATE INDEX idx_student_mark_school_student ON student_mark (school_id, student_id);
CREATE INDEX idx_class_subject_school_class ON class_subject (school_id, class_name);
CREATE INDEX idx_assessment_group_exam_mapping_exam ON assessment_group_exam_mapping (school_id, exam_config_id);
