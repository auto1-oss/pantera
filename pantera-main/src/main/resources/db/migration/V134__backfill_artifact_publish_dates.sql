-- V134__backfill_artifact_publish_dates.sql
-- Backfill artifact_publish_dates from existing artifacts.release_date so the
-- cooldown subsystem has dates for already-cached Maven/Gradle/etc artifacts
-- without waiting for a re-fetch. ON CONFLICT DO NOTHING preserves canonical
-- registry-sourced rows when they already exist.
INSERT INTO artifact_publish_dates (repo_type, name, version, published_at, source)
SELECT repo_type, name, version, TO_TIMESTAMP(release_date / 1000.0), 'backfill_from_artifacts'
FROM artifacts
WHERE release_date IS NOT NULL
  AND repo_type IN (
      'maven','maven-proxy','gradle','gradle-proxy',
      'npm','npm-proxy','pypi','pypi-proxy',
      'composer','composer-proxy','php','php-proxy',
      'gem','gem-proxy'
  )
ON CONFLICT (repo_type, name, version) DO NOTHING;
