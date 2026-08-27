CREATE TABLE teacher_attendance_schedule (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    teacher_id VARCHAR(255) NOT NULL REFERENCES teacher(teacher_id) ON DELETE CASCADE,
    schedule_type VARCHAR(20) NOT NULL,
    working_days VARCHAR(100),
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_teacher_schedule_type CHECK (schedule_type IN ('SCHOOL', 'CUSTOM')),
    CONSTRAINT chk_teacher_schedule_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_teacher_schedule_days CHECK (
        (schedule_type = 'SCHOOL' AND working_days IS NULL)
        OR (schedule_type = 'CUSTOM' AND working_days IS NOT NULL AND length(trim(working_days)) > 0)
    ),
    CONSTRAINT uq_teacher_schedule_start UNIQUE (school_id, teacher_id, effective_from)
);

CREATE INDEX idx_teacher_schedule_lookup
    ON teacher_attendance_schedule (school_id, teacher_id, effective_from, effective_to);
