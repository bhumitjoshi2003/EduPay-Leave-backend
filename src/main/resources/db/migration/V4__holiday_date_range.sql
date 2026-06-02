-- Rename single 'date' column to 'start_date' and add 'end_date' for date range support
ALTER TABLE school_holidays RENAME COLUMN date TO start_date;
ALTER TABLE school_holidays ADD COLUMN end_date DATE;
UPDATE school_holidays SET end_date = start_date WHERE end_date IS NULL;
ALTER TABLE school_holidays ALTER COLUMN end_date SET NOT NULL;

-- Drop old unique constraint and index, add new ones
DROP INDEX IF EXISTS idx_holiday_school_date;
ALTER TABLE school_holidays DROP CONSTRAINT IF EXISTS school_holidays_school_id_date_key;
CREATE INDEX IF NOT EXISTS idx_holiday_school_start ON school_holidays (school_id, start_date);
CREATE INDEX IF NOT EXISTS idx_holiday_school_end ON school_holidays (school_id, end_date);
