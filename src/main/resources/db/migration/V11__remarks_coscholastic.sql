-- ── Phase 3: Remarks + Co-Scholastic storage ─────────────────────────────────

-- Teacher and Principal remarks per student per template+session
CREATE TABLE report_card_remark (
    id           BIGSERIAL PRIMARY KEY,
    school_id    BIGINT       NOT NULL,
    student_id   VARCHAR(30)  NOT NULL,
    template_id  BIGINT       NOT NULL REFERENCES report_card_template(id) ON DELETE CASCADE,
    session      VARCHAR(20)  NOT NULL,
    remark_type  VARCHAR(20)  NOT NULL, -- TEACHER | PRINCIPAL
    remark_text  TEXT,
    entered_by   VARCHAR(100),
    entered_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_rcr_student_template_session_type
        UNIQUE (student_id, template_id, session, remark_type)
);

CREATE INDEX idx_rcr_template_session_school
    ON report_card_remark (template_id, session, school_id);

-- Co-scholastic activity grades per student per template+session
CREATE TABLE co_scholastic_entry (
    id           BIGSERIAL PRIMARY KEY,
    school_id    BIGINT       NOT NULL,
    student_id   VARCHAR(30)  NOT NULL,
    template_id  BIGINT       NOT NULL REFERENCES report_card_template(id) ON DELETE CASCADE,
    session      VARCHAR(20)  NOT NULL,
    activity     VARCHAR(100) NOT NULL,
    grade        VARCHAR(10),
    entered_by   VARCHAR(100),
    entered_at   TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_cse_student_template_session_activity
        UNIQUE (student_id, template_id, session, activity)
);

CREATE INDEX idx_cse_template_session_school
    ON co_scholastic_entry (template_id, session, school_id);
