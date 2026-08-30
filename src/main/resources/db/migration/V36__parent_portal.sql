CREATE TABLE IF NOT EXISTS parent_account (
    parent_id VARCHAR(50) PRIMARY KEY,
    school_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    email VARCHAR(255),
    phone_number VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_parent_account_school ON parent_account(school_id);
CREATE INDEX IF NOT EXISTS idx_parent_account_phone ON parent_account(school_id, phone_number);

CREATE TABLE IF NOT EXISTS parent_student_relationship (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL,
    parent_id VARCHAR(50) NOT NULL REFERENCES parent_account(parent_id),
    student_id VARCHAR(50) NOT NULL REFERENCES student(student_id),
    relationship_type VARCHAR(30) NOT NULL,
    primary_guardian BOOLEAN NOT NULL DEFAULT FALSE,
    can_view_attendance BOOLEAN NOT NULL DEFAULT TRUE,
    can_view_fees BOOLEAN NOT NULL DEFAULT TRUE,
    can_pay_fees BOOLEAN NOT NULL DEFAULT TRUE,
    can_view_results BOOLEAN NOT NULL DEFAULT TRUE,
    can_manage_leave BOOLEAN NOT NULL DEFAULT TRUE,
    pickup_authorized BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_until DATE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_parent_student_school UNIQUE (school_id, parent_id, student_id),
    CONSTRAINT ck_parent_relationship_dates CHECK (effective_until IS NULL OR effective_until >= effective_from)
);

CREATE INDEX IF NOT EXISTS idx_parent_student_parent ON parent_student_relationship(school_id, parent_id, active);
CREATE INDEX IF NOT EXISTS idx_parent_student_student ON parent_student_relationship(school_id, student_id, active);
