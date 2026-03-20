-- V100__create_settings_tables.sql
-- Settings layer tables for Artipie Web UI
-- Uses V100 to avoid numbering conflicts with potential artifact table migrations

CREATE TABLE IF NOT EXISTS repositories (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    type        VARCHAR(50) NOT NULL,
    config      JSONB NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_repositories_type ON repositories (type);
CREATE INDEX IF NOT EXISTS idx_repositories_enabled ON repositories (enabled);

CREATE TABLE IF NOT EXISTS users (
    id              SERIAL PRIMARY KEY,
    username        VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255),
    email           VARCHAR(255),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    auth_provider   VARCHAR(50) NOT NULL DEFAULT 'artipie',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_enabled ON users (enabled);
CREATE INDEX IF NOT EXISTS idx_users_auth_provider ON users (auth_provider);

CREATE TABLE IF NOT EXISTS roles (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    permissions JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id INT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS storage_aliases (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    repo_name   VARCHAR(255),
    config      JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (name, repo_name)
);

-- Partial unique index for global aliases (repo_name IS NULL).
-- PostgreSQL UNIQUE constraints treat NULLs as distinct, so without this
-- two rows ('default', NULL) would both be allowed.
CREATE UNIQUE INDEX IF NOT EXISTS idx_storage_aliases_global_unique
    ON storage_aliases (name) WHERE repo_name IS NULL;

CREATE INDEX IF NOT EXISTS idx_storage_aliases_repo ON storage_aliases (repo_name);

CREATE TABLE IF NOT EXISTS settings (
    key         VARCHAR(255) PRIMARY KEY,
    value       JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS auth_providers (
    id       SERIAL PRIMARY KEY,
    type     VARCHAR(50) NOT NULL UNIQUE,
    priority INT NOT NULL DEFAULT 0,
    config   JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor           VARCHAR(255),
    action          VARCHAR(50) NOT NULL,
    resource_type   VARCHAR(50) NOT NULL,
    resource_name   VARCHAR(255),
    old_value       JSONB,
    new_value       JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_resource ON audit_log (resource_type, resource_name);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor ON audit_log (actor);
