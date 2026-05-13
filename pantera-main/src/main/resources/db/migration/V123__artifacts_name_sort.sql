-- V123: Natural name sorting via stored generated column
--
-- Users saw search results for `name` sorted lexicographically, which
-- places `pkg-10` before `pkg-2`. This migration adds `name_sort` — a
-- text key that zero-pads every digit run inside the name so plain
-- lexicographic ordering yields natural ordering.
--
-- Covers:
--   * `pkg-2`  vs `pkg-10`           → `pkg-00...02` vs `pkg-00...10` (correct)
--   * `libfoo-1.10.2` vs `libfoo-1.2.3`
--   * Pure alphabetic names          → returned unchanged
--   * NULL                            → NULL (sorts NULLS LAST in ORDER BY)
--
-- Implementation mirrors V111's version_sort pattern but uses a generic
-- token splitter (digit runs vs non-digit runs) so it does not require
-- a parseable version shape.

CREATE OR REPLACE FUNCTION natural_sort_key(v text) RETURNS text
IMMUTABLE LANGUAGE sql AS $func$
    SELECT CASE
        WHEN v IS NULL THEN NULL
        WHEN v = '' THEN ''
        ELSE (
            SELECT string_agg(
                CASE WHEN m[1] IS NOT NULL THEN lpad(m[1], 20, '0')
                     ELSE m[2] END,
                '' ORDER BY ord
            )
            FROM regexp_matches(v, '([0-9]+)|([^0-9]+)', 'g')
                 WITH ORDINALITY AS t(m, ord)
        )
    END
$func$;

ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS name_sort text
    GENERATED ALWAYS AS (natural_sort_key(name)) STORED;

CREATE INDEX IF NOT EXISTS idx_artifacts_name_sort
    ON artifacts(name_sort);
