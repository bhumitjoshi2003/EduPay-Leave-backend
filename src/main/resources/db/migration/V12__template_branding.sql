-- Phase 5: CBSE Compliance + Custom Branding
-- Adds branding_json to store per-template colour, watermark, and feature flags.

ALTER TABLE report_card_template
    ADD COLUMN IF NOT EXISTS branding_json TEXT DEFAULT NULL;

COMMENT ON COLUMN report_card_template.branding_json IS
    'JSON: {primaryColor, accentColor, showWatermark, watermarkText, footerText, showCgpa, showGradePoints}';
