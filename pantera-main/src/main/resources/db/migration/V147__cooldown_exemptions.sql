-- V147: per-repository cooldown exemptions.
--
-- A row exempts the packages (or docker images) whose name matches `pattern`
-- in repository `repo_name` from cooldown: their versions are never blocked
-- and never filtered out of metadata. `pattern` is either an exact package
-- name, or a prefix followed by a single trailing `*` (`@auto1/*`,
-- `com.auto1.*`, `library/nginx*`), written as the package appears in the
-- cooldown blocked list. `expires_at` NULL means the exemption never expires.
--
-- Rows are loaded into memory at startup, replaced per repository by
-- PUT /api/v1/repositories/:name/cooldown/exemptions and re-read every minute;
-- requests never read this table. The foreign key drops a repository's rows
-- when it is deleted and follows it when it is renamed.

CREATE TABLE IF NOT EXISTS cooldown_exemptions (
    id          BIGSERIAL    PRIMARY KEY,
    repo_name   VARCHAR(255) NOT NULL
                REFERENCES repositories (name) ON DELETE CASCADE ON UPDATE CASCADE,
    pattern     VARCHAR(512) NOT NULL,
    expires_at  TIMESTAMPTZ,
    reason      TEXT,
    created_by  VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (repo_name, pattern)
);
