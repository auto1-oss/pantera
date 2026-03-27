-- V104: Performance indexes identified by full-stack audit
-- Note: artifacts table is created programmatically by ArtifactDbFactory,
-- so artifact indexes are added there. This migration covers settings tables only.

-- Enable pg_trgm extension for trigram-based fuzzy search (used by ArtifactDbFactory)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Functional index on repositories JSONB path for storage alias lookups
-- Accelerates: SELECT name FROM repositories WHERE config->'repo'->>'storage' = ?
CREATE INDEX IF NOT EXISTS idx_repositories_storage_alias
    ON repositories ((config -> 'repo' ->> 'storage'));

-- Composite index on users for auth provider filtering
-- Accelerates: SELECT ... FROM users WHERE enabled = ? AND auth_provider = ?
CREATE INDEX IF NOT EXISTS idx_users_enabled_provider
    ON users (enabled, auth_provider);
