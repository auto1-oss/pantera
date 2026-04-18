#!/usr/bin/env bash
# perf-compare.sh — compare measured perf results against a baseline.
#
# Usage: perf-compare.sh <baseline.json> <measured.json>
#
# Exits 0 if within threshold, 1 if p99 regressed by more than 10%.
# Requires: python3 (for JSON parsing).
set -euo pipefail

BASELINE="${1:?Usage: perf-compare.sh <baseline.json> <measured.json>}"
MEASURED="${2:?Usage: perf-compare.sh <baseline.json> <measured.json>}"

if [ ! -f "${BASELINE}" ]; then
  echo "ERROR: Baseline file not found: ${BASELINE}"
  exit 1
fi
if [ ! -f "${MEASURED}" ]; then
  echo "ERROR: Measured file not found: ${MEASURED}"
  exit 1
fi

THRESHOLD_PCT=10

echo "=== Perf comparison ==="
echo "Baseline: ${BASELINE}"
echo "Measured: ${MEASURED}"
echo "Regression threshold: ${THRESHOLD_PCT}%"
echo ""

# Use python3 for portable JSON parsing
python3 - "${BASELINE}" "${MEASURED}" "${THRESHOLD_PCT}" <<'PYEOF'
import json
import sys

baseline_path = sys.argv[1]
measured_path = sys.argv[2]
threshold_pct = int(sys.argv[3])

with open(baseline_path) as f:
    baseline = json.load(f)
with open(measured_path) as f:
    measured = json.load(f)

failed = False
for metric in ["p50_ms", "p95_ms", "p99_ms"]:
    base_val = baseline.get(metric, 0)
    meas_val = measured.get(metric, 0)
    if base_val == 0:
        print(f"  {metric}: baseline=0, measured={meas_val} (SKIP: no baseline)")
        continue
    pct_change = ((meas_val - base_val) / base_val) * 100
    status = "OK"
    if pct_change > threshold_pct:
        status = "REGRESSED"
        failed = True
    print(f"  {metric}: baseline={base_val}, measured={meas_val}, "
          f"delta={pct_change:+.1f}% [{status}]")

# Throughput: regression means lower value
base_tps = baseline.get("throughput_rps", 0)
meas_tps = measured.get("throughput_rps", 0)
if base_tps > 0:
    tps_change = ((meas_tps - base_tps) / base_tps) * 100
    tps_status = "OK"
    if tps_change < -threshold_pct:
        tps_status = "REGRESSED"
        failed = True
    print(f"  throughput_rps: baseline={base_tps}, measured={meas_tps}, "
          f"delta={tps_change:+.1f}% [{tps_status}]")

print()
if failed:
    print("FAIL: Performance regression detected (>{0}% threshold)".format(threshold_pct))
    sys.exit(1)
else:
    print("PASS: All metrics within {0}% threshold".format(threshold_pct))
    sys.exit(0)
PYEOF
