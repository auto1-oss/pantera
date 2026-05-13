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
    path_prefix  VARCHAR
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
