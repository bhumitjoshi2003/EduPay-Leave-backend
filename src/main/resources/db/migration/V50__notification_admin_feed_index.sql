-- Speeds up NotificationRepository.findBySchoolIdAndCreatedByIsNotNull(schoolId, pageable) —
-- the admin Notice Board's paginated feed. Partial (WHERE created_by IS NOT NULL) because
-- most notifications rows are system-authored business events this query always excludes;
-- keeping the index scoped to only the rows it actually serves keeps it small as that
-- majority grows. Includes created_at DESC directly so the query's ORDER BY is satisfied
-- by the index itself instead of a separate sort step once the table is large enough for
-- the planner to prefer it over a sequential scan.
CREATE INDEX IF NOT EXISTS idx_notifications_school_admin_feed
    ON notifications (school_id, created_at DESC)
    WHERE created_by IS NOT NULL;
