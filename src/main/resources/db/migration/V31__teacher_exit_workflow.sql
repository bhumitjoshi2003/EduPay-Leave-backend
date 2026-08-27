ALTER TABLE teacher
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS leaving_date DATE,
    ADD COLUMN IF NOT EXISTS reason_for_leaving VARCHAR(255),
    ADD COLUMN IF NOT EXISTS exit_remarks TEXT;

CREATE INDEX IF NOT EXISTS idx_teacher_school_status
    ON teacher (school_id, status);
