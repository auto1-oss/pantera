#!/usr/bin/env bash
##
## Pantera Benchmark v3 — Real Client Traffic
##
## Orchestrates infrastructure and runs real package manager clients
## (mvn, npm, docker, composer, pip/twine) against Pantera.
##
## Usage:
##   ./bench.sh                          # Full benchmark (v2.0.0, all protocols)
##   ./bench.sh --version old            # Test v1.20.12 only
##   ./bench.sh --version new            # Test v2.0.0 only
##   ./bench.sh --version both           # Test both versions sequentially
##   ./bench.sh --teardown               # Stop infrastructure
##   ./bench.sh --writers 50 --readers 50 --duration 30
##
## Environment:
##   PANTERA_OLD_IMAGE   v1.20.12 image (default: ECR artipie:1.20.12)
##   PANTERA_NEW_IMAGE   v2.0.0 image (default: pantera:2.0.0)
##   BENCH_WRITERS       Writers per protocol (default: 100)
##   BENCH_READERS       Readers per protocol (default: 100)
##   BENCH_DURATION      Seconds per protocol (default: 60)
##   BENCH_PROTOCOLS     Comma-separated (default: maven,npm,docker,php,pypi)
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"

# Images
OLD_IMAGE="${PANTERA_OLD_IMAGE:-167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.20.12}"
NEW_IMAGE="${PANTERA_NEW_IMAGE:-pantera:2.0.0}"

# Benchmark parameters (passed through to sidecar)
export BENCH_CONCURRENCY="${BENCH_CONCURRENCY:-40}"  # 80% readers, 20% writers
export BENCH_DURATION="${BENCH_DURATION:-60}"
export BENCH_PROTOCOLS="${BENCH_PROTOCOLS:-maven,npm,docker,php,pypi}"

# Auth
export PANTERA_USER_NAME="${PANTERA_USER_NAME:-pantera}"
export PANTERA_USER_PASS="${PANTERA_USER_PASS:-pantera}"

# Parse args
VERSION_FILTER="new"
TEARDOWN_ONLY=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version)    VERSION_FILTER="$2"; shift 2 ;;
        --concurrency) export BENCH_CONCURRENCY="$2"; shift 2 ;;
        --duration)    export BENCH_DURATION="$2"; shift 2 ;;
        --protocols)  export BENCH_PROTOCOLS="$2"; shift 2 ;;
        --teardown)   TEARDOWN_ONLY=true; shift ;;
        -h|--help)    head -20 "$0" | grep '^##' | sed 's/^## \?//'; exit 0 ;;
        *) echo "Unknown: $1"; exit 1 ;;
    esac
done

log() { echo ""; echo "================================================================"; echo "  $*"; echo "================================================================"; }
info() { echo "  [INFO] $*"; }
warn() { echo "  [WARN] $*"; }

compose() {
    docker compose -f "${SCRIPT_DIR}/docker-compose-bench.yml" "$@"
}

teardown() {
    cd "$SCRIPT_DIR"
    export PANTERA_IMAGE="${PANTERA_IMAGE:-pantera:2.0.0}"
    export CONFIG_DIR="${CONFIG_DIR:-/etc/pantera}"
    export DATA_DIR="${DATA_DIR:-/var/pantera}"
    export CONFIG_FILE="${CONFIG_FILE:-pantera.yml}"
    export PANTERA_CONFIG="${PANTERA_CONFIG:-pantera-new.yml}"
    export REPOS_DIR="${REPOS_DIR:-repos-new}"
    compose down -v 2>/dev/null || true
}

run_version() {
    local label="$1" image="$2" config="$3" repos="$4"
    local config_dir="${5:-/etc/pantera}" data_dir="${6:-/var/pantera}" config_file="${7:-pantera.yml}"

    log "Starting ${label} (${image}) — 8 CPU / 16 GB"

    cd "$SCRIPT_DIR"
    # Ensure clean state
    export PANTERA_IMAGE="$image"
    export PANTERA_CONFIG="$config"
    export REPOS_DIR="$repos"
    export CONFIG_DIR="$config_dir"
    export DATA_DIR="$data_dir"
    export CONFIG_FILE="$config_file"
    compose down -v 2>/dev/null || true
    sleep 2

    # Build bench-tools and start everything
    compose build bench-tools 2>&1 | tail -5
    compose up -d postgres valkey pantera

    info "Waiting for Pantera health check..."
    for i in $(seq 1 120); do
        if curl -sf "http://localhost:9080/.health" > /dev/null 2>&1; then
            info "Healthy after ${i}s"
            break
        fi
        if [[ $i -eq 120 ]]; then
            warn "TIMEOUT waiting for Pantera"
            compose logs pantera | tail -30
            teardown
            exit 1
        fi
        sleep 1
    done

    # JVM warmup
    info "JVM warmup (5s)..."
    for _ in $(seq 1 50); do
        curl -sf "http://localhost:9080/.health" > /dev/null 2>&1 || true
    done
    sleep 5

    # Run bench-tools sidecar
    log "Running benchmark for ${label}..."
    mkdir -p "${RESULTS_DIR}/${label}"
    # Mount version-specific results dir
    BENCH_SKIP_CORRECTNESS="${BENCH_SKIP_CORRECTNESS:-0}" \
    compose run --rm \
        -e BENCH_SKIP_CORRECTNESS="${BENCH_SKIP_CORRECTNESS:-0}" \
        -v "${RESULTS_DIR}/${label}:/results" \
        bench-tools || {
        warn "Benchmark run failed for ${label}"
        compose logs pantera | tail -50
    }

    info "Results for ${label}: ${RESULTS_DIR}/${label}/"
    teardown
    sleep 5
}

