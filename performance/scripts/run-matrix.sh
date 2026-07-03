#!/usr/bin/env bash
# run-matrix.sh — run the full scaling matrix (6 cells).
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"

# Build SUT image once
docker compose --env-file .env.C2 -f docker-compose-scaling.yml build pantera-sut

# Ensure bodies exist
[ -f wiremock/__files/body-100k.bin ] || ./wiremock/__files/generate.sh

# V0 scaling axis
for CFG in C1 C2 C3 C4; do
  ./scripts/run-cell.sh "$CFG" V0
done

# V1 cold start (C2 only)
./scripts/run-cell.sh C2 V1
# V2 cooldown off (C2 only)
./scripts/run-cell.sh C2 V2

# Aggregate
python3 scripts/parse-k6.py results results/scaling-raw.csv
python3 scripts/render-summary.py results/scaling-raw.csv results/scaling-summary.md

echo "=== Matrix done. Summary: results/scaling-summary.md ==="
