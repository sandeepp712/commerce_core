-- V4__refresh_tokens.sql
CREATE TABLE refresh_tokens (
    id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 of the raw token. Never store the raw token.
    token_hash  TEXT NOT NULL,

    -- Every rotation chain shares a family_id. Reuse detection revokes the family.
    family_id   UUID NOT NULL,

    -- Context for "log out this device" UI. Optional but useful.
    device_info TEXT,
    ip_address  TEXT,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID REFERENCES refresh_tokens(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user   ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;