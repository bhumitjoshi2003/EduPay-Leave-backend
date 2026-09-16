-- Additive pricing snapshots. Nullable fields preserve unambiguous interpretation of legacy
-- PLATFORM_FEE rows; new writes identify themselves with pricing_version.
ALTER TABLE payment_order
    ADD COLUMN school_liability_principal_paise BIGINT,
    ADD COLUMN gateway_rate_bps INTEGER,
    ADD COLUMN gateway_tax_rate_bps INTEGER,
    ADD COLUMN gateway_recovery_fee_paise BIGINT,
    ADD COLUMN edunexify_transaction_fee_paise BIGINT,
    ADD COLUMN pricing_version VARCHAR(40),
    ADD CONSTRAINT chk_po_school_principal_nonnegative CHECK (school_liability_principal_paise IS NULL OR school_liability_principal_paise >= 0),
    ADD CONSTRAINT chk_po_gateway_rate_bps CHECK (gateway_rate_bps IS NULL OR (gateway_rate_bps >= 0 AND gateway_rate_bps < 10000)),
    ADD CONSTRAINT chk_po_gateway_tax_rate_bps CHECK (gateway_tax_rate_bps IS NULL OR (gateway_tax_rate_bps >= 0 AND gateway_tax_rate_bps < 10000)),
    ADD CONSTRAINT chk_po_gateway_recovery_nonnegative CHECK (gateway_recovery_fee_paise IS NULL OR gateway_recovery_fee_paise >= 0),
    ADD CONSTRAINT chk_po_edunexify_fee_nonnegative CHECK (edunexify_transaction_fee_paise IS NULL OR edunexify_transaction_fee_paise >= 0);

ALTER TABLE payment
    ADD COLUMN school_liability_principal_paise BIGINT,
    ADD COLUMN gateway_rate_bps INTEGER,
    ADD COLUMN gateway_tax_rate_bps INTEGER,
    ADD COLUMN gateway_recovery_fee_paise BIGINT,
    ADD COLUMN edunexify_transaction_fee_paise BIGINT,
    ADD COLUMN pricing_version VARCHAR(40),
    ADD CONSTRAINT chk_payment_school_principal_nonnegative CHECK (school_liability_principal_paise IS NULL OR school_liability_principal_paise >= 0),
    ADD CONSTRAINT chk_payment_gateway_rate_bps CHECK (gateway_rate_bps IS NULL OR (gateway_rate_bps >= 0 AND gateway_rate_bps < 10000)),
    ADD CONSTRAINT chk_payment_gateway_tax_rate_bps CHECK (gateway_tax_rate_bps IS NULL OR (gateway_tax_rate_bps >= 0 AND gateway_tax_rate_bps < 10000)),
    ADD CONSTRAINT chk_payment_gateway_recovery_nonnegative CHECK (gateway_recovery_fee_paise IS NULL OR gateway_recovery_fee_paise >= 0),
    ADD CONSTRAINT chk_payment_edunexify_fee_nonnegative CHECK (edunexify_transaction_fee_paise IS NULL OR edunexify_transaction_fee_paise >= 0);

-- Dynamic, effective-dated, platform-level (never school-scoped) payment pricing authority.
-- The database — not environment variables — is now the source of CURRENT/FUTURE pricing;
-- a PaymentOrder/Payment's own snapshot columns above remain the sole historical authority for
-- an already-created transaction (never recalculated by joining back to this table). A pricing
-- version is immutable once created: "editing" is always a new row with a later effective_from;
-- cancellation (cancelled_at/cancelled_by) is only permitted while effective_from is still in
-- the future — see PaymentPricingService for the enforcement, this table only shapes the data.
CREATE TABLE payment_pricing_config (
    id BIGSERIAL PRIMARY KEY,
    gateway_provider VARCHAR(30) NOT NULL,
    gateway_rate_bps INTEGER NOT NULL,
    gateway_tax_rate_bps INTEGER NOT NULL,
    edunexify_transaction_fee_paise BIGINT NOT NULL,
    -- Absolute instant (TIMESTAMPTZ, mapped to java.time.Instant) — never an ambiguous
    -- server-local timestamp, so "01 Jan 2027 00:00 IST" has one unambiguous meaning
    -- regardless of the reading server's own timezone.
    effective_from TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(255),
    cancelled_at TIMESTAMPTZ,
    cancelled_by VARCHAR(255),
    CONSTRAINT chk_ppc_gateway_rate_bps CHECK (gateway_rate_bps >= 0 AND gateway_rate_bps < 10000),
    CONSTRAINT chk_ppc_gateway_tax_rate_bps CHECK (gateway_tax_rate_bps >= 0 AND gateway_tax_rate_bps < 10000),
    CONSTRAINT chk_ppc_edunexify_fee_nonnegative CHECK (edunexify_transaction_fee_paise >= 0),
    CONSTRAINT chk_ppc_cancellation_consistency CHECK (
        (cancelled_at IS NULL AND cancelled_by IS NULL) OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)
    )
);

-- Two ACTIVE (non-cancelled) versions for the same provider can never share an effective_from —
-- a cancelled version may coexist at the same instant since it can never be selected. Partial
-- index, not a table-wide UNIQUE constraint, precisely because cancellation must not be
-- retroactively blocked by an old timestamp collision.
CREATE UNIQUE INDEX uq_ppc_provider_effective_from_active
    ON payment_pricing_config (gateway_provider, effective_from) WHERE cancelled_at IS NULL;

-- Supports PaymentPricingService.resolveActive's "latest non-cancelled row with
-- effective_from <= now" lookup directly via an index scan.
CREATE INDEX idx_ppc_provider_active_effective
    ON payment_pricing_config (gateway_provider, effective_from DESC) WHERE cancelled_at IS NULL;

-- Provenance only — which pricing version produced this transaction's already-snapshotted
-- values above. Never joined back to for calculation; NULL for manual/legacy payments.
ALTER TABLE payment_order
    ADD COLUMN payment_pricing_config_id BIGINT REFERENCES payment_pricing_config(id) ON DELETE RESTRICT;
ALTER TABLE payment
    ADD COLUMN payment_pricing_config_id BIGINT REFERENCES payment_pricing_config(id) ON DELETE RESTRICT;
