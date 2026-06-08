-- V6: Add exit-related columns and migrate INACTIVE → WITHDRAWN
-- New student statuses: GRADUATED, TRANSFERRED, WITHDRAWN

ALTER TABLE student ADD COLUMN IF NOT EXISTS reason_for_leaving VARCHAR(500);
ALTER TABLE student ADD COLUMN IF NOT EXISTS conduct_at_leaving VARCHAR(100);
ALTER TABLE student ADD COLUMN IF NOT EXISTS exit_remarks VARCHAR(1000);

-- Migrate all existing INACTIVE records to WITHDRAWN (safest default —
-- we cannot retroactively know if they graduated or transferred).
UPDATE student SET status = 'WITHDRAWN' WHERE status = 'INACTIVE';
