-- =============================================================================
-- Edunexify — Minimum Required Seed Data
-- =============================================================================
-- Run this ONCE after a fresh database wipe (TRUNCATE / DROP+CREATE).
-- Everything else (feature_catalog, plans, global_subscription_config) is
-- seeded automatically by SubscriptionDataInitializer on application startup.
--
-- HOW TO RUN:
--   psql -U <db_user> -d <db_name> -f seed.sql
--
-- DEFAULT SUPER ADMIN CREDENTIALS:
--   Login ID : SUPERADMIN001
--   Password : Edunexify@2025   ← change this immediately after first login
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. SUPER ADMIN — users table (authentication record)
-- -----------------------------------------------------------------------------
-- role has no schoolId — SUPER_ADMIN operates platform-wide
INSERT INTO users (user_id, password, role, email, school_id, refresh_token_id)
VALUES (
    'SUPERADMIN001',
    '$2b$10$JV4EEvrt0/3x.7CScFff7.3Zh4k1yGNOtLBQzF1saDbJwdme.cwfS',  -- Edunexify@2025
    'SUPER_ADMIN',
    'admin@edunexify.co.in',
    NULL,
    NULL
)
ON CONFLICT (user_id) DO NOTHING;


-- -----------------------------------------------------------------------------
-- 2. SUPER ADMIN — admin table (profile record)
-- -----------------------------------------------------------------------------
-- AuthController.getMe() looks up the admin profile by userId for SUPER_ADMIN
INSERT INTO admin (admin_id, school_id, name, email, phone_number, dob, gender, photo_url, created_at, updated_at)
VALUES (
    'SUPERADMIN001',
    NULL,
    'Super Admin',
    'admin@edunexify.co.in',
    '9999999999',
    '1990-01-01',
    'MALE',
    NULL,
    NOW(),
    NOW()
)
ON CONFLICT (admin_id) DO NOTHING;


-- =============================================================================
-- AUTO-SEEDED ON STARTUP (do NOT insert manually — SubscriptionDataInitializer
-- handles these idempotently every time the application boots):
--
--   • feature_catalog          — 13 feature keys (FEE_MANAGEMENT, EXAM_MARKS, …)
--   • feature_catalog_dependencies — PAYMENT_COLLECTION→FEE_MANAGEMENT, etc.
--   • plans                    — Campus / Academy / Institute
--   • plan_features            — feature sets per plan
--   • global_subscription_config — grace period, trial days, notify days
--
-- To onboard a school after boot:
--   1. Log in as SUPERADMIN001 at /home
--   2. Go to Super Admin Dashboard → Onboard School
--   3. Fill in school details — a TRIAL subscription is created automatically
--   4. Register an ADMIN for that school via Register Admin
-- =============================================================================
