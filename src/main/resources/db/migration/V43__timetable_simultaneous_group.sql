-- Additive only. NULL means a normal (single-occupant) timetable slot, exactly
-- today's behavior — every existing row remains valid and unaffected. A non-null,
-- admin-defined value (e.g. "MATH_BIO") tags two or more TimetableEntry rows as
-- legitimate simultaneous/elective alternatives within the same class+section+day+period.
ALTER TABLE timetable_entry ADD COLUMN simultaneous_group VARCHAR(100);
