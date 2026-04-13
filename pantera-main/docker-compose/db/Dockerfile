# PostgreSQL with pg_cron extension for scheduled maintenance jobs.
#
# Used by Pantera for:
#   - Periodic refresh of mv_artifact_totals / mv_artifact_per_repo
#   - Expired-cooldown cleanup
#   - Future scheduled DB tasks
#
# Alpine doesn't ship pg_cron in its package repos, so we use the
# Debian-based postgres:17 image instead. The pg_cron version must
# match the major PostgreSQL version (postgresql-17-cron for PG 17).
FROM postgres:17

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-17-cron \
    && rm -rf /var/lib/apt/lists/*
