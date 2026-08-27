#!/usr/bin/env bash
#
# audit-repo-base-urls.sh
#
# Audit (and optionally clear) stale repo-level `url:` values in the
# `repositories` table.
#
# A repository's `url:` is the client-facing base URL Pantera embeds in the
# links it emits (npm `dist.tarball`, Composer provider URLs, Helm chart URLs).
# It is tier 1 of the base-URL chain in SliceByPath: when set it beats the
# `client_base_url` admin setting AND `Host`/`X-Forwarded-*` derivation, for
# every client, whatever hostname they used. A value carried over from a
# previous registry by the YAML-to-DB import therefore pins every client's
# links to the OLD host until the key is removed.
#
# Types whose adapter builds absolute URLs without the per-request derivation
# still hard-require `url:` — this script never touches those, it lists them
# for manual review instead. As of 2.2.6 hosted `npm` is NOT one of them, and
# `conan` never was — it builds its download_urls from the request Host.
#
# Usage:
#     scripts/audit-repo-base-urls.sh [options]
#
#       --stale-host <substring>   Host to treat as stale. Repeatable.
#                                  Default: none — report every configured url:.
#       --repo <name>              Limit to one repository. Repeatable.
#       --apply                    Actually clear the keys. Default is dry-run.
#       --backup-dir <dir>         Where --apply writes its revert SQL.
#                                  Default: ./pantera-url-audit-backups
#       -h | --help                This text.
#
# Connection: standard libpq environment (PGHOST, PGPORT, PGUSER, PGPASSWORD,
# PGDATABASE) or PANTERA_DB_DSN with a full connection URI.
#
# Exit codes:
#     0 = nothing to do (or --apply completed)
#     1 = stale urls found in dry-run mode
#     2 = usage / connection error
#
set -euo pipefail

# Adapters that construct absolute URLs directly and still need `url:`.
# Keep in sync with docs/configuration-reference.md §2.2.
REQUIRED_TYPES="helm php nuget conda"

STALE_HOSTS=()
REPOS=()
APPLY=0
BACKUP_DIR="./pantera-url-audit-backups"

