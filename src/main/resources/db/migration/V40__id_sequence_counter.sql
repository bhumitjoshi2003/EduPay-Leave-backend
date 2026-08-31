-- Backing store for Edunexify's system-generated Student/Employee/Parent IDs
-- (stu_YYnnnnnn / emp_YYnnnnnn / par_YYnnnnnn). One row per (role_prefix, year_code)
-- combination; the counter itself never leaves this table — callers only ever see the
-- final formatted ID. Concurrency safety is provided entirely by the atomic
-- INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING statement the application uses to
-- read this table (see IdSequenceCounterRepository), which relies on Postgres's own
-- row-level locking and therefore stays correct across multiple backend instances —
-- no application-level locking of any kind is required or used.
--
-- This migration is purely additive: it creates a new table only and does not alter
-- any existing student/teacher/parent/user row or column. No existing ID is affected.
CREATE TABLE id_sequence_counter (
    role_prefix VARCHAR(10) NOT NULL,
    year_code   INTEGER     NOT NULL,
    next_seq    BIGINT      NOT NULL,
    PRIMARY KEY (role_prefix, year_code)
);
