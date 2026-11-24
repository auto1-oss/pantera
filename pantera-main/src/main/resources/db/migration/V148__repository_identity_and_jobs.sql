-- V148: repository identity, deleted repositories, repository jobs.
--
-- repo_key
--   A repository keeps one immutable key for its whole life. The key is the
--   directory (prefix) of its data in its storage and the value stored in
--   artifacts.repo_name. A rename changes repositories.name only: no object
--   is copied and no index row is rewritten. Every existing repository gets
--   repo_key = name, so nothing moves. A new repository takes its name as
--   key when that key is free, otherwise "<name>~<n>" ('~' is not allowed in
--   a repository name, so a key never collides with a later name).
--
-- repository_tombstones
--   A deleted repository leaves `repositories` at once (its name is free)
--   and is kept here with its config and the resolved config of its storage
--   until its data is purged. `purge_after` is the end of the restore window.
--   The tombstone holds the key: a new repository cannot take it before the
--   purge has finished, so a re-created name always starts empty.
--
-- repository_aliases
--   Former names. `routable` aliases keep serving requests for the old name
--   until `expires_at` (the "keep the old name working" option of a rename);
--   the others only translate artifact events that were queued under the old
--   name while the rename committed.
--
-- repo_jobs
--   Durable background work on a repository: purge of a deleted repository's
--   objects and rows, purge of an orphaned storage directory, follow-up of a
--   rename. A node claims a job with a lease (lease_owner / lease_until) and
--   renews it while it works; a job whose lease ran out is taken over by
--   another node and resumes from `checkpoint`.

ALTER TABLE repositories ADD COLUMN IF NOT EXISTS repo_key VARCHAR(255);
UPDATE repositories SET repo_key = name WHERE repo_key IS NULL;
ALTER TABLE repositories ALTER COLUMN repo_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_repositories_repo_key ON repositories (repo_key);

CREATE TABLE IF NOT EXISTS repository_tombstones (
    repo_key        VARCHAR(255) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    type            VARCHAR(50)  NOT NULL,
    config          JSONB        NOT NULL,
    storage_config  JSONB,
    extras          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    deleted_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_by      VARCHAR(255),
    purge_after     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS repository_aliases (
    alias       VARCHAR(255) PRIMARY KEY,
    repo_key    VARCHAR(255) NOT NULL,
    routable    BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_repository_aliases_key ON repository_aliases (repo_key);

CREATE TABLE IF NOT EXISTS repo_jobs (
    id              BIGSERIAL    PRIMARY KEY,
    type            VARCHAR(32)  NOT NULL,
    repo_key        VARCHAR(255) NOT NULL,
    repo_name       VARCHAR(255) NOT NULL,
    state           VARCHAR(16)  NOT NULL DEFAULT 'pending',
    phase           VARCHAR(32)  NOT NULL DEFAULT 'waiting',
    params          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    checkpoint      TEXT,
    objects_done    BIGINT       NOT NULL DEFAULT 0,
    rows_done       BIGINT       NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    not_before      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    lease_owner     VARCHAR(255),
    lease_until     TIMESTAMPTZ,
    trace_id        VARCHAR(64),
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMPTZ,
    CONSTRAINT repo_jobs_state_check
        CHECK (state IN ('pending', 'running', 'failed', 'done', 'cancelled')),
    CONSTRAINT repo_jobs_type_check
        CHECK (type IN ('purge', 'orphan_purge', 'rename_followup'))
);
CREATE INDEX IF NOT EXISTS idx_repo_jobs_claim
    ON repo_jobs (not_before) WHERE state IN ('pending', 'running');
CREATE INDEX IF NOT EXISTS idx_repo_jobs_key ON repo_jobs (repo_key);
-- One live purge per key: a second delete or orphan purge of the same
-- directory is refused by the database, whichever node receives it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_repo_jobs_live_purge
    ON repo_jobs (repo_key)
    WHERE state IN ('pending', 'running', 'failed') AND type IN ('purge', 'orphan_purge');

-- The key of a new repository is allocated here, so every writer of
-- `repositories` (API, YAML import, scripts) gets it right: the name when
-- no repository and no tombstone holds that key, otherwise "<name>~<n>".
-- A writer may also set repo_key itself (the API does when the storage
-- already holds leftover data under the name).
CREATE OR REPLACE FUNCTION repositories_assign_key() RETURNS trigger AS $fn$
DECLARE
    candidate VARCHAR(255);
    suffix    INT := 1;
BEGIN
    IF NEW.repo_key IS NOT NULL THEN
        RETURN NEW;
    END IF;
    candidate := NEW.name;
    WHILE EXISTS (SELECT 1 FROM repositories WHERE repo_key = candidate)
        OR EXISTS (SELECT 1 FROM repository_tombstones WHERE repo_key = candidate)
    LOOP
        suffix := suffix + 1;
        candidate := NEW.name || '~' || suffix;
    END LOOP;
    NEW.repo_key := candidate;
    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_repositories_assign_key ON repositories;
CREATE TRIGGER trg_repositories_assign_key
    BEFORE INSERT ON repositories
    FOR EACH ROW EXECUTE FUNCTION repositories_assign_key();
