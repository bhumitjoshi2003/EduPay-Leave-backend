-- V22 created workflow assignments for pre-existing annual fee records but left
-- selected_months NULL. Reconciliation therefore could not identify which of the
-- twelve legacy academic months were missing. The pre-V22 generator was annual, so
-- these migrated GENERATED/PARTIALLY_GENERATED rows expected all twelve months.
UPDATE student_fee_assignment
SET selected_months = '1,2,3,4,5,6,7,8,9,10,11,12',
    updated_at = CURRENT_TIMESTAMP
WHERE (selected_months IS NULL OR BTRIM(selected_months) = '')
  AND status IN ('GENERATED', 'PARTIALLY_GENERATED');
