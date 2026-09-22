-- Low-frequency rollout-readiness metadata. These nullable fields preserve truthful
-- UNKNOWN states for existing users and intentionally contain no device identifier,
-- notification permission, session token, or analytics event data.
ALTER TABLE users ADD COLUMN client_platform VARCHAR(20);
ALTER TABLE users ADD COLUMN app_version_name VARCHAR(50);
ALTER TABLE users ADD COLUMN app_version_code INTEGER;
ALTER TABLE users ADD COLUMN client_reported_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN onboarding_completed_at TIMESTAMPTZ;
