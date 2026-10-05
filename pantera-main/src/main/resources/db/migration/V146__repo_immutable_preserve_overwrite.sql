-- V146: keep the overwrite behaviour of existing repositories.
--
-- 2.2.10 adds the per-repository boolean `repo.immutable` (flat key, like
-- anonymous_read). A missing key means immutable = true: an existing
-- published artifact can never be overwritten.
--
-- Hosted repositories of the types below overwrote an existing artifact on
-- a plain re-upload before 2.2.10. So that an upgrade does not silently turn
-- their re-uploads into 409 Conflict, every such repository stored in the
-- database WITHOUT an explicit `immutable` key is pinned to
-- `immutable: false` (today's behaviour). An operator opts in afterwards by
-- setting `immutable: true` in the repository settings.
--
-- conan is listed because its upload-URL refusal never fired on file or S3
-- storage, so conan repositories overwrite today as well. Every other type
-- (maven, gradle, php, go, pypi, nuget, ...) already refused overwrites and
-- gets the new default. Repositories with an explicit
-- `immutable` key are left as configured. Config documents are stored as
-- {"repo": {...}} (RepositoryDao). YAML-only repositories (no database) are
-- not touched and get the new default.

UPDATE repositories
SET config = jsonb_set(config, '{repo,immutable}', 'false'::jsonb)
WHERE jsonb_typeof(config->'repo') = 'object'
  AND config->'repo'->>'type' IN ('file', 'npm', 'gem', 'conda', 'deb', 'helm', 'rpm', 'hexpm', 'conan')
  AND NOT (config->'repo' ? 'immutable');
