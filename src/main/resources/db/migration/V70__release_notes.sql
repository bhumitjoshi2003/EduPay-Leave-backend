-- Platform-wide "What's New" release notes. Not school-scoped — one row is shown to every
-- tenant whose role matches its audience. Phase 1 has no create/update endpoint; rows are
-- seeded/maintained via ReleaseNoteSeeder (see that class for how to add a new release).
CREATE TABLE release_notes (
    id            BIGSERIAL PRIMARY KEY,
    version       VARCHAR(20)  NOT NULL UNIQUE,
    title         VARCHAR(200) NOT NULL,
    summary       VARCHAR(500),
    items         TEXT,
    audience      VARCHAR(20)  NOT NULL DEFAULT 'ALL',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    published_at  DATE         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_release_notes_active_published ON release_notes (active, published_at DESC);
