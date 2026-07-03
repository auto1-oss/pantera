--
-- Copyright (c) 2025-2026 Auto1 Group
-- Maintainers: Auto1 DevOps Team
-- Lead Maintainer: Ayd Asraf
--
-- This program is free software: you can redistribute it and/or modify
-- it under the terms of the GNU General Public License v3.0.
--
-- Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
--

-- Scaling-benchmark fixture: 100 000 artifact rows across 5 repos.
--
-- Schema MUST match what Pantera's ArtifactDbFactory.createStructure()
-- creates on boot (pantera-main/src/main/java/com/auto1/pantera/db/ArtifactDbFactory.java:339+).
-- Creating the table here with the exact same shape means Pantera's
-- `CREATE TABLE IF NOT EXISTS` + `ADD COLUMN IF NOT EXISTS` become no-ops,
-- and our INSERTs land cleanly.
--
-- Size buckets (per pkgId mod 10): 0-6 → 100 KB, 7-8 → 1 MB, 9 → 10 MB.
-- Dates are epoch millis (BIGINT), matching Pantera's convention.

\c pantera

CREATE TABLE IF NOT EXISTS artifacts(
    id           BIGSERIAL PRIMARY KEY,
    repo_type    VARCHAR      NOT NULL,
    repo_name    VARCHAR      NOT NULL,
    name         VARCHAR      NOT NULL,
    version      VARCHAR      NOT NULL,
    size         BIGINT       NOT NULL,
    created_date BIGINT       NOT NULL,
    release_date BIGINT,
    owner        VARCHAR      NOT NULL,
    path_prefix  VARCHAR,
    UNIQUE (repo_name, name, version)
);

-- 5 repos × 20 000 artifacts × 1 version = 100 000 rows.
-- Package names match the k6 payload pool (pkg-00000 … pkg-19999, cycled per repo).
INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date, release_date, owner, path_prefix)
SELECT
    'npm',
    'local-repo-' || (((n - 1) / 20000) + 1)::text,
    'pkg-' || lpad(((n - 1) % 20000)::text, 5, '0'),
    '1.0.0',
    CASE (n % 10)
        WHEN 7 THEN 1048576       -- 1 MB bucket (10 %)
        WHEN 8 THEN 1048576       -- 1 MB bucket (10 %)
        WHEN 9 THEN 10485760      -- 10 MB bucket (10 %)
        ELSE    102400            -- 100 KB bucket (70 %)
    END,
    (extract(epoch from now()) * 1000)::bigint,
    (extract(epoch from now()) * 1000)::bigint,
    'bench',
    NULL
FROM generate_series(1, 100000) AS n;

ANALYZE artifacts;

-- ---------------------------------------------------------------------------
-- Bench principal for the k6 load (basic auth bench/benchpass — the password
-- half lives in AuthFromEnv via PANTERA_USER_NAME/PANTERA_USER_PASS on the
-- pantera-sut service; this block only provides the POLICY side).
--
-- CachedDbPolicy resolves users → user_roles → roles.permissions from these
-- tables. Shapes MUST match V100__create_settings_tables.sql so Flyway's
-- CREATE TABLE IF NOT EXISTS becomes a no-op on boot.
-- ---------------------------------------------------------------------------
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

INSERT INTO users (username, enabled)
VALUES ('bench', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO roles (name, permissions, enabled)
VALUES ('bench', '{"adapter_basic_permissions": {"*": ["read", "write"]}}'::jsonb, TRUE)
ON CONFLICT (name) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'bench' AND r.name = 'bench'
ON CONFLICT DO NOTHING;
