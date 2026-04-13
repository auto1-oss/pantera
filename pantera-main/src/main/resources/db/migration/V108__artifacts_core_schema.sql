-- V108: Core artifacts schema
-- Moves ArtifactDbFactory.createStructure() DDL into Flyway for reliable
-- deployment on fresh databases and during CI/local resets.
-- All statements are idempotent (IF NOT EXISTS / ADD COLUMN IF NOT EXISTS).

-- Main artifacts index table
CREATE TABLE IF NOT EXISTS artifacts (
    id            BIGSERIAL PRIMARY KEY,
    repo_type     VARCHAR NOT NULL,
    repo_name     VARCHAR NOT NULL,
    name          VARCHAR NOT NULL,
    version       VARCHAR NOT NULL,
    size          BIGINT NOT NULL,
    created_date  BIGINT NOT NULL,
    release_date  BIGINT,
    owner         VARCHAR NOT NULL,
    UNIQUE (repo_name, name, version)
);

-- Backward compatibility: columns added after initial release
ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS release_date BIGINT;
ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS path_prefix  VARCHAR;

-- Base lookup indexes
CREATE INDEX IF NOT EXISTS idx_artifacts_repo_lookup
    ON artifacts(repo_name, name, version);
CREATE INDEX IF NOT EXISTS idx_artifacts_repo_type_name
    ON artifacts(repo_type, repo_name, name);
CREATE INDEX IF NOT EXISTS idx_artifacts_created_date
    ON artifacts(created_date);
CREATE INDEX IF NOT EXISTS idx_artifacts_owner
    ON artifacts(owner);
CREATE INDEX IF NOT EXISTS idx_artifacts_release_date
    ON artifacts(release_date) WHERE release_date IS NOT NULL;

-- Covering index for locate() — enables index-only scan
CREATE INDEX IF NOT EXISTS idx_artifacts_locate
    ON artifacts(name, repo_name) INCLUDE (repo_type);

-- Covering index for browse operations
CREATE INDEX IF NOT EXISTS idx_artifacts_browse
    ON artifacts(repo_name, name, version)
    INCLUDE (size, created_date, owner);

-- Partial index for path-prefix based group resolution
CREATE INDEX IF NOT EXISTS idx_artifacts_path_prefix
    ON artifacts(path_prefix, repo_name) WHERE path_prefix IS NOT NULL;

-- Artifact cooldowns: block list for suspicious/quarantined artifacts
CREATE TABLE IF NOT EXISTS artifact_cooldowns (
    id              BIGSERIAL PRIMARY KEY,
    repo_type       VARCHAR NOT NULL,
    repo_name       VARCHAR NOT NULL,
    artifact        VARCHAR NOT NULL,
    version         VARCHAR NOT NULL,
    reason          VARCHAR NOT NULL,
    status          VARCHAR NOT NULL,
    blocked_by      VARCHAR NOT NULL,
    blocked_at      BIGINT NOT NULL,
    blocked_until   BIGINT NOT NULL,
    unblocked_at    BIGINT,
    unblocked_by    VARCHAR,
    installed_by    VARCHAR,
    CONSTRAINT cooldown_artifact_unique UNIQUE (repo_name, artifact, version)
);
ALTER TABLE artifact_cooldowns ADD COLUMN IF NOT EXISTS installed_by VARCHAR;

CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_artifact
    ON artifact_cooldowns(repo_name, artifact, version);
CREATE INDEX IF NOT EXISTS idx_cooldowns_status
    ON artifact_cooldowns(status);
CREATE INDEX IF NOT EXISTS idx_cooldowns_status_blocked_at
    ON artifact_cooldowns(status, blocked_at DESC);
CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_status
    ON artifact_cooldowns(repo_type, repo_name, status);
CREATE INDEX IF NOT EXISTS idx_cooldowns_status_artifact
    ON artifact_cooldowns(status, artifact, repo_name);
CREATE INDEX IF NOT EXISTS idx_cooldowns_status_blocked_until
    ON artifact_cooldowns(status, blocked_until) WHERE status = 'ACTIVE';

-- Import sessions: idempotency and progress tracking for bulk imports
CREATE TABLE IF NOT EXISTS import_sessions (
    id                BIGSERIAL PRIMARY KEY,
    idempotency_key   VARCHAR(1000) NOT NULL UNIQUE,
    repo_name         VARCHAR NOT NULL,
    repo_type         VARCHAR NOT NULL,
    artifact_path     TEXT NOT NULL,
    artifact_name     VARCHAR,
    artifact_version  VARCHAR,
    size_bytes        BIGINT,
    checksum_sha1     VARCHAR(128),
    checksum_sha256   VARCHAR(128),
    checksum_md5      VARCHAR(128),
    checksum_policy   VARCHAR(16) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    attempt_count     INTEGER NOT NULL DEFAULT 1,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,
    completed_at      TIMESTAMP,
    last_error        TEXT,
    quarantine_path   TEXT
);

CREATE INDEX IF NOT EXISTS idx_import_sessions_repo
    ON import_sessions(repo_name);
CREATE INDEX IF NOT EXISTS idx_import_sessions_status
    ON import_sessions(status);
CREATE INDEX IF NOT EXISTS idx_import_sessions_repo_path
    ON import_sessions(repo_name, artifact_path);
