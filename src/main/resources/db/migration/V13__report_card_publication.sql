-- Phase 6: Report Card Publishing
-- Tracks which class+session+template combinations have been published for student access.

CREATE TABLE IF NOT EXISTS report_card_publication (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    school_id     BIGINT       NOT NULL,
    template_id   BIGINT       NOT NULL,
    session       VARCHAR(20)  NOT NULL,
    class_name    VARCHAR(50)  NOT NULL,
    published_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_by  VARCHAR(150),
    email_sent_at TIMESTAMP    NULL DEFAULT NULL,
    email_count   INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pub (school_id, template_id, session, class_name),
    CONSTRAINT fk_pub_template FOREIGN KEY (template_id)
        REFERENCES report_card_template(id) ON DELETE CASCADE
);
