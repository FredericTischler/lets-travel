-- V7__add_refresh_tokens.sql
-- Refresh tokens (partial mitigation of security audit G10 —
-- docs/lets-travel-architecture-decisions.md addendum "Refresh token").
--
-- Unlike `users`/`reports`, this table has no deleted_at: a refresh token is
-- REVOKED (revoked_at set, see RefreshTokenService#rotate/#revokeIfPresent),
-- never soft-deleted — the row must stay fully readable precisely so a
-- reused, already-rotated, or logged-out token can be recognized and
-- rejected, not filtered out of a read.
--
-- Only the SHA-256 hash of the opaque value handed to the client is ever
-- stored (token_hash); the plaintext exists solely in the HTTP response at
-- issuance and is never logged. The UNIQUE constraint on token_hash both
-- prevents a hash collision from being silently accepted and backs the
-- lookup (Postgres indexes unique constraints automatically). A dedicated
-- index on user_id supports a future "revoke every session for this user"
-- operation.

CREATE TABLE refresh_tokens (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users(id),
    token_hash  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
