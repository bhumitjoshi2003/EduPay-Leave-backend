-- Student Attendance V2 replaces the legacy absence-only `attendance` table (absence rows plus a
-- studentId 'X' sentinel per submission, with the fee's charge_paid flag mixed in). Legacy history
-- is intentionally not preserved or backfilled; every consumer now reads attendance_session /
-- student_attendance, and the fee's paid state lives in absence_charge_settlement (V80).
DROP TABLE IF EXISTS attendance;
