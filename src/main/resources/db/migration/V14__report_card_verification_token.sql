-- Phase F: QR Verification Token
-- Adds a unique UUID token to each published report card batch.
-- Used to generate a publicly-accessible verification URL embedded as a QR code in the PDF.

ALTER TABLE report_card_publication
    ADD COLUMN IF NOT EXISTS verification_token VARCHAR(36) NULL UNIQUE;

-- Back-fill existing rows with a random UUID so they are verifiable too
UPDATE report_card_publication
SET verification_token = UUID()
WHERE verification_token IS NULL;
