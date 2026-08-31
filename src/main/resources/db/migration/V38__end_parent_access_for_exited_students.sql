-- Existing guardian links must not retain operational access after a student exits.
-- The application performs the same update transactionally for all future exits.
UPDATE parent_student_relationship relationship
SET active = FALSE,
    effective_until = GREATEST(
        relationship.effective_from,
        COALESCE(student.leaving_date, CURRENT_DATE)
    ),
    updated_at = CURRENT_TIMESTAMP
FROM student
WHERE relationship.school_id = student.school_id
  AND relationship.student_id = student.student_id
  AND relationship.active = TRUE
  AND student.status IN ('TRANSFERRED', 'WITHDRAWN', 'GRADUATED', 'INACTIVE');
