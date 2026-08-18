-- V19__ai_and_communication_limits.sql
-- Adds the two new usage-limit dimensions needed for the AI_COPILOT and BULK_COMMUNICATIONS
-- catalog entries introduced in this migration's companion Java change
-- (SubscriptionDataInitializer). Both are nullable and unenforced by any code path today —
-- EntitlementService.checkLimit() is not extended to read them in this change, matching the
-- existing storage_gb_limit column's precedent (a real Plan-level number that predates its own
-- enforcement). Wiring these into LimitType/checkLimit() is deliberately deferred to a later
-- phase; this migration only makes the numbers a persisted, seedable fact.
--
-- max_ai_messages_monthly: NULL means "AI Copilot not included" (Campus) rather than
-- "unlimited" — the inverse of every other limit column in this table, where NULL means
-- unlimited. This mirrors how AI_COPILOT plan-feature membership already works (Campus simply
-- doesn't have the feature key at all) and avoids a NULL on this specific column being
-- ambiguous between "no cap" and "no access" for a capability that, unlike students/staff/
-- storage, genuinely has a not-included tier.
--
-- max_kb_documents: same NULL convention, same reasoning — bounds per-school embedding cost
-- for the AI knowledge base.

ALTER TABLE public.plans
    ADD COLUMN max_ai_messages_monthly integer,
    ADD COLUMN max_kb_documents integer;
