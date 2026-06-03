-- ============================================================
-- V5: Teacher Check-in Attendance System
-- Adds GPS-based check-in settings to school, creates teacher_attendance table
-- ============================================================

-- 1. Add staff-attendance configuration columns to school
ALTER TABLE school ADD COLUMN school_latitude DOUBLE PRECISION;
ALTER TABLE school ADD COLUMN school_longitude DOUBLE PRECISION;
ALTER TABLE school ADD COLUMN geofence_radius INTEGER DEFAULT 200;
ALTER TABLE school ADD COLUMN school_start_time TIME;
ALTER TABLE school ADD COLUMN late_threshold_minutes INTEGER DEFAULT 5;
ALTER TABLE school ADD COLUMN checkin_window_start TIME;
ALTER TABLE school ADD COLUMN checkin_window_end TIME;

-- 2. Create teacher_attendance table
CREATE TABLE teacher_attendance (
    id BIGSERIAL PRIMARY KEY,
    teacher_id VARCHAR(255) NOT NULL,
    school_id BIGINT NOT NULL,
    date DATE NOT NULL,
    check_in_time TIMESTAMP,
    check_out_time TIMESTAMP,
    status VARCHAR(20) NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    distance_from_school DOUBLE PRECISION,
    method VARCHAR(20) DEFAULT 'GPS',
    marked_by_admin BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT uq_teacher_date_school UNIQUE (teacher_id, date, school_id)
);

CREATE INDEX idx_teacher_att_teacher_date ON teacher_attendance (teacher_id, date);
CREATE INDEX idx_teacher_att_school_date ON teacher_attendance (school_id, date);
