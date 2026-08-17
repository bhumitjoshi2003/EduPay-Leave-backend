-- Adds a persistent flag forcing a password change before a user can access
-- any business API. Defaults to false so existing accounts are unaffected;
-- application code sets it true for newly-created STUDENT/TEACHER accounts.
ALTER TABLE users
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
