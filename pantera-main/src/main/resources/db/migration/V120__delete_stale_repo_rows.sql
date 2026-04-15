-- Remove DB index rows for repos that were renamed or removed from config.
-- These cause 'group_index_orphan' warnings (now DEBUG) and wasted DB calls
-- because the safety-net full-fanout kicks in for every request.
--
-- Identified via log analysis 2026-04-15 — runtime logs showed 303
-- orphan entries in 1 hour mostly attributable to four stale repo names:
--
--   go-remote       — renamed to go_proxy
--   pypi-proxy      — renamed to pypi_proxy (underscore)
--   docker_cache    — renamed to docker_proxy
--   test            — scratch repo, never cleaned up
--
-- Operators who still use any of these names under different config
-- should re-run pantera-backfill for their repos after this migration.
DELETE FROM artifacts
WHERE repo_name IN ('go-remote', 'pypi-proxy', 'docker_cache', 'test');
