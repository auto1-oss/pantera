-- V121: Cooldown history table + live-tunable batched pg_cron jobs
--
-- ADDITIVE-ONLY migration (no column drops).
--
-- Changes:
--   1. artifact_cooldowns_history table (append-only archive)
--   2. Postgres helper functions _cooldown_batch_limit() / _cooldown_retention_days()
--      that read from the settings JSONB blob at job-run time, so admin UI
--      changes take effect without restart.
--   3. Trigram index on artifact_cooldowns.artifact for fast ILIKE search.
--   4. pg_cron job 'cleanup-expired-cooldowns' is replaced with an
--      archive-then-delete version using a LIMIT from the helper function.
--   5. New pg_cron job 'purge-cooldown-history' (daily 03:00 UTC).
--
-- NOT done in V121 (deferred to a later migration after Task 4 ships):
--   - DROP COLUMN artifact_cooldowns.status (still read pervasively by
--     CooldownRepository and JdbcCooldownService; removing breaks runtime).
--   - DROP the status-based indexes (idx_cooldowns_status,
--     idx_cooldowns_status_blocked_at, idx_cooldowns_repo_status,
--     idx_cooldowns_status_artifact, idx_cooldowns_status_blocked_until).
--   - Re-scheduling the cron job WITHOUT the status='ACTIVE' predicate.
--     That must happen in the same migration that drops the column,
--     or V121's cron DELETE will hit a non-existent column.

-- ---------------------------------------------------------------------------
-- Part A: Additive schema — history table + new indexes
-- ---------------------------------------------------------------------------

-- History table: append-only record of expired/unblocked entries.
CREATE TABLE IF NOT EXISTS artifact_cooldowns_history (
    id              BIGSERIAL PRIMARY KEY,
    original_id     BIGINT NOT NULL,
    repo_type       VARCHAR NOT NULL,
    repo_name       VARCHAR NOT NULL,
    artifact        VARCHAR NOT NULL,
    version         VARCHAR NOT NULL,
    reason          VARCHAR NOT NULL,
    blocked_by      VARCHAR NOT NULL,
    blocked_at      BIGINT NOT NULL,
    blocked_until   BIGINT NOT NULL,
    installed_by    VARCHAR,
    archived_at     BIGINT NOT NULL,
    archive_reason  VARCHAR NOT NULL,
    archived_by     VARCHAR NOT NULL
);

-- Intentionally no UNIQUE constraint on (repo_name, artifact, version) —
-- the same artifact may legitimately appear in history multiple times over
-- its lifecycle (blocked → unblocked → re-blocked → expired → ...).

CREATE INDEX IF NOT EXISTS idx_cooldowns_history_archived_at
    ON artifact_cooldowns_history(archived_at);
CREATE INDEX IF NOT EXISTS idx_cooldowns_history_repo_artifact
    ON artifact_cooldowns_history(repo_name, artifact, version);
CREATE INDEX IF NOT EXISTS idx_cooldowns_history_archive_reason
    ON artifact_cooldowns_history(archive_reason);

-- New search/lookup indexes on artifact_cooldowns (additive).
-- pg_trgm is already installed by V104, so the trigram index is unconditional.
CREATE INDEX IF NOT EXISTS idx_cooldowns_blocked_until
    ON artifact_cooldowns(blocked_until);
CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_name
    ON artifact_cooldowns(repo_name);
CREATE INDEX IF NOT EXISTS idx_cooldowns_repo_type
    ON artifact_cooldowns(repo_type);
CREATE INDEX IF NOT EXISTS idx_cooldowns_artifact_trgm
    ON artifact_cooldowns USING gin (artifact gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Part B: Settings-reader helper functions (unconditional)
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION _cooldown_batch_limit() RETURNS integer AS $fn$
    SELECT GREATEST(1, LEAST(100000, COALESCE(
        NULLIF(value->>'cleanup_batch_limit', '')::integer,
        10000
    )))
    FROM settings
    WHERE key = 'cooldown'
    UNION ALL
    SELECT 10000
    LIMIT 1;
$fn$ LANGUAGE sql STABLE;

CREATE OR REPLACE FUNCTION _cooldown_retention_days() RETURNS integer AS $fn$
    SELECT GREATEST(1, COALESCE(
        NULLIF(value->>'history_retention_days', '')::integer,
        90
    ))
    FROM settings
    WHERE key = 'cooldown'
    UNION ALL
    SELECT 90
    LIMIT 1;
$fn$ LANGUAGE sql STABLE;

-- ---------------------------------------------------------------------------
-- Part C: pg_cron replacement (conditional)
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_available_extensions WHERE name = 'pg_cron'
    ) THEN
        RAISE NOTICE
            'pg_cron unavailable — V121 will skip cron updates. '
            'CooldownCleanupFallback (Vertx) will handle cleanup at runtime.';
        RETURN;
    END IF;
    CREATE EXTENSION IF NOT EXISTS pg_cron;

    -- Unschedule V114's cleanup job (hard-delete variant) so we can replace it.
    PERFORM cron.unschedule(jobid) FROM cron.job
        WHERE jobname IN ('cleanup-expired-cooldowns', 'purge-cooldown-history');

    -- Cleanup: archive-then-delete, batched via _cooldown_batch_limit().
    -- Keeps status='ACTIVE' predicate because the column still exists in V121.
    -- The later migration that drops the column must re-register this job
    -- without the status predicate.
    PERFORM cron.schedule(
        'cleanup-expired-cooldowns',
        '*/10 * * * *',
        $cron$
            WITH victims AS (
                SELECT id FROM artifact_cooldowns
                WHERE status = 'ACTIVE'
                  AND blocked_until < EXTRACT(EPOCH FROM NOW()) * 1000
                ORDER BY blocked_until
                LIMIT _cooldown_batch_limit()
                FOR UPDATE SKIP LOCKED
            ),
            archived AS (
                INSERT INTO artifact_cooldowns_history (
                    original_id, repo_type, repo_name, artifact, version,
                    reason, blocked_by, blocked_at, blocked_until,
                    installed_by, archived_at, archive_reason, archived_by
                )
                SELECT c.id, c.repo_type, c.repo_name, c.artifact, c.version,
                       c.reason, c.blocked_by, c.blocked_at, c.blocked_until,
                       c.installed_by,
                       EXTRACT(EPOCH FROM NOW())::bigint * 1000,
                       'EXPIRED', 'system'
                FROM artifact_cooldowns c
                JOIN victims v ON v.id = c.id
                RETURNING original_id
            )
            DELETE FROM artifact_cooldowns c
            USING archived a
            WHERE c.id = a.original_id;
        $cron$
    );

    -- Daily history purge at 03:00 UTC.
    PERFORM cron.schedule(
        'purge-cooldown-history',
        '0 3 * * *',
        $cron$
            WITH victims AS (
                SELECT id FROM artifact_cooldowns_history
                WHERE archived_at <
                      EXTRACT(EPOCH FROM NOW() -
                          (_cooldown_retention_days() || ' days')::interval
                      )::bigint * 1000
                ORDER BY archived_at
                LIMIT _cooldown_batch_limit() * 5
            )
            DELETE FROM artifact_cooldowns_history h
            USING victims v
            WHERE h.id = v.id;
        $cron$
    );
END $$;
