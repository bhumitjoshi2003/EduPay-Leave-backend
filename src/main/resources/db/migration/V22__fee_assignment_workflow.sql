-- Fee activation/assignment workflow. Additive only: existing StudentFees and payments remain authoritative.
CREATE TABLE school_fee_settings (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL UNIQUE REFERENCES school(id) ON DELETE CASCADE,
    operational_status VARCHAR(20) NOT NULL DEFAULT 'DISABLED',
    activation_date DATE,
    mid_session_policy VARCHAR(30) NOT NULL DEFAULT 'FROM_EFFECTIVE_MONTH',
    allow_retroactive_generation BOOLEAN NOT NULL DEFAULT FALSE,
    automatic_annual_generation BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_school_fee_status CHECK (operational_status IN ('DISABLED','DRAFT','ACTIVE','PAUSED')),
    CONSTRAINT ck_school_fee_mid_session CHECK (mid_session_policy IN ('FROM_EFFECTIVE_MONTH','NEXT_MONTH','PRORATE_JOINING_MONTH'))
);

CREATE TABLE student_fee_assignment (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    student_id VARCHAR(255) NOT NULL,
    academic_session VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'NOT_ASSIGNED',
    effective_date DATE,
    selected_months VARCHAR(40),
    excluded BOOLEAN NOT NULL DEFAULT FALSE,
    exclusion_reason VARCHAR(500),
    failure_reason VARCHAR(1000),
    assigned_by VARCHAR(255),
    assigned_at TIMESTAMP,
    generated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_student_fee_assignment UNIQUE (school_id, student_id, academic_session),
    CONSTRAINT ck_student_fee_assignment_status CHECK (status IN ('NOT_ASSIGNED','PENDING_CONFIGURATION','READY','GENERATING','GENERATED','PARTIALLY_GENERATED','EXCLUDED','GENERATION_FAILED'))
);
CREATE INDEX idx_student_fee_assignment_school_session_status
    ON student_fee_assignment(school_id, academic_session, status);

-- Existing fee rows are classified, never regenerated or modified.
INSERT INTO student_fee_assignment (school_id, student_id, academic_session, status, generated_at)
SELECT school_id, student_id, year,
       CASE WHEN COUNT(DISTINCT month) = 12 THEN 'GENERATED' ELSE 'PARTIALLY_GENERATED' END,
       CURRENT_TIMESTAMP
FROM student_fees
WHERE school_id IS NOT NULL AND student_id IS NOT NULL AND year IS NOT NULL
GROUP BY school_id, student_id, year
ON CONFLICT (school_id, student_id, academic_session) DO NOTHING;

CREATE TABLE student_transport_fee_assignment (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL REFERENCES school(id) ON DELETE CASCADE,
    student_id VARCHAR(255) NOT NULL,
    academic_session VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    distance NUMERIC(10,2),
    effective_from DATE NOT NULL,
    effective_to DATE,
    reason VARCHAR(500),
    changed_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_transport_fee_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_transport_fee_assignment_lookup
    ON student_transport_fee_assignment(school_id, student_id, academic_session, effective_from, effective_to);

