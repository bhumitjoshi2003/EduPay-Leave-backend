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
