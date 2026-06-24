-- V10: Report Card Templates — section-based, configurable per school
-- Each template links to an AssessmentGroup and defines which sections to render

CREATE TABLE report_card_template (
    id                  BIGSERIAL PRIMARY KEY,
    school_id           BIGINT NOT NULL,
    name                VARCHAR(150) NOT NULL,
    description         VARCHAR(500),
    assessment_group_id BIGINT NOT NULL REFERENCES assessment_group(id),
    grading_override    VARCHAR(20),        -- NULL = use school default; or CBSE / LETTER / PERCENTAGE
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP DEFAULT now(),
    updated_at          TIMESTAMP DEFAULT now()
);

CREATE TABLE report_card_template_section (
    id            BIGSERIAL PRIMARY KEY,
    template_id   BIGINT NOT NULL REFERENCES report_card_template(id) ON DELETE CASCADE,
    section_type  VARCHAR(50) NOT NULL,
    -- SCHOOL_HEADER | STUDENT_INFO | MARKS_TABLE | ASSESSMENT_SUMMARY
    -- ATTENDANCE | TEACHER_REMARKS | PRINCIPAL_REMARKS
    -- CO_SCHOLASTIC | PROMOTION_STATUS | SIGNATURES
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT NOT NULL DEFAULT 0,
    config_json   TEXT,                     -- JSON string for section-specific config
    CONSTRAINT uq_rcts_template_section UNIQUE (template_id, section_type)
);
