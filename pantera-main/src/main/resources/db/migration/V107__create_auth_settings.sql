CREATE TABLE IF NOT EXISTS auth_settings (
    key         VARCHAR(100) PRIMARY KEY,
    value       VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO auth_settings (key, value) VALUES
    ('access_token_ttl_seconds', '3600'),
    ('refresh_token_ttl_seconds', '604800'),
    ('api_token_max_ttl_seconds', '7776000'),
    ('api_token_allow_permanent', 'true')
ON CONFLICT (key) DO NOTHING;

COMMENT ON TABLE auth_settings IS 'UI-configurable authentication policy settings';
