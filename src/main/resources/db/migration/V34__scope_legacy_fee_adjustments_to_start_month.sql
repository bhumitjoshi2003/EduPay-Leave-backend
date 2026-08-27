-- Fee-assignment adjustments are batch/month scoped. Older rows created without
-- an end date must not silently carry discounts or opt-outs into later batches.
UPDATE student_fee_config
SET valid_until = (date_trunc('month', valid_from)
                   + interval '1 month'
                   - interval '1 day')::date
WHERE valid_from IS NOT NULL
  AND valid_until IS NULL
  AND revoked_at IS NULL;
