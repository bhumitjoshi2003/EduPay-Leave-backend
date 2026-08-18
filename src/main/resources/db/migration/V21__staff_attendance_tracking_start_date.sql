-- A nullable rollout boundary keeps existing schools' historical behaviour unchanged until an
-- admin deliberately configures a date. Once set, missing staff check-ins before this date are
-- not interpreted as absences.
ALTER TABLE school
    ADD COLUMN IF NOT EXISTS staff_attendance_tracking_start_date date;
