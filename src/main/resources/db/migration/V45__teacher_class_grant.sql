-- Lets an admin authorize a teacher to self-serve timetable periods for a class/section they
-- don't yet have any existing connection to (no logged periods, not their class-teacher
-- assignment) — see TimetableService#authorizeTeacherWrite, which checks this table as the
-- third and final way a teacher can be authorized to add a period for a class+section.
--
-- This is a lightweight, one-time admin action distinct from actually entering a period: the
-- admin only names the teacher + class + section, not a day/period/time.
CREATE TABLE teacher_class_grant (
    id BIGSERIAL PRIMARY KEY,
    school_id BIGINT NOT NULL,
    teacher_id VARCHAR(255) NOT NULL,
    class_name VARCHAR(100) NOT NULL,
    class_id BIGINT,
    section_id BIGINT,
    section_name VARCHAR(50),
    granted_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_teacher_class_grant_teacher ON teacher_class_grant (teacher_id, school_id);
