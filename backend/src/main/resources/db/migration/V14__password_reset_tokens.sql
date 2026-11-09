-- Password reset. Same shape as refresh_token, for the same reasons.
--
-- A reset token is a credential sent to an address we do not control, so it is
-- single use, short lived, and stored only as a hash. Read access to this table
-- hands over nothing.

CREATE TABLE password_reset_token (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES app_user(id),

    -- SHA-256 of the token, hex. NOT BCrypt, for the same reason refresh_token
    -- is not: BCrypt is slow on purpose because passwords are low-entropy and
    -- worth brute-forcing. A 256-bit random value is neither, so slowness buys
    -- nothing and costs a table scan, because the row has to be found by hash
    -- rather than by trying every candidate.
    token_hash   text        NOT NULL UNIQUE,

    -- Separate from expires_at because they answer different questions.
    -- expires_at decides whether a link still works; requested_at is what the
    -- rate limit reads, and the window is much shorter than the lifetime.
    requested_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,

    -- Set when the token is spent. The row is kept rather than deleted so a
    -- second presentation is rejected rather than treated as unknown, which
    -- makes a double-clicked link behave predictably.
    used_at      timestamptz,

    CONSTRAINT reset_expires_after_request CHECK (expires_at > requested_at)
);

-- The rate limit asks "has this user requested one recently", which is this
-- index. It also serves the cleanup of old rows.
CREATE INDEX password_reset_by_user ON password_reset_token (user_id, requested_at DESC);