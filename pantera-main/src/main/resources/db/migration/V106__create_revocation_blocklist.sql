CREATE TABLE IF NOT EXISTS revocation_blocklist (
    id          BIGSERIAL PRIMARY KEY,
    entry_type  VARCHAR(10) NOT NULL,
    entry_value VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_revocation_expires
    ON revocation_blocklist (expires_at);

CREATE INDEX IF NOT EXISTS idx_revocation_lookup
    ON revocation_blocklist (entry_type, entry_value, expires_at);

COMMENT ON TABLE revocation_blocklist IS 'Access token revocation entries for DB-polling fallback mode';
COMMENT ON COLUMN revocation_blocklist.entry_type IS 'jti or username';
COMMENT ON COLUMN revocation_blocklist.entry_value IS 'Token UUID or username string';
