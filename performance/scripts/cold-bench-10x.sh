#!/usr/bin/env bash
#
# Phase 8 acceptance bench — 10-iteration cold pom-heavy walk on the
# v2.2.0 shipped default config (h2 + prefetch). Computes a true
# mean / median / p95 / stdev over 10 cold-cache runs of the user's exact
# command from the perf-pack spec.
#
# Pre-flight (verified manually before invocation):
#   - pantera + nginx + pantera-db running
#   - /tmp/settings-pantera.xml points to localhost:8081/maven_group
#   - sequential-only group fanout in effect (Part A of the perf-pack)
#
# Run from repo root:
#   ./performance/scripts/cold-bench-10x.sh
#
set -euo pipefail

RUNS="${RUNS:-10}"
SETTINGS="${SETTINGS:-/tmp/settings-pantera.xml}"
LOCAL_REPO="${LOCAL_REPO:-/tmp/m2-perftest-pantera}"
RESULTS_FILE="${RESULTS_FILE:-performance/results/cold-bench-10x.csv}"
SUMMARY_FILE="${SUMMARY_FILE:-performance/results/cold-bench-10x.md}"
DB_CONTAINER="${DB_CONTAINER:-pantera-db}"
COMPOSE="${COMPOSE:-pantera-main/docker-compose/docker-compose.yaml}"
ARTIFACT_GAV="${ARTIFACT_GAV:-org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121}"
# Run mvn from an empty directory so it doesn't pick up the project's
# multi-module pom.xml (which would try to resolve sibling-module deps
# like com.auto1.pantera:pantera-storage-core:2.2.0 and fail). The
# `-Dartifact=...` form of dependency:resolve only needs a pomless
# directory plus the user's --settings file.
MVN_RUN_DIR="${MVN_RUN_DIR:-/tmp/pantera-cold-bench-mvn-cwd}"

mkdir -p "$(dirname "$RESULTS_FILE")"
mkdir -p "$MVN_RUN_DIR"
echo "run,wall_seconds,mvn_rc" > "$RESULTS_FILE"

reset_state () {
    docker exec "$DB_CONTAINER" psql -U pantera -d pantera \
        -c "TRUNCATE artifacts;" >/dev/null
    rm -rf "$LOCAL_REPO" 2>/dev/null || true
    rm -rf pantera-main/docker-compose/pantera/data/maven_proxy 2>/dev/null || true
    rm -rf pantera-main/docker-compose/pantera/data/groovy 2>/dev/null || true
    docker compose -f "$COMPOSE" restart pantera >/dev/null
    # Wait for metrics endpoint (8087) which AsyncMetricVerticle binds at
    # the very end of boot — once it answers the request path is up.
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

for i in $(seq 1 "$RUNS"); do
    echo "=== Run $i / $RUNS ==="
    reset_state
    t0=$(date +%s.%N)
    set +e
    ( cd "$MVN_RUN_DIR" && mvn -B -s "$SETTINGS" \
        -Dmaven.repo.local="$LOCAL_REPO" \
        dependency:resolve \
        -Dartifact="${ARTIFACT_GAV}" \
        >/tmp/cold-bench-10x-mvn-${i}.log 2>&1 )
    rc=$?
    set -e
    t1=$(date +%s.%N)
    wall=$(echo "$t1 - $t0" | bc)
    printf '  wall=%.2fs rc=%d\n' "$wall" "$rc"
    printf '%d,%.3f,%d\n' "$i" "$wall" "$rc" >> "$RESULTS_FILE"
done

echo
echo "=== Summary ==="
python3 - "$RESULTS_FILE" "$SUMMARY_FILE" <<'PY'
import csv, os, statistics, sys, datetime, subprocess
results_csv = sys.argv[1]
summary_md  = sys.argv[2]

walls = []
failed = 0
total = 0
with open(results_csv) as f:
    for row in csv.DictReader(f):
        total += 1
        if int(row["mvn_rc"]) == 0:
            walls.append(float(row["wall_seconds"]))
        else:
            failed += 1

walls.sort()

def fmt(label, v): print(f"{label:8s}{v:.2f}s")
print(f"runs:   {len(walls)} ok  ({failed} failed of {total})")
fmt("min:", min(walls))
fmt("p50:", statistics.median(walls))
fmt("mean:", statistics.mean(walls))
if len(walls) >= 2:
    fmt("stdev:", statistics.stdev(walls))
# p95 = ceil(0.95 * n) - 1 index, sorted ascending
p95_idx = max(0, min(len(walls) - 1, int(round(0.95 * len(walls))) - 1))
fmt("p95:", walls[p95_idx])
fmt("max:", max(walls))

try:
    head = subprocess.check_output(
        ["git", "rev-parse", "--short", "HEAD"], text=True
    ).strip()
except Exception:
    head = "unknown"

with open(summary_md, "w") as out:
    out.write(f"# 10-iteration cold pom-heavy bench — v2.2.0 perf-pack\n\n")
    out.write(f"- Date: {datetime.datetime.utcnow().isoformat()}Z\n")
    out.write(f"- Pantera HEAD: `{head}`\n")
    out.write(f"- Command: `mvn dependency:resolve "
              f"-Dartifact={os.environ.get('ARTIFACT_GAV', 'org.codehaus.mojo:sonar-maven-plugin:4.0.0.4121')}`\n")
    out.write(f"- Through: `maven_group -> remotes -> maven_proxy -> Maven Central`\n")
    out.write(f"- Per-run cold reset: TRUNCATE artifacts, "
              f"clear local m2 + maven_proxy/groovy fs cache, "
              f"`docker compose restart pantera`\n")
    out.write(f"- Successful runs: {len(walls)} / {total} "
              f"({failed} failed)\n\n")
    out.write("| run | wall (s) | rc |\n|---|---|---|\n")
    with open(results_csv) as f:
        for row in csv.DictReader(f):
            out.write(f"| {row['run']} | "
                      f"{float(row['wall_seconds']):.2f} | "
                      f"{row['mvn_rc']} |\n")
    out.write("\n## Statistics (excluding failed runs)\n\n")
    out.write(f"- min:   {min(walls):.2f}s\n")
    out.write(f"- p50:   {statistics.median(walls):.2f}s\n")
    out.write(f"- mean:  {statistics.mean(walls):.2f}s\n")
    if len(walls) >= 2:
        out.write(f"- stdev: {statistics.stdev(walls):.2f}s\n")
    out.write(f"- p95:   {walls[p95_idx]:.2f}s\n")
    out.write(f"- max:   {max(walls):.2f}s\n")
PY

echo
echo "Results: $RESULTS_FILE"
echo "Summary: $SUMMARY_FILE"
