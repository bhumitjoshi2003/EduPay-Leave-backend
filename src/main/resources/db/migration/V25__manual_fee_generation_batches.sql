CREATE TABLE fee_generation_batch (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL,
    academic_session VARCHAR(20) NOT NULL,
    effective_date DATE NOT NULL,
    selected_months VARCHAR(40) NOT NULL,
    requested_student_ids TEXT NOT NULL,
    failed_student_ids TEXT,
    requested_students INTEGER NOT NULL DEFAULT 0,
    successful_students INTEGER NOT NULL DEFAULT 0,
    failed_students INTEGER NOT NULL DEFAULT 0,
    generated_months INTEGER NOT NULL DEFAULT 0,
    skipped_months INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL,
    initiated_by VARCHAR(100) NOT NULL,
    retry_of_batch_id BIGINT,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    CONSTRAINT fk_fee_generation_retry_batch FOREIGN KEY (retry_of_batch_id) REFERENCES fee_generation_batch(id)
);

CREATE INDEX idx_fee_generation_batch_school_session
    ON fee_generation_batch (school_id, academic_session, started_at DESC);
