-- Phase 2 (subscription redesign) added max_ai_messages_monthly/max_kb_documents to `plans`
-- (V19) but never propagated them into the resolved entitlement table, so nothing reading
-- school_effective_entitlements (including AuthController's /me and /api/school/entitlement)
-- could ever see a school's actual AI allowance. This closes that gap.
--
-- Same NULL-means-"not included" convention as plans.max_ai_messages_monthly/max_kb_documents —
-- NULL is a real value here (Campus), not "unknown".
--
-- Additive only: two nullable columns, no backfill needed since this table is fully rebuilt by
-- EntitlementRefreshService.refresh() on every subscription/plan change and by the nightly
-- scheduler — any existing row simply picks up real values on its next refresh.

ALTER TABLE public.school_effective_entitlements
    ADD COLUMN max_ai_messages_monthly integer,
    ADD COLUMN max_kb_documents integer;
