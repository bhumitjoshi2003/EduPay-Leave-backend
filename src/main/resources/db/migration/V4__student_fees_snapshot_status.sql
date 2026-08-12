-- Consumer-migration phase: gives every StudentFees row a native, indexable twin of
-- amount_rule_snapshot's embedded "status" field, so payment/reminder/checkout code can
-- check whether a snapshot is trustworthy (SnapshotStatus.COMPUTED) with one column read
-- instead of parsing the JSON blob on every access. NULL on existing rows means "unknown
-- trustworthiness" (pre-migration data, or a row generated before this column existed) —
-- consumers must treat NULL the same as NO_ACTIVE_RULE_THIS_MONTH (untrustworthy, fall back
-- to live computation), never assume it means COMPUTED.
ALTER TABLE student_fees ADD COLUMN IF NOT EXISTS snapshot_status VARCHAR(30);
