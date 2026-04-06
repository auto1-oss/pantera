-- V109: Full-text search and fuzzy matching on artifacts
-- Moves FTS column, trigger, and supporting indexes from
-- ArtifactDbFactory.createStructure() into Flyway.

-- pg_trgm extension for trigram-based fuzzy search (LIKE '%foo%')
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- tsvector column for full-text search. Populated by trigger below.
ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS search_tokens tsvector;

-- GIN index for fast @@ tsquery matching
CREATE INDEX IF NOT EXISTS idx_artifacts_search
    ON artifacts USING gin(search_tokens);

-- Trigger function: populate search_tokens on INSERT/UPDATE.
-- Uses translate() to replace dots/slashes/dashes/underscores with spaces
-- so each path/version component becomes a separate searchable token.
-- Without this, "auto1.base.test.txt" would be one opaque token and
-- searching for "test" wouldn't match.
CREATE OR REPLACE FUNCTION artifacts_search_update() RETURNS trigger AS $$
BEGIN
  NEW.search_tokens := to_tsvector('simple',
    translate(coalesce(NEW.name, ''), './-_', '    ') || ' ' ||
    translate(coalesce(NEW.version, ''), './-_', '    ') || ' ' ||
    coalesce(NEW.owner, '') || ' ' ||
    translate(coalesce(NEW.repo_name, ''), './-_', '    ') || ' ' ||
    translate(coalesce(NEW.repo_type, ''), './-_', '    '));
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop + recreate trigger for idempotent migration
DROP TRIGGER IF EXISTS trg_artifacts_search ON artifacts;
CREATE TRIGGER trg_artifacts_search
  BEFORE INSERT OR UPDATE ON artifacts
  FOR EACH ROW EXECUTE FUNCTION artifacts_search_update();

-- Case-insensitive name lookups (LOWER(name) LIKE ...)
CREATE INDEX IF NOT EXISTS idx_artifacts_name_lower
    ON artifacts(LOWER(name));

-- Repo-scoped latest-first browsing
CREATE INDEX IF NOT EXISTS idx_artifacts_repo_latest
    ON artifacts(repo_name, created_date DESC);

-- Trigram index for fuzzy name matching (LIKE '%foo%')
CREATE INDEX IF NOT EXISTS idx_artifacts_name_trgm
    ON artifacts USING GIN(name gin_trgm_ops);
