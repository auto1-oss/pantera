-- V145: index Go modules under their real module path.
--
-- GOPROXY URLs and storage keys escape every upper-case letter of a module
-- path as '!' + lower-case (github.com/!burnt!sushi/toml for
-- github.com/BurntSushi/toml). The go and go-proxy adapters recorded that
-- escaped form as the artifact name, so search never found a mixed-case
-- module by the path developers use. From 2.2.9 the adapters record the
-- decoded path and group routing looks it up decoded; this migration
-- decodes the rows written before, so they stay routable and searchable.
--
-- A row whose decoded name already exists for the same repository and
-- version (both forms indexed) is a duplicate and is removed; every other
-- escaped row is renamed. path_prefix (the escaped storage key) is kept.

CREATE TEMP TABLE v145_go_names AS
SELECT a.id, a.repo_name, a.version, (
    SELECT string_agg(COALESCE(upper(substr(m.arr[1], 2)), m.arr[2]), '' ORDER BY m.ord)
    FROM regexp_matches(a.name, '(![a-z])|([^!]+|!)', 'g') WITH ORDINALITY AS m(arr, ord)
) AS real_name
FROM artifacts a
WHERE a.repo_type IN ('go', 'go-proxy') AND position('!' IN a.name) > 0;

DELETE FROM artifacts a
USING v145_go_names n
WHERE a.id = n.id
  AND EXISTS (
      SELECT 1 FROM artifacts b
      WHERE b.repo_name = n.repo_name AND b.version = n.version AND b.name = n.real_name
  );

UPDATE artifacts a
SET name = n.real_name
FROM v145_go_names n
WHERE a.id = n.id AND n.real_name IS NOT NULL AND a.name <> n.real_name;

DROP TABLE v145_go_names;
