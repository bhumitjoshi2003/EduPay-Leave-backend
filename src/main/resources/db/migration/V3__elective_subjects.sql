-- ============================================================================
-- V3: Elective (optional) subject enrollment for classes 1–10
-- ============================================================================
-- Adds two columns to class_subject so subjects can be flagged as electives
-- belonging to a mutual-exclusion group (e.g. "elective-1").
-- Creates student_elective_enrollment to record each student's elective choice.
-- ============================================================================

-- 1. Extend class_subject with optional-subject fields
--    Column named is_elective (not "optional") to avoid MySQL reserved-word collision.
ALTER TABLE class_subject
    ADD COLUMN IF NOT EXISTS is_elective    BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS optional_group VARCHAR(100) NULL;

-- 2. Student elective enrollment
--    Unique constraint ensures one choice per optional_group per student per class.
CREATE TABLE IF NOT EXISTS student_elective_enrollment (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    school_id      BIGINT       NOT NULL,
    student_id     VARCHAR(50)  NOT NULL,
    class_name     VARCHAR(20)  NOT NULL,
    subject_name   VARCHAR(100) NOT NULL,
    optional_group VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_elective_student_group
        UNIQUE (school_id, student_id, class_name, optional_group),
    INDEX idx_elective_class   (school_id, class_name),
    INDEX idx_elective_student (school_id, student_id),
    INDEX idx_elective_subject (school_id, class_name, subject_name)
);
