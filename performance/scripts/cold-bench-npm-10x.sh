#!/usr/bin/env bash
#
# v2.2.0 perf-pack — npm parity bench. Mirrors cold-bench-10x.sh for the
# Maven path, but exercises `npm install express` cold-cache through both
# pantera (npm_group → npm_proxy → registry.npmjs.org) and direct
# registry.npmjs.org. Computes median + min/max/p95/stdev for each side
# and a ratio (pantera / direct).
#
# Pre-flight (verified manually before invocation):
#   - pantera + nginx + pantera-db running with the v2.2.0 image that
#     registers the npm-proxy parser (see git rev-parse --short HEAD).
#   - User "ayd" has admin role; basic-auth token YXlkOmF5ZA== works
#     against http://localhost:8081/npm_group/.
#   - npm CLI 9.x or 10.x available on PATH.
#
# Run from repo root:
#   ./performance/scripts/cold-bench-npm-10x.sh
#
set -euo pipefail

RUNS="${RUNS:-10}"
PKG="${PKG:-express}"
RANGE="${RANGE:-^4.21.0}"
PANTERA_DIR="${PANTERA_DIR:-/tmp/npm-bench-pantera-10x}"
DIRECT_DIR="${DIRECT_DIR:-/tmp/npm-bench-direct-10x}"
RESULTS_FILE="${RESULTS_FILE:-performance/results/cold-bench-npm-10x.csv}"
SUMMARY_FILE="${SUMMARY_FILE:-performance/results/cold-bench-npm-10x.md}"
DB_CONTAINER="${DB_CONTAINER:-pantera-db}"
COMPOSE="${COMPOSE:-pantera-main/docker-compose/docker-compose.yaml}"
PROXY_DATA_DIR="${PROXY_DATA_DIR:-pantera-main/docker-compose/pantera/data/npm_proxy}"
PANTERA_REG="${PANTERA_REG:-http://localhost:8081/npm_group/}"
PANTERA_AUTH_B64="${PANTERA_AUTH_B64:-YXlkOmF5ZA==}"
DIRECT_REG="${DIRECT_REG:-https://registry.npmjs.org/}"

mkdir -p "$(dirname "$RESULTS_FILE")"
mkdir -p "$PANTERA_DIR" "$DIRECT_DIR"

cat > "$PANTERA_DIR/.npmrc" <<EOF
registry=$PANTERA_REG
//localhost:8081/npm_group/:_auth=$PANTERA_AUTH_B64
//localhost:8081/npm_proxy/:_auth=$PANTERA_AUTH_B64
//localhost:8081/:_auth=$PANTERA_AUTH_B64
EOF

cat > "$PANTERA_DIR/package.json" <<EOF
{ "name": "bench-pantera", "version": "1.0.0", "private": true,
  "dependencies": { "$PKG": "$RANGE" } }
EOF

cat > "$DIRECT_DIR/.npmrc" <<EOF
registry=$DIRECT_REG
EOF

cat > "$DIRECT_DIR/package.json" <<EOF
{ "name": "bench-direct", "version": "1.0.0", "private": true,
  "dependencies": { "$PKG": "$RANGE" } }
EOF

echo "run,target,wall_seconds,npm_rc" > "$RESULTS_FILE"

reset_pantera () {
    docker exec "$DB_CONTAINER" psql -U pantera -d pantera \
        -c "TRUNCATE artifacts;" >/dev/null
    rm -rf "$PROXY_DATA_DIR" 2>/dev/null || true
    docker compose -f "$COMPOSE" restart pantera >/dev/null
    local i=0
    while [ "$i" -lt 60 ]; do
        if curl -fsS -o /dev/null --max-time 2 \
            "http://localhost:8087/metrics/vertx" 2>/dev/null; then
            sleep 2
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    echo "ERROR: pantera metrics did not respond within 60s" >&2
    return 1
}

reset_npm_cache () {
    npm cache clean --force >/dev/null 2>&1 || true
}

run_one () {
    local target="$1"
    local dir="$2"
    rm -rf "$dir/node_modules" "$dir/package-lock.json" 2>/dev/null || true
    reset_npm_cache
    local t0 t1 wall rc
    t0=$(date +%s.%N)
    set +e
    ( cd "$dir" && npm install --no-audit --no-fund --loglevel=error \
        >/tmp/cold-bench-npm-${target}.log 2>&1 )
    rc=$?
    set -e
    t1=$(date +%s.%N)
    wall=$(echo "$t1 - $t0" | bc)
    printf '  [%s] wall=%.2fs rc=%d\n' "$target" "$wall" "$rc"
    printf '%d,%s,%.3f,%d\n' "$RUN_IDX" "$target" "$wall" "$rc" \
        >> "$RESULTS_FILE"
}

