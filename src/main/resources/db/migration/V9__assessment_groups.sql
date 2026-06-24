-- V9: Assessment Groups — configurable exam groupings with weightage
-- Supports Term/Annual groupings for flexible report card generation

CREATE TABLE assessment_group (
    id            BIGSERIAL PRIMARY KEY,
    school_id     BIGINT NOT NULL,
    session       VARCHAR(10) NOT NULL,
    class_name    VARCHAR(20) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100),
    group_type    VARCHAR(20) NOT NULL DEFAULT 'EXAM_BASED',
    display_order INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP DEFAULT now()
);

CREATE TABLE assessment_group_exam_mapping (
    id                  BIGSERIAL PRIMARY KEY,
    school_id           BIGINT NOT NULL,
    assessment_group_id BIGINT NOT NULL REFERENCES assessment_group(id) ON DELETE CASCADE,
    exam_config_id      BIGINT NOT NULL,
    weightage           DECIMAL(5,4) NOT NULL,
    display_order       INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_agm_group_exam UNIQUE (assessment_group_id, exam_config_id)
);

CREATE TABLE assessment_group_composition (
    id              BIGSERIAL PRIMARY KEY,
    school_id       BIGINT NOT NULL,
    parent_group_id BIGINT NOT NULL REFERENCES assessment_group(id) ON DELETE CASCADE,
    child_group_id  BIGINT NOT NULL REFERENCES assessment_group(id),
    weightage       DECIMAL(5,4) NOT NULL,
    display_order   INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_agc_parent_child UNIQUE (parent_group_id, child_group_id)
);

CREATE TABLE assessment_group_result (
    id                  BIGSERIAL PRIMARY KEY,
    school_id           BIGINT NOT NULL,
    student_id          VARCHAR(50) NOT NULL,
    assessment_group_id BIGINT NOT NULL REFERENCES assessment_group(id) ON DELETE CASCADE,
    session             VARCHAR(10) NOT NULL,
    weighted_score      DECIMAL(8,4),
    total_obtained      DECIMAL(8,2),
    total_max           DECIMAL(8,2),
    rank_position       INT,
    computed_at         TIMESTAMP DEFAULT now(),
    CONSTRAINT uq_agr_student_group_session UNIQUE (student_id, assessment_group_id, session)
);
