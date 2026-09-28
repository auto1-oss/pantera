-- V144: durable manual cooldown unblock.
--
-- A manual unblock used to archive the block to history and DELETE the live
-- row. Once the in-memory "allowed" decision aged out, the next evaluation
-- found no row, saw a release date still inside the cooldown window, and
-- re-created the block — silently undoing the unblock (re-blocked every
-- ~30 minutes in production).
--
-- From 2.2.9 a manual unblock still writes the MANUAL_UNBLOCK history row,
-- but keeps the live row with status = 'INACTIVE' (plus unblocked_at /
-- unblocked_by and the original blocked_until). Evaluation treats a non-ACTIVE
-- row as allowed, and every active-list / count / cleanup query already
-- filters on status = 'ACTIVE', so the row is invisible everywhere else.
--
-- This migration schedules the job that removes INACTIVE rows once their
-- window has ended. They were archived at release time, so it is a plain
-- DELETE (no second history row). The status column is now load-bearing and
-- must not be dropped (V121 anticipated dropping it).
--
-- Without pg_cron the Vert.x CooldownCleanupFallback runs the same purge
-- (CooldownRepository.purgeReleasedBatch) every 10 minutes.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'pg_cron'
    ) THEN
        RAISE NOTICE
            'pg_cron not installed — V144 skips scheduling. '
            'CooldownCleanupFallback (Vertx) purges released rows at runtime.';
        RETURN;
    END IF;

    PERFORM cron.unschedule(jobid) FROM cron.job
        WHERE jobname = 'purge-released-cooldowns';

    PERFORM cron.schedule(
        'purge-released-cooldowns',
        '*/10 * * * *',
        $cron$
            WITH victims AS (
                SELECT id FROM artifact_cooldowns
                WHERE status = 'INACTIVE'
                  AND blocked_until < EXTRACT(EPOCH FROM NOW())::bigint * 1000
                ORDER BY blocked_until
                LIMIT _cooldown_batch_limit()
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM artifact_cooldowns c
            USING victims v
            WHERE c.id = v.id;
        $cron$
    );
END $$;
