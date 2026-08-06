-- V137__client_base_url_settings.sql
-- Seed the client-facing base URL derivation settings into the
-- auth_settings key/value store. Both keys are consumed by
-- ClientBaseUrlSettingsLoader (pantera-main) via ClientBaseUrlSettingsRegistry
-- into pantera-core's ClientBaseUrl, which builds the absolute URLs Pantera
-- emits (e.g. npm dist.tarball) from the inbound request when a repository
-- has no explicit `url:` configured.
--
--   trust_forwarded_headers     = 'false'
--     Replaces the pre-2.3.0 env-only PANTERA_TRUST_FORWARDED_HEADERS flag.
--     When 'true', X-Forwarded-Proto/-Host/-Prefix are honoured (only safe
--     when a fronting reverse proxy overwrites them on every inbound
--     request). The key name deliberately matches the legacy env var
--     (PANTERA_TRUST_FORWARDED_HEADERS via ENV_PREFIX + key.toUpperCase()),
--     so an existing deployment's env setting keeps working as the fallback
--     tier under this DB row.
--
--   client_base_host_allowlist  = ''
--     Comma-separated Host header values permitted to be used when deriving
--     a base URL. EMPTY IS PERMISSIVE: any Host is honoured -- matches
--     Pantera's behaviour before this allowlist existed, so upgrading an
--     existing deployment never breaks it. A non-empty list rejects a
--     non-matching Host (falls through exactly as an absent Host would,
--     never emitting the rejected value). The loader logs a startup WARN
--     while this stays empty -- see VertxMain.
--
-- The in-memory loader (ClientBaseUrlSettingsLoader) falls back to the
-- hardcoded defaults when rows are absent, so this migration is about
-- making the values explicit and admin-editable, not about correctness.

INSERT INTO auth_settings (key, value) VALUES
    ('trust_forwarded_headers', 'false'),
    ('client_base_host_allowlist', '')
ON CONFLICT (key) DO NOTHING;
