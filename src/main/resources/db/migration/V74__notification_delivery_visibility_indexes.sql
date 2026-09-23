-- SUPER_ADMIN notification delivery visibility lists the newest rows across every school.
-- Existing indexes are per-recipient (inbox) or per-worker-queue (status/next_attempt_at), so a
-- cross-school "most recent first" page would otherwise scan and sort the whole table.
CREATE INDEX IF NOT EXISTS idx_notification_deliveries_recent
    ON notification_deliveries (created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_user_notifications_recent
    ON user_notifications (created_at DESC, id DESC);
