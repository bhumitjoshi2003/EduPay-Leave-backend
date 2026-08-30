-- Introduce the parent portal to plans and effective entitlements that existed
-- before the feature was added. The DISABLED override remains authoritative.
INSERT INTO feature_catalog (feature_key, display_name, description, category, is_always_on)
VALUES ('PARENT_PORTAL', 'Parent Portal',
        'Secure guardian accounts with multi-child access to attendance, fees, results, leave and school communication.',
        'COMMUNICATION', FALSE)
ON CONFLICT (feature_key) DO NOTHING;

INSERT INTO plan_features (feature_key, plan_id, created_at)
SELECT 'PARENT_PORTAL', p.id, CURRENT_TIMESTAMP
FROM plans p
WHERE UPPER(p.tier) IN ('CAMPUS', 'ACADEMY', 'INSTITUTE')
ON CONFLICT (feature_key, plan_id) DO NOTHING;

INSERT INTO school_entitlement_features (feature_key, school_id, added_at, source_plan_id)
SELECT 'PARENT_PORTAL', ss.school_id, CURRENT_TIMESTAMP, ss.plan_id
FROM school_subscriptions ss
JOIN plans p ON p.id = ss.plan_id
WHERE UPPER(p.tier) IN ('CAMPUS', 'ACADEMY', 'INSTITUTE')
  AND UPPER(ss.status) <> 'EXPIRED'
  AND NOT EXISTS (
      SELECT 1
      FROM school_feature_overrides sfo
      WHERE sfo.school_id = ss.school_id
        AND sfo.feature_key = 'PARENT_PORTAL'
        AND UPPER(sfo.override_state) = 'DISABLED'
  )
ON CONFLICT (feature_key, school_id) DO NOTHING;

ALTER TABLE parent_student_relationship
    ADD COLUMN IF NOT EXISTS can_view_timetable BOOLEAN NOT NULL DEFAULT TRUE;
