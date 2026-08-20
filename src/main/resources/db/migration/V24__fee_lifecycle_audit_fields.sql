ALTER TABLE student_fee_config
    ADD COLUMN revoked_at TIMESTAMP,
    ADD COLUMN revoked_by VARCHAR(100),
    ADD COLUMN revoke_reason VARCHAR(500);

CREATE INDEX idx_student_fee_config_history
    ON student_fee_config(school_id, student_id, academic_session_id, valid_from);
