-- V114: pg_cron extension + scheduled maintenance jobs
--
-- Requires the postgres container to load pg_cron via:
--   shared_preload_libraries = 'pg_cron'
--   cron.database_name = 'pantera'
-- (configured in docker-compose.yaml command: directive)
--
-- Without those flags this migration will fail with
-- "extension pg_cron is not available". Skip via try/catch in code paths
-- that may run on databases without pg_cron installed.

-- Run inside a DO block so environments WITHOUT pg_cron (e.g. test
-- containers, dev installs) skip gracefully instead of failing the
-- migration. The block checks pg_available_extensions before doing
-- anything; if pg_cron is not installed it logs a NOTICE and exits.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_available_extensions WHERE name = 'pg_cron'
    ) THEN
        RAISE NOTICE
            'pg_cron extension not available — skipping V114 scheduled jobs. '
            'Install postgresql-N-cron and configure shared_preload_libraries '
            'to enable scheduled materialized view refresh and cooldown cleanup.';
        RETURN;
    END IF;

    -- Create the extension (idempotent)
    CREATE EXTENSION IF NOT EXISTS pg_cron;

    -- Remove any pre-existing entries for our jobs (idempotent re-run).
    -- Includes both current job names and the earlier "pantera-" prefixed
    -- variants to clean up any installs that ran the previous migration.
    PERFORM cron.unschedule(jobid)
        FROM cron.job
        WHERE jobname IN (
            'refresh-mv-artifact-totals',
            'refresh-mv-artifact-per-repo',
            'cleanup-expired-cooldowns',
            'pantera-refresh-mv-totals',
            'pantera-refresh-mv-per-repo',
            'pantera-cleanup-expired-cooldowns'
        );

    -- Refresh the dashboard totals MV every 30 minutes.
    -- CONCURRENTLY uses SHARE UPDATE EXCLUSIVE on the MV only (not on the
    -- artifacts table) so dashboard reads and writes proceed normally.
    PERFORM cron.schedule(
        'refresh-mv-artifact-totals',
        '*/30 * * * *',
        $cron$REFRESH MATERIALIZED VIEW CONCURRENTLY mv_artifact_totals$cron$
    );

    -- Refresh per-repo MV every 30 minutes. Heavier than totals because
    -- of GROUP BY (uses idx_artifacts_repo_size_cover covering index for
    -- index-only scan).
    PERFORM cron.schedule(
        'refresh-mv-artifact-per-repo',
        '*/30 * * * *',
        $cron$REFRESH MATERIALIZED VIEW CONCURRENTLY mv_artifact_per_repo$cron$
    );

    -- Delete expired cooldown blocks every 10 minutes.
    -- Hard delete (not status=INACTIVE) keeps the table small over time.
    -- Uses the partial index idx_cooldowns_status_blocked_until.
    PERFORM cron.schedule(
        'cleanup-expired-cooldowns',
        '*/10 * * * *',
        $cron$
            DELETE FROM artifact_cooldowns
            WHERE status = 'ACTIVE'
              AND blocked_until < EXTRACT(EPOCH FROM NOW()) * 1000
        $cron$
    );
END $$;
