ALTER TABLE notification_deliveries
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(120);

-- A deployment may have been interrupted after marking rows PROCESSING. Make every
-- pre-lease PROCESSING row safely claimable by the new worker.
UPDATE notification_deliveries
SET status = 'FAILED_RETRYABLE',
    next_attempt_at = COALESCE(next_attempt_at, CURRENT_TIMESTAMP),
    last_error = CASE
        WHEN last_error IS NULL OR last_error = '' THEN 'Recovered during delivery-worker lease migration'
        ELSE last_error
    END,
    processing_started_at = NULL,
    lease_until = NULL,
    lease_owner = NULL
WHERE status = 'PROCESSING';

DROP INDEX IF EXISTS idx_notification_deliveries_pending;
CREATE INDEX idx_notification_deliveries_claimable
    ON notification_deliveries (status, next_attempt_at, lease_until, id);

CREATE INDEX idx_notification_deliveries_retention
    ON notification_deliveries (status, sent_at, created_at);
