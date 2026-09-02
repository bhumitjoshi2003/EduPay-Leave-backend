-- Notification inbox rows are durable recipient snapshots. Repair legacy tenant
-- values, collapse any rows created repeatedly by the former GET-side
-- materialisation, then enforce one inbox row per tenant/user/notification.

UPDATE user_notifications inbox
SET school_id = notification.school_id
FROM notifications notification
WHERE inbox.notification_id = notification.id
  AND notification.school_id IS NOT NULL
  AND inbox.school_id IS DISTINCT FROM notification.school_id;

WITH duplicate_groups AS (
    SELECT school_id,
           user_id,
           notification_id,
           MIN(id) AS keep_id,
           -- If any duplicate row had already been read, preserve that read
           -- state when collapsing legacy GET-materialised duplicates.
           BOOL_OR(is_read) AS merged_is_read,
           MIN(created_at) AS merged_created_at
    FROM user_notifications
    GROUP BY school_id, user_id, notification_id
    HAVING COUNT(*) > 1
)
UPDATE user_notifications inbox
SET is_read = duplicate_groups.merged_is_read,
    created_at = duplicate_groups.merged_created_at
FROM duplicate_groups
WHERE inbox.id = duplicate_groups.keep_id;

DELETE FROM user_notifications duplicate
USING user_notifications canonical
WHERE duplicate.id > canonical.id
  AND duplicate.user_id = canonical.user_id
  AND duplicate.notification_id = canonical.notification_id
  AND duplicate.school_id IS NOT DISTINCT FROM canonical.school_id;

ALTER TABLE user_notifications
    ADD CONSTRAINT uq_user_notifications_school_user_notification
    UNIQUE (school_id, user_id, notification_id);

-- PostgreSQL treats NULL values as distinct in a normal unique constraint.
-- Keep the nullable tenant column for explicitly platform-global legacy records,
-- but make those rows unique too instead of leaving NULL as a duplicate loophole.
CREATE UNIQUE INDEX uq_user_notifications_global_user_notification
    ON user_notifications (user_id, notification_id)
    WHERE school_id IS NULL;

-- Preserve existing Notice Board visibility after removing GET-side writes.
-- This takes a one-time snapshot of the currently eligible audience of every
-- already-published school notification. Future publications are snapshotted by
-- NotificationService at write time.
WITH recipient_candidates AS (
    SELECT notification.school_id,
           student.student_id AS user_id,
           notification.id AS notification_id,
           notification.created_at
    FROM notifications notification
    JOIN student
      ON student.school_id = notification.school_id
     AND student.status = 'ACTIVE'
    JOIN users account
      ON account.school_id = student.school_id
     AND account.user_id = student.student_id
     AND account.active = TRUE
    WHERE notification.school_id IS NOT NULL
      AND notification.created_by IS NOT NULL
      AND (
          UPPER(notification.audience) IN ('ALL', 'STUDENTS')
          OR UPPER(notification.audience) = UPPER('CLASS:' || student.class_name)
          OR UPPER(notification.audience) = UPPER('CLASS_WITH_TEACHER:' || student.class_name)
      )

    UNION

    SELECT notification.school_id,
           teacher.teacher_id,
           notification.id,
           notification.created_at
    FROM notifications notification
    JOIN teacher
      ON teacher.school_id = notification.school_id
     AND teacher.status = 'ACTIVE'
    JOIN users account
      ON account.school_id = teacher.school_id
     AND account.user_id = teacher.teacher_id
     AND account.active = TRUE
    WHERE notification.school_id IS NOT NULL
      AND notification.created_by IS NOT NULL
      AND (
          UPPER(notification.audience) IN ('ALL', 'TEACHERS')
          OR UPPER(notification.audience) = UPPER('CLASS_WITH_TEACHER:' || teacher.class_teacher)
      )

    UNION

    SELECT notification.school_id,
           parent.parent_id,
           notification.id,
           notification.created_at
    FROM notifications notification
    JOIN student
      ON student.school_id = notification.school_id
     AND student.status = 'ACTIVE'
    JOIN parent_student_relationship relationship
      ON relationship.school_id = student.school_id
     AND relationship.student_id = student.student_id
     AND relationship.active = TRUE
     AND relationship.effective_from <= CURRENT_DATE
     AND (relationship.effective_until IS NULL OR relationship.effective_until >= CURRENT_DATE)
    JOIN parent_account parent
      ON parent.school_id = relationship.school_id
     AND parent.parent_id = relationship.parent_id
     AND parent.active = TRUE
    JOIN users account
      ON account.school_id = parent.school_id
     AND account.user_id = parent.parent_id
     AND account.active = TRUE
    WHERE notification.school_id IS NOT NULL
      AND notification.created_by IS NOT NULL
      AND (
          UPPER(notification.audience) IN ('ALL', 'STUDENTS')
          OR UPPER(notification.audience) = UPPER('CLASS:' || student.class_name)
          OR UPPER(notification.audience) = UPPER('CLASS_WITH_TEACHER:' || student.class_name)
      )

    UNION

    SELECT notification.school_id,
           account.user_id,
           notification.id,
           notification.created_at
    FROM notifications notification
    JOIN users account
      ON account.school_id = notification.school_id
     AND UPPER(account.user_id) = UPPER(notification.audience)
     AND account.active = TRUE
    WHERE notification.school_id IS NOT NULL
      AND notification.created_by IS NOT NULL
      AND UPPER(notification.audience) NOT IN ('ALL', 'STUDENTS', 'TEACHERS')
      AND UPPER(notification.audience) NOT LIKE 'CLASS:%'
      AND UPPER(notification.audience) NOT LIKE 'CLASS_WITH_TEACHER:%'
)
INSERT INTO user_notifications (school_id, user_id, notification_id, is_read, created_at)
SELECT school_id, user_id, notification_id, FALSE, created_at
FROM recipient_candidates
ON CONFLICT ON CONSTRAINT uq_user_notifications_school_user_notification DO NOTHING;
