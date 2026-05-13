-- V125__artifact_publish_dates.sql
-- Canonical publish-date cache. Rows are immutable: once written, never updated.
-- Source value is informational (e.g. "maven_central_solr", "npm_registry") so we
-- can re-fetch from a different source if a row is suspect.
CREATE TABLE artifact_publish_dates (
    repo_type     VARCHAR(32)  NOT NULL,
    name          VARCHAR(512) NOT NULL,
    version       VARCHAR(128) NOT NULL,
    published_at  TIMESTAMPTZ  NOT NULL,
    source        VARCHAR(64)  NOT NULL,
    fetched_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_type, name, version)
);

-- Lookups are always by full PK; no secondary indexes needed.
COMMENT ON TABLE artifact_publish_dates IS
  'Canonical publish dates from ecosystem registries. Immutable once written.';
COMMENT ON COLUMN artifact_publish_dates.source IS
  'Origin of the value: maven_central_solr | npm_registry | pypi | go_proxy | packagist | rubygems';
