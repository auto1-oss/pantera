#!/usr/bin/env bash
# stats-collector.sh — sample docker stats every 5 s for the scaling stack.
#
# Usage: stats-collector.sh <output_csv>
# Stops on SIGTERM; flushes partial samples on exit.
set -euo pipefail
OUT="${1:?usage: stats-collector.sh <output_csv>}"
INTERVAL=5
SERVICES=(pantera-sut postgres valkey mock-upstream)

echo "ts,service,cpu_pct,mem_pct,mem_used_mb" > "$OUT"

cleanup() { exit 0; }
trap cleanup TERM INT

while true; do
  ts=$(date -u +%s)
  # docker stats --no-stream emits one row per running container
  docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemPerc}},{{.MemUsage}}' \
    | awk -v ts="$ts" -F',' '
      {
        name=$1; cpu=$2; memp=$3; memu=$4;
        gsub(/%/, "", cpu);
        gsub(/%/, "", memp);
        # MemUsage format: "123MiB / 4GiB" — take the first number, strip unit
        split(memu, parts, " / ");
        mem_used=parts[1];
        gsub(/MiB/, "", mem_used);
        gsub(/GiB/, "000", mem_used);
        printf "%s,%s,%s,%s,%s\n", ts, name, cpu, memp, mem_used
      }' >> "$OUT" || true
  sleep "$INTERVAL"
done
