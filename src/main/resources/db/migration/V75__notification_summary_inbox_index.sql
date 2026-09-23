-- SUPER_ADMIN event-level delivery summary counts in-app recipients/opened per notification.
-- user_notifications has no index leading on notification_id (only (user_id, notification_id)),
-- so that aggregate scanned the whole inbox table. Including is_read lets COUNT/opened be served
-- from the index alone.
CREATE INDEX IF NOT EXISTS idx_user_notifications_notification
    ON user_notifications (notification_id, is_read);
