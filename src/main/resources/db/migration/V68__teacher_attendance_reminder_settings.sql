-- Per-school opt-in reminder nudging teachers who haven't marked their own attendance
-- by a configured local time. Disabled by default; the reminder time is only
-- meaningful (and required by application-level validation) once enabled.
ALTER TABLE school
    ADD COLUMN IF NOT EXISTS teacher_attendance_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS teacher_attendance_reminder_time TIME NULL;
