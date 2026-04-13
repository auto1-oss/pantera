-- V117: Repair BIGSERIAL sequences after bulk imports
--
-- The backfill tool (BatchInserter) inserts rows with explicit IDs,
-- which does NOT advance the associated sequence. After a backfill,
-- the sequence can generate IDs that collide with existing rows,
-- causing "duplicate key violates unique constraint artifacts_pkey"
-- and poisoning the entire DbConsumer batch transaction.
--
-- This migration resets all BIGSERIAL sequences to MAX(id)+1,
-- which is idempotent and safe to run on any deployment.

SELECT setval('artifacts_id_seq',
    COALESCE((SELECT MAX(id) FROM artifacts), 0) + 1, false);

SELECT setval('artifact_cooldowns_id_seq',
    COALESCE((SELECT MAX(id) FROM artifact_cooldowns), 0) + 1, false);

SELECT setval('import_sessions_id_seq',
    COALESCE((SELECT MAX(id) FROM import_sessions), 0) + 1, false);
