#!/usr/bin/env bash
# seed-files.sh — materialise 100 000 tarballs on disk as hardlinks to the three
# WireMock body files so local-repo and group-local routes return real bytes.
#
# Host path layout (mounted at /var/pantera/data inside the SUT):
#   performance/data/<repo>/<pkg>-<ver>.tgz          — flat npm layout per repo
#
# The repo npm.yaml uses `storage.path: /var/pantera/data` with no per-repo prefix
# in the existing benchmark template. Pantera's FileStorage writes artifacts under
# that root with the URL path as the file path. For the npm URL pattern
# `/<repo>/<pkg>/-/<pkg>-<ver>.tgz`, the stored file ends up at
# `/var/pantera/data/<repo>/<pkg>/-/<pkg>-<ver>.tgz` (slashes preserved).
#
# Size bucket matches seed.sql: (pkgId mod 10) → 7,8 → 1 MB; 9 → 10 MB; else 100 KB.
#
# Idempotent: skips repos whose artifact 0 already exists.

set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

SRC_100K="$(pwd)/wiremock/__files/body-100k.bin"
SRC_1M="$(pwd)/wiremock/__files/body-1m.bin"
SRC_10M="$(pwd)/wiremock/__files/body-10m.bin"
[ -f "$SRC_100K" ] || ./wiremock/__files/generate.sh

DEST_ROOT="$(pwd)/data"
mkdir -p "$DEST_ROOT"

# If the marker file from the last seed exists, skip — hardlinks are persistent.
MARKER="$DEST_ROOT/.seeded-100k"
if [ -f "$MARKER" ]; then
  echo "=== seed-files: already seeded (found $MARKER) — skipping ==="
  exit 0
fi

echo "=== seed-files: hardlinking 100 000 tarballs across 5 local repos ==="
START=$SECONDS

# Use python for fast batch os.link — bash loop would fork ~300k times.
python3 - "$SRC_100K" "$SRC_1M" "$SRC_10M" "$DEST_ROOT" <<'PY'
import os, sys

src_100k, src_1m, src_10m, dest_root = sys.argv[1:5]
PKG_PER_REPO = 20000
REPOS = [f"local-repo-{i}" for i in (1, 2, 3, 4, 5)]

def size_src(n: int) -> str:
    m = n % 10
    if m == 9:
        return src_10m
    if m == 7 or m == 8:
        return src_1m
    return src_100k

for repo in REPOS:
    for local_id in range(PKG_PER_REPO):
        name = f"pkg-{local_id:05d}"
        ver = "1.0.0"
        dir_ = os.path.join(dest_root, repo, name, "-")
        os.makedirs(dir_, exist_ok=True)
        dest = os.path.join(dir_, f"{name}-{ver}.tgz")
        if not os.path.exists(dest):
            os.link(size_src(local_id), dest)
print("linked 100000 artifacts (5 repos x 20000 each)")
PY

touch "$MARKER"
ELAPSED=$((SECONDS - START))
echo "=== seed-files: done in ${ELAPSED}s ==="
du -sh "$DEST_ROOT"
