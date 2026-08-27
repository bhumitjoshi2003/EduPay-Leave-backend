-- Fee generation remains explicitly admin-triggered, but the workflow no longer
-- requires schools to manage a separate draft/active lifecycle in the UI.
ALTER TABLE school_fee_settings ALTER COLUMN operational_status SET DEFAULT 'ACTIVE';

UPDATE school_fee_settings
SET operational_status = 'ACTIVE',
    updated_at = CURRENT_TIMESTAMP
WHERE operational_status IN ('DISABLED', 'DRAFT', 'PAUSED');
