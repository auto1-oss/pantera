-- V124: Classify artifacts so search excludes metadata/checksum/signature rows
--
-- Users reported searches for `wkda.common.api.vehicle-api` returning 16k+
-- rows, all of them `.meta.maven.shards.*` internal metadata entries — the
-- real JAR artifacts were invisible behind the noise. The backfill scanners
-- filter these patterns at import time (see MavenScanner.isScannableCandidate)
-- but live upload paths insert them unfiltered, and the search SQL had no
-- WHERE clause to exclude them.
--
-- This migration adds a persisted `artifact_kind` column populated by a
-- pure SQL classification function. Kinds:
--   ARTIFACT   primary artifact, the default search result
--   CHECKSUM   .md5 / .sha1 / .sha256 / .sha512
--   SIGNATURE  .asc / .sig
--   METADATA   maven-metadata.xml, Pantera internal shards (`.meta.*`),
--              Pantera sidecars (`.pantera-meta.json`), Debian repo index
--              files (Packages, Release, InRelease, Sources), Helm
--              `index.yaml`, Composer `packages.json`
--
-- Rows needed for group routing (metadata, checksums for integrity checks)
-- stay in the table; search filters them at query time with
-- `AND artifact_kind = 'ARTIFACT'`.
--
-- A partial index on rows where kind != 'ARTIFACT' is cheap in storage
-- because metadata/checksum entries are a small fraction of the table;
-- queries that opt into those kinds via the `include=` query parameter
-- will use it.

CREATE OR REPLACE FUNCTION classify_artifact(n text) RETURNS text
IMMUTABLE LANGUAGE sql AS $func$
    SELECT CASE
        WHEN n IS NULL THEN 'ARTIFACT'
        WHEN n ~* '\.(md5|sha1|sha256|sha512)$'     THEN 'CHECKSUM'
        WHEN n ~* '\.(asc|sig)$'                    THEN 'SIGNATURE'
        WHEN n = 'maven-metadata.xml'
          OR n LIKE 'maven-metadata.xml.%'
          OR n LIKE '%.pantera-meta.json'
          OR starts_with(n, '.meta.')
          OR starts_with(n, '.pantera-')
          OR n = 'index.yaml'
          OR n = 'packages.json'
          OR n IN ('Packages', 'Release', 'InRelease', 'Sources')
          OR n LIKE 'Packages.%'
          OR n LIKE 'Release.%'                      THEN 'METADATA'
        ELSE 'ARTIFACT'
    END
$func$;

ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS artifact_kind text
    GENERATED ALWAYS AS (classify_artifact(name)) STORED;

-- Small partial index: most rows are ARTIFACT, only NON-ARTIFACT rows need
-- an index to be reachable when `include=` is requested.
CREATE INDEX IF NOT EXISTS idx_artifacts_kind_nonartifact
    ON artifacts(artifact_kind)
    WHERE artifact_kind <> 'ARTIFACT';

-- Composite index supporting the default-filtered search path:
-- (kind, created_date) lets `WHERE artifact_kind='ARTIFACT' ORDER BY
-- created_date` use an index-only scan.
CREATE INDEX IF NOT EXISTS idx_artifacts_kind_created_date
    ON artifacts(artifact_kind, created_date);
