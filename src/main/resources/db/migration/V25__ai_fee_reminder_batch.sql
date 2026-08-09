-- Durable record of AI-copilot-triggered fee reminder batches: idempotency for the
-- send step (keyed by workflow_id, unique) and an audit trail. Inserted at workflow
-- START (not approval), so an abandoned batch the admin never approves is still
-- visible/auditable. The corresponding LangGraph pause/resume state lives in Redis
-- (edunexify-ai/checkpointer.py) and expires automatically — this table is the
-- permanent, Spring-owned counterpart for the one thing that must never be lost or
-- double-applied: what was actually sent.

CREATE TABLE ai_fee_reminder_batch (
    id              BIGSERIAL PRIMARY KEY,
    workflow_id     VARCHAR(64) NOT NULL UNIQUE,
    school_id       BIGINT NOT NULL,
    admin_user_id   VARCHAR(64) NOT NULL,
    session         VARCHAR(16) NOT NULL,
    class_name      VARCHAR(64),
    status          VARCHAR(32) NOT NULL DEFAULT 'PENDING_APPROVAL',
    student_ids     TEXT NOT NULL,
    sent_count      INT NOT NULL DEFAULT 0,
    failed_count    INT NOT NULL DEFAULT 0,
    outcomes        TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    completed_at    TIMESTAMP
);

CREATE INDEX idx_ai_fee_reminder_batch_school ON ai_fee_reminder_batch(school_id);
