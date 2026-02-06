#!/usr/bin/env bash
# fix-maven-metadata-timestamps.sh
#
# Fixes maven-metadata.xml files that have <lastUpdated> as epoch milliseconds
# (13 digits) instead of the Maven-standard yyyyMMddHHmmss format (14 digits).
# Also regenerates checksums (.sha1, .md5, .sha256) for modified files.
#
# Usage:
#   ./fix-maven-metadata-timestamps.sh /path/to/maven/repo [--dry-run]
#
# Requirements: bash 4+, date (GNU coreutils or BSD), shasum/sha256sum, md5sum
set -euo pipefail

REPO_ROOT="${1:?Usage: $0 /path/to/maven/repo [--dry-run]}"
DRY_RUN="${2:-}"
FIXED=0
SKIPPED=0
ERRORS=0

if [[ ! -d "$REPO_ROOT" ]]; then
    echo "ERROR: Directory not found: $REPO_ROOT" >&2
    exit 1
fi

# Detect platform for date command
epoch_to_maven_ts() {
    local epoch_ms="$1"
    local epoch_s=$(( epoch_ms / 1000 ))
    if date --version &>/dev/null; then
        # GNU date (Linux)
        date -u -d "@${epoch_s}" '+%Y%m%d%H%M%S'
    else
        # BSD date (macOS)
        date -u -r "${epoch_s}" '+%Y%m%d%H%M%S'
    fi
}

generate_checksums() {
    local file="$1"
    if command -v sha1sum &>/dev/null; then
        sha1sum "$file" | awk '{print $1}' > "${file}.sha1"
    else
        shasum -a 1 "$file" | awk '{print $1}' > "${file}.sha1"
    fi
    if command -v md5sum &>/dev/null; then
        md5sum "$file" | awk '{print $1}' > "${file}.md5"
    else
        md5 -q "$file" > "${file}.md5"
    fi
    if command -v sha256sum &>/dev/null; then
        sha256sum "$file" | awk '{print $1}' > "${file}.sha256"
    else
        shasum -a 256 "$file" | awk '{print $1}' > "${file}.sha256"
    fi
}

echo "Scanning $REPO_ROOT for maven-metadata.xml files with epoch timestamps..."
echo ""

while IFS= read -r -d '' file; do
    # Extract lastUpdated value
    ts=$(grep -oP '(?<=<lastUpdated>)\d+(?=</lastUpdated>)' "$file" 2>/dev/null || true)

    if [[ -z "$ts" ]]; then
        (( SKIPPED++ )) || true
        continue
    fi

    # Check if it's already correct (14 digits starting with 20)
    if [[ ${#ts} -eq 14 && "$ts" =~ ^20[0-9]{12}$ ]]; then
        (( SKIPPED++ )) || true
        continue
    fi

    # Must be epoch millis (13 digits typically)
    if [[ ! "$ts" =~ ^[0-9]{13,}$ ]]; then
        echo "WARN: Unexpected format in $file: $ts" >&2
        (( SKIPPED++ )) || true
        continue
    fi

    new_ts=$(epoch_to_maven_ts "$ts")
    if [[ -z "$new_ts" || ${#new_ts} -ne 14 ]]; then
        echo "ERROR: Failed to convert timestamp $ts in $file" >&2
        (( ERRORS++ )) || true
        continue
    fi

    if [[ "$DRY_RUN" == "--dry-run" ]]; then
        echo "[DRY-RUN] $file: $ts -> $new_ts"
    else
        # Replace in-place
        if sed -i.bak "s|<lastUpdated>${ts}</lastUpdated>|<lastUpdated>${new_ts}</lastUpdated>|g" "$file"; then
            rm -f "${file}.bak"
            generate_checksums "$file"
            echo "[FIXED] $file: $ts -> $new_ts"
        else
            echo "ERROR: sed failed for $file" >&2
            (( ERRORS++ )) || true
            continue
        fi
    fi
    (( FIXED++ )) || true

done < <(find "$REPO_ROOT" -name 'maven-metadata.xml' -not -name '*.sha1' -not -name '*.md5' -print0)

echo ""
echo "Done. Fixed: $FIXED, Skipped: $SKIPPED, Errors: $ERRORS"
if [[ "$DRY_RUN" == "--dry-run" ]]; then
    echo "(Dry run — no files were modified)"
fi