for i in $(seq 1 "$RUNS"); do
    echo "=== Run $i / $RUNS ==="
    RUN_IDX=$i
    # Direct first — independent of pantera state.
    run_one direct "$DIRECT_DIR"
    # Pantera — fresh cold reset every run.
    reset_pantera
    run_one pantera "$PANTERA_DIR"
done

echo
echo "=== Summary ==="
python3 - "$RESULTS_FILE" "$SUMMARY_FILE" <<'PY'
import csv, statistics, sys, datetime, subprocess
from collections import defaultdict

results_csv = sys.argv[1]
summary_md  = sys.argv[2]

walls = defaultdict(list)
failed = defaultdict(int)
total = defaultdict(int)
with open(results_csv) as f:
    for row in csv.DictReader(f):
        target = row["target"]
        total[target] += 1
        if int(row["npm_rc"]) == 0:
            walls[target].append(float(row["wall_seconds"]))
        else:
            failed[target] += 1

for target in ("direct", "pantera"):
    walls[target].sort()

def stat(vals):
    if not vals:
        return {}
    p95_idx = max(0, min(len(vals) - 1, int(round(0.95 * len(vals))) - 1))
    return {
        "min": min(vals), "p50": statistics.median(vals),
        "mean": statistics.mean(vals),
        "stdev": statistics.stdev(vals) if len(vals) >= 2 else 0.0,
        "p95": vals[p95_idx], "max": max(vals),
    }

direct_s = stat(walls["direct"])
pantera_s = stat(walls["pantera"])

for label, s, n, fl, tot in (
    ("direct ", direct_s, len(walls["direct"]),  failed["direct"],  total["direct"]),
    ("pantera", pantera_s, len(walls["pantera"]), failed["pantera"], total["pantera"]),
):
    print(f"{label}: n={n} ok ({fl} failed of {tot})")
    if not s:
        continue
    for k in ("min","p50","mean","stdev","p95","max"):
        print(f"  {k:6s} {s[k]:.2f}s")

if direct_s and pantera_s and direct_s["p50"] > 0:
    ratio = pantera_s["p50"] / direct_s["p50"]
    print(f"\nratio (pantera p50 / direct p50): {ratio:.2f}x")
else:
    ratio = None

try:
    head = subprocess.check_output(
        ["git", "rev-parse", "--short", "HEAD"], text=True
    ).strip()
except Exception:
    head = "unknown"

with open(summary_md, "w") as out:
    out.write("# 10-iteration cold-cache npm install bench — v2.2.0 perf-pack\n\n")
    out.write(f"- Date: {datetime.datetime.utcnow().isoformat()}Z\n")
    out.write(f"- Pantera HEAD: `{head}`\n")
    out.write("- Command: `npm install` of express ^4.21.0 (~69 transitive packages)\n")
    out.write("- Direct: registry.npmjs.org (no pantera in path)\n")
    out.write("- Pantera: localhost:8081/npm_group → npm_proxy → registry.npmjs.org\n")
    out.write("- Per-pantera-run reset: TRUNCATE artifacts, "
              "clear npm_proxy fs cache, restart pantera. "
              "Per-run: npm cache clean --force.\n\n")
    out.write("| run | target | wall (s) | rc |\n|---|---|---|---|\n")
    with open(results_csv) as f:
        for row in csv.DictReader(f):
            out.write(f"| {row['run']} | {row['target']} | "
                      f"{float(row['wall_seconds']):.2f} | "
                      f"{row['npm_rc']} |\n")
    out.write("\n## Statistics (excluding failed runs)\n\n")
    for label, s in (("Direct (registry.npmjs.org)", direct_s),
                     ("Pantera (npm_group)",       pantera_s)):
        out.write(f"### {label}\n\n")
        if not s:
            out.write("- (no successful runs)\n\n")
            continue
        for k in ("min","p50","mean","stdev","p95","max"):
            out.write(f"- {k}: {s[k]:.2f}s\n")
        out.write("\n")
    if ratio is not None:
        out.write(f"## Parity ratio\n\n- pantera p50 / direct p50 = **{ratio:.2f}x**\n")
        out.write(f"- v2.2.0 acceptance bar: ≤ 1.5x — "
                  f"{'PASS' if ratio <= 1.5 else 'FAIL'}\n")
PY

echo
echo "Results: $RESULTS_FILE"
echo "Summary: $SUMMARY_FILE"
