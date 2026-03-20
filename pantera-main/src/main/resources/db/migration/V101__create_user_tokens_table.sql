-- V101__create_user_tokens_table.sql
-- API tokens issued to users, with expiry tracking and revocation support

CREATE TABLE IF NOT EXISTS user_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username    VARCHAR(255) NOT NULL,
    label       VARCHAR(255) NOT NULL DEFAULT 'API Token',
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked     BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_user_tokens_username ON user_tokens (username);
CREATE INDEX IF NOT EXISTS idx_user_tokens_revoked ON user_tokens (revoked) WHERE revoked = FALSE;
