-- V111: Natural version sorting via stored generated column
--
-- Creates an IMMUTABLE helper function that produces a lexically-sortable
-- text key for any version string, then exposes it as a STORED generated
-- column on the artifacts table.
--
-- Covers:
--   * Numeric versions (1.5.0, 1.24.0) → zero-padded segments
--   * Dates (2019-08-23)              → same, hyphens normalized to dots
--   * v-prefixed (v0.0.3-SNAPSHOT)    → v stripped, then normalized
--   * Bare integers (5007235)         → single zero-padded segment
--   * Git hashes / arbitrary strings  → stored as-is for lexical sort
--   * UNKNOWN                         → NULL (falls to end via NULLS LAST)

CREATE OR REPLACE FUNCTION version_sort_key(v text) RETURNS text
IMMUTABLE LANGUAGE sql AS $func$
    SELECT CASE
        WHEN v IS NULL OR v = 'UNKNOWN' THEN NULL
        WHEN REGEXP_REPLACE(v, '^[vV]', '') ~ '^[0-9]+([.-][0-9]+)+'
            THEN (SELECT string_agg(lpad(s, 20, '0'), '.')
                  FROM unnest(string_to_array(
                    REGEXP_REPLACE(
                      REPLACE(
                        REGEXP_REPLACE(
                          REGEXP_REPLACE(v, '^[vV]', ''),
                          '[^0-9.\-].*$', ''),
                        '-', '.'),
                      '(^\.|\.\.+|\.$)', '', 'g'),
                    '.')) AS s)
        WHEN v ~ '^[0-9]+$'
            THEN lpad(v, 20, '0')
        ELSE v
    END
$func$;

-- Detect and drop any v1 (bigint[]) version_sort column before adding v2 (text).
-- On fresh DBs this block is a no-op; on existing DBs it migrates the type.
DO $$
DECLARE
    col_type text;
BEGIN
    SELECT data_type INTO col_type
    FROM information_schema.columns
    WHERE table_name = 'artifacts' AND column_name = 'version_sort';

    IF col_type IS NOT NULL AND col_type <> 'text' THEN
        ALTER TABLE artifacts DROP COLUMN version_sort;
    END IF;
END $$;

-- Add the stored generated column if missing
ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS version_sort text
    GENERATED ALWAYS AS (version_sort_key(version)) STORED;

CREATE INDEX IF NOT EXISTS idx_artifacts_version_sort
    ON artifacts(version_sort);
