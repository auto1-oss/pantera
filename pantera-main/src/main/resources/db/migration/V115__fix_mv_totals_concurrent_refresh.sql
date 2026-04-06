-- V115: Fix REFRESH MATERIALIZED VIEW CONCURRENTLY on mv_artifact_totals
--
-- V110 created the single-row MV with a unique index on the expression
-- ((1)). PostgreSQL rejects expression indexes for CONCURRENTLY refresh:
--
--   ERROR: cannot refresh materialized view "public.mv_artifact_totals"
--          concurrently
--   HINT:  Create a unique index with no WHERE clause on one or more
--          columns of the materialized view.
--
-- The index must be on ACTUAL columns with no WHERE clause and no
-- expressions. For a single-row MV that means adding a synthetic "id"
-- column to the MV definition and indexing on it.
--
-- MVs cannot be ALTERed to add columns — we must DROP and recreate.

DROP MATERIALIZED VIEW IF EXISTS mv_artifact_totals;

CREATE MATERIALIZED VIEW mv_artifact_totals AS
    SELECT 1::int AS id,
           COUNT(*) AS artifact_count,
           COALESCE(SUM(size), 0) AS total_size
    FROM artifacts;

-- Unique index on the synthetic id column — satisfies CONCURRENTLY refresh
CREATE UNIQUE INDEX uq_mv_artifact_totals ON mv_artifact_totals(id);