die() { echo "error: $*" >&2; exit 2; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stale-host) [[ $# -ge 2 ]] || die "--stale-host needs a value"; STALE_HOSTS+=("$2"); shift 2 ;;
        --repo)       [[ $# -ge 2 ]] || die "--repo needs a value";       REPOS+=("$2");       shift 2 ;;
        --apply)      APPLY=1; shift ;;
        --backup-dir) [[ $# -ge 2 ]] || die "--backup-dir needs a value"; BACKUP_DIR="$2";     shift 2 ;;
        -h|--help)    awk 'NR>1 { if (/^#/) { sub(/^# ?/, ""); print } else { exit } }' "$0"; exit 0 ;;
        *)            die "unknown argument: $1" ;;
    esac
done

command -v psql >/dev/null 2>&1 || die "psql not found on PATH"

PSQL=(psql --no-psqlrc --quiet --tuples-only --no-align --field-separator=$'\t')
if [[ -n "${PANTERA_DB_DSN:-}" ]]; then
    PSQL+=("${PANTERA_DB_DSN}")
fi

"${PSQL[@]}" -c 'SELECT 1' >/dev/null 2>&1 \
    || die "cannot connect to PostgreSQL (set PGHOST/PGUSER/PGDATABASE or PANTERA_DB_DSN)"

# ---------------------------------------------------------------------------
# Build the WHERE clause. Everything interpolated here is shell-provided
# (never row data), and each value is single-quote escaped for SQL.
# ---------------------------------------------------------------------------
sql_quote() { printf "'%s'" "${1//\'/\'\'}"; }

WHERE="config -> 'repo' ? 'url'"

if [[ ${#STALE_HOSTS[@]} -gt 0 ]]; then
    clause=""
    for host in "${STALE_HOSTS[@]}"; do
        [[ -n "${clause}" ]] && clause+=" OR "
        clause+="config -> 'repo' ->> 'url' LIKE $(sql_quote "%${host}%")"
    done
    WHERE+=" AND (${clause})"
fi

if [[ ${#REPOS[@]} -gt 0 ]]; then
    list=""
    for repo in "${REPOS[@]}"; do
        [[ -n "${list}" ]] && list+=", "
        list+="$(sql_quote "${repo}")"
    done
    WHERE+=" AND name IN (${list})"
fi

REQUIRED_LIST=""
for type in ${REQUIRED_TYPES}; do
    [[ -n "${REQUIRED_LIST}" ]] && REQUIRED_LIST+=", "
    REQUIRED_LIST+="$(sql_quote "${type}")"
done

ROWS=$("${PSQL[@]}" -c "
    SELECT name, type, config -> 'repo' ->> 'url',
           CASE WHEN type IN (${REQUIRED_LIST}) THEN 'required' ELSE 'clearable' END
      FROM repositories
     WHERE ${WHERE}
     ORDER BY 4 DESC, name;
")

if [[ -z "${ROWS}" ]]; then
    echo "No repositories match — nothing to do."
    exit 0
fi

CLEARABLE=()
printf '%-32s %-14s %-10s %s\n' REPOSITORY TYPE VERDICT URL
printf '%-32s %-14s %-10s %s\n' "--------------------------------" "--------------" "----------" "---"
while IFS=$'\t' read -r name type url verdict; do
    [[ -z "${name}" ]] && continue
    printf '%-32s %-14s %-10s %s\n' "${name}" "${type}" "${verdict}" "${url}"
    [[ "${verdict}" == "clearable" ]] && CLEARABLE+=("${name}")
done <<< "${ROWS}"

echo
echo "clearable: ${#CLEARABLE[@]}"
echo
echo "A 'required' verdict means the adapter for that type builds absolute URLs"
echo "without per-request derivation (${REQUIRED_TYPES// /, }): clearing url: there"
echo "breaks the repository. Review those by hand."

if [[ ${#CLEARABLE[@]} -eq 0 ]]; then
    exit 0
fi

if [[ ${APPLY} -eq 0 ]]; then
    echo
    echo "Dry run — nothing written. Re-run with --apply to clear the ${#CLEARABLE[@]} clearable url: key(s)."
    echo "After clearing, set trust_forwarded_headers=true and list every hostname you"
    echo "serve in client_base_host_allowlist (Settings -> Client-Facing Base URL), and"
    echo "make sure the fronting proxy OVERWRITES X-Forwarded-Proto/-Host on every request."
    exit 1
fi

mkdir -p "${BACKUP_DIR}"
STAMP=$("${PSQL[@]}" -c "SELECT to_char(NOW(), 'YYYYMMDD-HH24MISS')" | tr -d '[:space:]')
BACKUP_FILE="${BACKUP_DIR}/repo-url-revert-${STAMP}.sql"

NAME_LIST=""
for name in "${CLEARABLE[@]}"; do
    [[ -n "${NAME_LIST}" ]] && NAME_LIST+=", "
    NAME_LIST+="$(sql_quote "${name}")"
done

# Revert script first: one UPDATE per row restoring the exact previous url:.
{
    echo "-- Revert for audit-repo-base-urls.sh --apply at ${STAMP}"
    echo "BEGIN;"
    "${PSQL[@]}" -c "
        SELECT format(
            'UPDATE repositories SET config = jsonb_set(config, ''{repo,url}'', %L::jsonb), updated_at = NOW() WHERE name = %L;',
            to_jsonb(config -> 'repo' ->> 'url'), name
        )
          FROM repositories
         WHERE name IN (${NAME_LIST});
    "
    echo "COMMIT;"
} > "${BACKUP_FILE}"
echo "Revert SQL written to ${BACKUP_FILE}"

UPDATED=$("${PSQL[@]}" -c "
    WITH cleared AS (
        UPDATE repositories
           SET config = jsonb_set(config, '{repo}', (config -> 'repo') - 'url'),
               updated_at = NOW(),
               updated_by = 'audit-repo-base-urls'
         WHERE name IN (${NAME_LIST})
        RETURNING name
    )
    SELECT count(*) FROM cleared;
" | tr -d '[:space:]')

echo "Cleared url: from ${UPDATED} repository config(s)."
echo
echo "Repository configs are cached per node: the change takes effect on the next"
echo "config reload (a PUT through the admin API, or a restart). Verify with a real"
echo "client request, e.g.:"
echo "  curl -s -H 'Host: <your-host>' <backend>/api/npm/<repo>/<package> | jq -r '.versions[].dist.tarball' | head -3"