generate_comparison() {
    local report="${RESULTS_DIR}/BENCHMARK-REPORT.md"

    if [[ -f "${RESULTS_DIR}/v1.20.12/stats.csv" && -f "${RESULTS_DIR}/v2.0.0/stats.csv" ]]; then
        log "Generating comparison report"
        cat > "$report" << 'EOF'
# Pantera Benchmark v3 — v1.20.12 vs v2.0.0 Comparison

> **Method:** Real package manager clients (mvn, npm, docker, composer, pip/twine).
> Each writer deploys a unique artifact. Readers resolve pre-seeded artifacts.

---

## Comparison

| Protocol | Operation | v1.20.12 RPS | v2.0.0 RPS | Delta | v1.20.12 P95 | v2.0.0 P95 | v1.20.12 Err | v2.0.0 Err |
|----------|-----------|-------------|-----------|-------|-------------|-----------|-------------|-----------|
EOF

        # Merge both stats CSVs
        tail -n +2 "${RESULTS_DIR}/v1.20.12/stats.csv" | while IFS=, read -r proto op total rps avg p50 p95 p99 mx err; do
            local key="${proto},${op}"
            local new_line
            new_line=$(grep "^${key}," "${RESULTS_DIR}/v2.0.0/stats.csv" 2>/dev/null || echo "")
            if [[ -n "$new_line" ]]; then
                IFS=, read -r _ _ ntotal nrps navg np50 np95 np99 nmx nerr <<< "$new_line"
                local delta="--"
                if (( $(echo "$rps > 0" | bc -l 2>/dev/null || echo 0) )); then
                    delta=$(echo "scale=1; ($nrps - $rps) * 100 / $rps" | bc 2>/dev/null || echo "--")
                    [[ "${delta:0:1}" != "-" ]] && delta="+${delta}"
                    delta="${delta}%"
                fi
                printf "| %s | %s | %s | %s | %s | %s | %s | %s%% | %s%% |\n" \
                    "$proto" "$op" "$rps" "$nrps" "$delta" "$p95" "$np95" "$err" "$nerr" >> "$report"
            fi
        done

        cat >> "$report" << EOF

---

## Environment

| Parameter | Value |
|-----------|-------|
| v1.20.12 image | \`${OLD_IMAGE}\` |
| v2.0.0 image | \`${NEW_IMAGE}\` |
| CPU | 8 per instance |
| RAM | 16 GB per instance |
| Writers | ${BENCH_WRITERS} per protocol |
| Readers | ${BENCH_READERS} per protocol |
| Duration | ${BENCH_DURATION}s per protocol |
| Protocols | ${BENCH_PROTOCOLS} |

*Generated by benchmark v3 (\`bench.sh\`).*
EOF
        info "Comparison report: ${report}"
    fi
}

# ================================================================
# Main
# ================================================================
main() {
    echo ""
    echo "  Pantera Benchmark v3 — Real Client Traffic"
    echo "  OLD: ${OLD_IMAGE}"
    echo "  NEW: ${NEW_IMAGE}"
    echo "  Concurrency: ${BENCH_CONCURRENCY} (80% read / 20% write)"
    echo "  Duration: ${BENCH_DURATION}s, Protocols: ${BENCH_PROTOCOLS}"
    echo ""

    if $TEARDOWN_ONLY; then teardown; exit 0; fi

    for cmd in docker curl bc; do
        command -v "$cmd" >/dev/null 2>&1 || { echo "Missing: $cmd"; exit 1; }
    done

    mkdir -p "$RESULTS_DIR"

    if [[ "$VERSION_FILTER" == "both" || "$VERSION_FILTER" == "old" ]]; then
        run_version "v1.20.12" "$OLD_IMAGE" "pantera-old.yml" "repos-old" \
            "/etc/artipie" "/var/artipie" "artipie.yml"
    fi

    if [[ "$VERSION_FILTER" == "both" || "$VERSION_FILTER" == "new" ]]; then
        run_version "v2.0.0" "$NEW_IMAGE" "pantera-new.yml" "repos-new" \
            "/etc/pantera" "/var/pantera" "pantera.yml"
    fi

    if [[ "$VERSION_FILTER" == "both" ]]; then
        generate_comparison
    fi

    log "BENCHMARK COMPLETE"
    echo ""
    echo "  Results: ${RESULTS_DIR}/"
    ls -la "${RESULTS_DIR}/"
}

main "$@"
