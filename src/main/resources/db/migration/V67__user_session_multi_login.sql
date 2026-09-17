-- Multi-session authentication. Previously, users.refresh_token_id held a SINGLE
-- JTI per account: every login (or refresh) overwrote it, so logging in on a second
-- browser/device silently broke the first session's ability to ever refresh again —
-- its access token kept working only until its own short natural expiry. This table
-- gives every login its own independent, revocable row, so N concurrent sessions can
-- each refresh/logout without touching any of the others.
--
-- users.refresh_token_id is intentionally left in place, unused — nothing reads or
-- writes it anymore after this migration; dropping it is unnecessary and out of scope.
--
-- refresh_token_hash stores SHA-256(raw refresh token JTI), never the raw token
-- itself — matches the existing users.reset_token convention (PasswordResetService
-- .hashToken). user_agent/ip_address are metadata only, never used for authorization
-- or session identity — a session is identified solely by its (secret, high-entropy)
-- refresh token, exactly as before.
CREATE TABLE user_session (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    user_agent VARCHAR(255),
    ip_address VARCHAR(64)
);

-- A refresh token's hash is effectively its identity — must be globally unique so a
-- hash lookup during refresh/logout resolves to exactly one session.
CREATE UNIQUE INDEX uq_user_session_refresh_token_hash ON user_session (refresh_token_hash);

-- Supports "list my active sessions" / "revoke all others" — scoped to a single user's
-- still-active (non-revoked) rows, which is the only access pattern besides the
-- unique hash lookup above.
CREATE INDEX idx_user_session_user_active ON user_session (user_id) WHERE revoked_at IS NULL;
