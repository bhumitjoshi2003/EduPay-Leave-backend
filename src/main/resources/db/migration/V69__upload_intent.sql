-- Direct-to-object-storage upload authorizations (Phase 1: teacher profile photo only).
-- Each row is the trust anchor for /api/files/complete — the raw objectKey a client reports is
-- never trusted alone; it must match a PENDING, unexpired intent issued to the current
-- school/user for the declared purpose and entity. Rows left PENDING past expiry are orphan
-- objects a client never completed uploading, cleaned up by a rare (daily) sweep.
CREATE TABLE upload_intent (
    id                      BIGSERIAL PRIMARY KEY,
    school_id               BIGINT NOT NULL REFERENCES school(id),
    requested_by_user_id    VARCHAR(255) NOT NULL,
    purpose                 VARCHAR(64) NOT NULL,
    entity_id               VARCHAR(255) NOT NULL,
    object_key              VARCHAR(512) NOT NULL,
    expected_content_type   VARCHAR(128) NOT NULL,
    expected_size           BIGINT NOT NULL,
    status                  VARCHAR(32) NOT NULL,
    created_at              TIMESTAMP NOT NULL,
    expires_at              TIMESTAMP NOT NULL,
    completed_at            TIMESTAMP NULL,
    CONSTRAINT uq_upload_intent_object_key UNIQUE (object_key)
);

-- The cleanup sweep's own candidate query (status + expires_at) and the completion endpoint's
-- own per-school scoping both benefit from these.
CREATE INDEX idx_upload_intent_status_expires_at ON upload_intent (status, expires_at);
CREATE INDEX idx_upload_intent_school_id ON upload_intent (school_id);
