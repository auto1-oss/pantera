-- V110: Dashboard aggregate materialized views
-- Sub-millisecond reads vs seq scans on the artifacts table.
-- Refreshed externally by pg_cron; see docs/admin-guide/installation.md.

-- Covering index for dashboard top-repos query — turns GROUP BY seq scan
-- into an index-only aggregation. Required for CONCURRENTLY refresh.
CREATE INDEX IF NOT EXISTS idx_artifacts_repo_size_cover
    ON artifacts(repo_name, repo_type) INCLUDE (size);

-- Single-row totals view
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_artifact_totals AS
    SELECT COUNT(*) AS artifact_count,
           COALESCE(SUM(size), 0) AS total_size
    FROM artifacts;

-- Single-row MV: unique index on constant expression — required for CONCURRENTLY.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_artifact_totals
    ON mv_artifact_totals ((1));

-- Per-repo aggregate view
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_artifact_per_repo AS
    SELECT repo_name, repo_type,
           COUNT(*) AS artifact_count,
           COALESCE(SUM(size), 0) AS total_size
    FROM artifacts
    GROUP BY repo_name, repo_type;

CREATE UNIQUE INDEX IF NOT EXISTS uq_mv_artifact_per_repo
    ON mv_artifact_per_repo(repo_name, repo_type);

CREATE INDEX IF NOT EXISTS idx_mv_artifact_per_repo_size
    ON mv_artifact_per_repo(total_size DESC, artifact_count DESC);
