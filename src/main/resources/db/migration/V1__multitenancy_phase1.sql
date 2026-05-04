-- =============================================================
-- Phase 1 Multi-tenancy Migration
-- Adds school + school_class tables and school_id column to all
-- school-level entities. All school_id columns are nullable
-- initially so the migration is safe to run before data backfill.
-- =============================================================

-- ---------------------------------------------------------------
-- 1. School table
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS school (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    slug                VARCHAR(255) NOT NULL UNIQUE,
    board_type          VARCHAR(20),
    address             TEXT,
    city                VARCHAR(100),
    state               VARCHAR(100),
    pincode             VARCHAR(10),
    logo_url            TEXT,
    theme_color         VARCHAR(20),
    contact_person_name VARCHAR(255),
    email               VARCHAR(255),
    phone               VARCHAR(20),
    website             VARCHAR(255),
    plan                VARCHAR(20) NOT NULL DEFAULT 'TRIAL',
    max_students        INTEGER,
    expiry_date         DATE,
    active              BOOLEAN NOT NULL DEFAULT FALSE,
    razorpay_key_id     VARCHAR(255),
    razorpay_key_secret VARCHAR(255),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    onboarded_by        VARCHAR(255)
);

-- ---------------------------------------------------------------
-- 2. School class table
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS school_class (
    id            BIGSERIAL PRIMARY KEY,
    school_id     BIGINT NOT NULL REFERENCES school(id),
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_school_class_name UNIQUE (school_id, name)
);

-- ---------------------------------------------------------------
-- 3. Seed Indra Academy as School #1
-- ---------------------------------------------------------------
INSERT INTO school (id, name, slug, board_type, city, state, plan, active, created_at, onboarded_by)
VALUES (1, 'Indra Academy Sr. Sec. School', 'indra-academy', 'CBSE', 'Hisar', 'Haryana', 'PROFESSIONAL', TRUE, NOW(), 'SYSTEM')
ON CONFLICT (id) DO NOTHING;

-- Reset sequence so next insert gets id=2
SELECT setval('school_id_seq', (SELECT MAX(id) FROM school));

-- Seed class list for Indra Academy
INSERT INTO school_class (school_id, name, display_order, active) VALUES
(1, '1st',  1,  TRUE),
(1, '2nd',  2,  TRUE),
(1, '3rd',  3,  TRUE),
(1, '4th',  4,  TRUE),
(1, '5th',  5,  TRUE),
(1, '6th',  6,  TRUE),
(1, '7th',  7,  TRUE),
(1, '8th',  8,  TRUE),
(1, '9th',  9,  TRUE),
(1, '10th', 10, TRUE),
(1, '11th', 11, TRUE),
(1, '12th', 12, TRUE)
ON CONFLICT ON CONSTRAINT uq_school_class_name DO NOTHING;

-- ---------------------------------------------------------------
-- 4. Add school_id column to all school-level entities (nullable)
-- ---------------------------------------------------------------
ALTER TABLE users           ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE student         ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE teacher         ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE admin           ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE fee_structure    ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE payment         ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE student_fees    ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE attendance      ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE leaves          ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE notifications   ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE events          ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE timetable_entry ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE exam_config     ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE bus_fees        ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE class_subject   ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE audit_log       ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE audit_log_archive ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE user_notifications ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE student_mark    ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE device_tokens   ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE academic_stream  ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE optional_subject_group ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE optional_subject ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE stream_core_subject ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE student_stream_selection ADD COLUMN IF NOT EXISTS school_id BIGINT;
ALTER TABLE exam_subject_entry ADD COLUMN IF NOT EXISTS school_id BIGINT;

-- ---------------------------------------------------------------
-- 5. Backfill all existing rows to School #1
-- ---------------------------------------------------------------
UPDATE users                    SET school_id = 1 WHERE school_id IS NULL;
UPDATE student                  SET school_id = 1 WHERE school_id IS NULL;
UPDATE teacher                  SET school_id = 1 WHERE school_id IS NULL;
UPDATE admin                    SET school_id = 1 WHERE school_id IS NULL;
UPDATE fee_structure             SET school_id = 1 WHERE school_id IS NULL;
UPDATE payment                  SET school_id = 1 WHERE school_id IS NULL;
UPDATE student_fees             SET school_id = 1 WHERE school_id IS NULL;
UPDATE attendance               SET school_id = 1 WHERE school_id IS NULL;
UPDATE leaves                   SET school_id = 1 WHERE school_id IS NULL;
UPDATE notifications            SET school_id = 1 WHERE school_id IS NULL;
UPDATE events                   SET school_id = 1 WHERE school_id IS NULL;
UPDATE timetable_entry          SET school_id = 1 WHERE school_id IS NULL;
UPDATE exam_config              SET school_id = 1 WHERE school_id IS NULL;
UPDATE bus_fees                 SET school_id = 1 WHERE school_id IS NULL;
UPDATE class_subject            SET school_id = 1 WHERE school_id IS NULL;
UPDATE audit_log                SET school_id = 1 WHERE school_id IS NULL;
UPDATE audit_log_archive        SET school_id = 1 WHERE school_id IS NULL;
UPDATE user_notifications       SET school_id = 1 WHERE school_id IS NULL;
UPDATE student_mark             SET school_id = 1 WHERE school_id IS NULL;
UPDATE device_tokens            SET school_id = 1 WHERE school_id IS NULL;
UPDATE academic_stream          SET school_id = 1 WHERE school_id IS NULL;
UPDATE optional_subject_group   SET school_id = 1 WHERE school_id IS NULL;
UPDATE optional_subject         SET school_id = 1 WHERE school_id IS NULL;
UPDATE stream_core_subject      SET school_id = 1 WHERE school_id IS NULL;
UPDATE student_stream_selection SET school_id = 1 WHERE school_id IS NULL;
UPDATE exam_subject_entry       SET school_id = 1 WHERE school_id IS NULL;

-- ---------------------------------------------------------------
-- 6. Indexes on school_id for performance
-- ---------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_student_school         ON student(school_id);
CREATE INDEX IF NOT EXISTS idx_teacher_school         ON teacher(school_id);
CREATE INDEX IF NOT EXISTS idx_payment_school         ON payment(school_id);
CREATE INDEX IF NOT EXISTS idx_student_fees_school    ON student_fees(school_id);
CREATE INDEX IF NOT EXISTS idx_attendance_school      ON attendance(school_id);
CREATE INDEX IF NOT EXISTS idx_leaves_school          ON leaves(school_id);
CREATE INDEX IF NOT EXISTS idx_notifications_school   ON notifications(school_id);
CREATE INDEX IF NOT EXISTS idx_events_school          ON events(school_id);
