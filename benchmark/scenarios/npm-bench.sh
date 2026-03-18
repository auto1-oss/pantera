#!/usr/bin/env bash
##
## NPM benchmark scenarios using the real npm client:
##   1. Local Publish  — npm publish to local npm repo
##   2. Group Install  — npm install through npm_group (local package)
##   3. Group Proxy Install — npm install through npm_group (→ npmjs.org)
##   4. Mixed Read+Write — concurrent publish + install through npm_group
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="${BENCH_DIR}/results"
FIXTURES_DIR="${BENCH_DIR}/fixtures"

source "${SCRIPT_DIR}/common.sh"

OLD_PORT="${OLD_PORT:-9081}"
NEW_PORT="${NEW_PORT:-9091}"
ARTIPIE_USER="${ARTIPIE_USER_NAME:-artipie}"
ARTIPIE_PASS="${ARTIPIE_USER_PASS:-artipie}"

# Higher concurrency to push 50-200 req/s through the group
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-1 10 20 50 100 200}"
ITERATIONS="${NPM_ITERATIONS:-10}"

npm_results="${RESULTS_DIR}/npm.csv"
mkdir -p "$RESULTS_DIR"
csv_header "$npm_results"

TIMING_BASE=$(mktemp -d)
trap 'rm -rf "$TIMING_BASE"' EXIT

AUTH_TOKEN=$(echo -n "${ARTIPIE_USER}:${ARTIPIE_PASS}" | base64)

# ============================================================
# Helpers
# ============================================================

make_npmrc() {
    local port="$1" repo_path="$2" output_file="$3"
    cat > "$output_file" <<EOF
registry=http://localhost:${port}/${repo_path}/
//localhost:${port}/${repo_path}/:_auth=${AUTH_TOKEN}
//localhost:${port}/${repo_path}/:always-auth=true
EOF
}

npm_publish_pkg() {
    local pkg_dir="$1" port="$2" repo_path="$3"
    local npmrc="${TIMING_BASE}/.npmrc-pub-${$}-${RANDOM}"
    make_npmrc "$port" "$repo_path" "$npmrc"

    npm publish "$pkg_dir" \
        --registry="http://localhost:${port}/${repo_path}/" \
        --userconfig="$npmrc" \
        --no-git-checks 2>&1

    rm -f "$npmrc"
}

npm_install_pkg() {
    local pkg_name="$1" port="$2" repo_path="$3" worker_id="$4"
    local work_dir="${TIMING_BASE}/npm-install-${worker_id}-${$}-${RANDOM}"
    rm -rf "$work_dir"
    mkdir -p "$work_dir"

    local npmrc="${work_dir}/.npmrc"
    make_npmrc "$port" "$repo_path" "$npmrc"

    cat > "$work_dir/package.json" <<EOF
{"name":"bench-consumer-${worker_id}","version":"1.0.0","private":true}
EOF

    cd "$work_dir"
    npm install "$pkg_name" \
        --registry="http://localhost:${port}/${repo_path}/" \
        --userconfig="$npmrc" \
        --no-audit --no-fund --no-update-notifier \
        --prefer-online \
        --cache="${work_dir}/.npm-cache" 2>&1
    local rc=$?
    cd - >/dev/null

    rm -rf "$work_dir"
    return $rc
}

# ============================================================
# SCENARIO 1: NPM Local Publish
# ============================================================
run_npm_publish() {
    bench_log "=== Scenario 1: NPM Local Publish ==="

    local pkg_template_dir="${FIXTURES_DIR}/npm-package"
    if [[ ! -d "$pkg_template_dir" ]]; then
        bench_log "  No NPM package fixture found, skipping"
        return
    fi

    for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
        IFS=: read -r tag port version <<< "$version_info"

        bench_log "  Publish packages → ${version} (${ITERATIONS} ops)..."

        local tdir="${TIMING_BASE}/npm-publish-${tag}"
        mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

        local wall_start wall_end
        wall_start=$(now_ms)

        # Use epoch-based version to avoid collisions with previous runs
        local epoch_base
        epoch_base=$(date +%s)

        for i in $(seq 1 "$ITERATIONS"); do
            local pkg_dir="${TIMING_BASE}/npm-pub-pkg-${i}"
            cp -r "$pkg_template_dir" "$pkg_dir"
            local pkg_version="${epoch_base}.0.${i}"
            local pkg_name="@bench/perf-test"
            cat > "$pkg_dir/package.json" <<PKGJSON
{
  "name": "${pkg_name}",
  "version": "${pkg_version}",
  "description": "Benchmark package v${pkg_version}",
  "main": "index.js",
  "license": "MIT"
}
PKGJSON

            local s e rc
            s=$(now_ms)
            npm_publish_pkg "$pkg_dir" "$port" "npm" >/dev/null 2>&1 && rc=0 || rc=$?
            e=$(now_ms)
            echo "$((e - s)) $rc" > "$tdir/op-${i}.txt"

            rm -rf "$pkg_dir"
        done

        wall_end=$(now_ms)

        csv_row "$npm_results" "npm-local-publish" "$version" "package" "1" \
            "$tdir" "$((wall_end - wall_start))" "0"
    done
}

# ============================================================
# SCENARIO 2: NPM Group Install (npm_group → local member)
# ============================================================
run_npm_install() {
    bench_log "=== Scenario 2: NPM Group Install (npm_group → local member) ==="

    local pkg_name="@bench/perf-test"

    for version_info in "old:${OLD_PORT}" "new:${NEW_PORT}"; do
        IFS=: read -r tag port <<< "$version_info"
        local check_code
        check_code=$(curl -s -o /dev/null -w '%{http_code}' \
            "http://localhost:${port}/npm/@bench%2fperf-test" \
            -H "Authorization: Basic ${AUTH_TOKEN}" 2>/dev/null) || true
        if [[ "$check_code" != "200" ]]; then
            bench_log "  WARNING: Package not found on ${tag} (HTTP ${check_code}). Publish may have failed."
        fi
    done

    export TIMING_BASE AUTH_TOKEN
    export -f npm_install_pkg make_npmrc now_ms

    for conc in $CONCURRENCY_LEVELS; do
        local ops=$conc  # one full round at each concurrency level
        [[ $ops -lt $ITERATIONS ]] && ops=$ITERATIONS

        for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
            IFS=: read -r tag port version <<< "$version_info"

            bench_log "  Group install c=${conc} ${version} (${ops} ops)..."

            local tdir="${TIMING_BASE}/npm-install-c${conc}-${tag}"
            mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start wall_end
            wall_start=$(now_ms)

            local rounds=$(( (ops + conc - 1) / conc ))
            local op_id=0
            for round in $(seq 1 "$rounds"); do
                local batch=$conc remaining=$((ops - op_id))
                [[ $remaining -lt $batch ]] && batch=$remaining
                [[ $batch -le 0 ]] && break

                for w in $(seq 1 "$batch"); do
                    op_id=$((op_id + 1))
                    local oid=$op_id
                    (
                        local s e rc
                        s=$(now_ms)
                        npm_install_pkg "$pkg_name" "$port" "npm_group" "$oid" >/dev/null 2>&1 && rc=0 || rc=$?
                        e=$(now_ms)
                        echo "$((e - s)) $rc" > "$tdir/op-${oid}.txt"
                    ) &
                done
                wait
            done

            wall_end=$(now_ms)

            csv_row "$npm_results" "npm-group-install" "$version" "package" "$conc" \
                "$tdir" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# SCENARIO 3: NPM Proxy Install (through npm_group → npmjs.org)
# ============================================================
run_npm_proxy() {
    bench_log "=== Scenario 3: NPM Proxy Install (npm_group → npmjs.org) ==="

    local -a PROXY_PACKAGES=(
        "ms"
        "isarray"
        "escape-html"
    )

    bench_log "  Warming proxy cache..."
    for pkg in "${PROXY_PACKAGES[@]}"; do
        for version_info in "old:${OLD_PORT}" "new:${NEW_PORT}"; do
            IFS=: read -r tag port <<< "$version_info"
            npm_install_pkg "$pkg" "$port" "npm_group" "warm-${tag}" >/dev/null 2>&1 || true
        done
    done
    sleep 2

    local test_pkg="${PROXY_PACKAGES[0]}"
    bench_log "  Benchmarking warm proxy installs for ${test_pkg}..."

    for conc in $CONCURRENCY_LEVELS; do
        local ops=$conc
        [[ $ops -lt $ITERATIONS ]] && ops=$ITERATIONS

        for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
            IFS=: read -r tag port version <<< "$version_info"

            bench_log "  Proxy install c=${conc} ${version} (${ops} ops)..."

            local tdir="${TIMING_BASE}/npm-proxy-c${conc}-${tag}"
            mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start wall_end
            wall_start=$(now_ms)

            local rounds=$(( (ops + conc - 1) / conc ))
            local op_id=0
            for round in $(seq 1 "$rounds"); do
                local batch=$conc remaining=$((ops - op_id))
                [[ $remaining -lt $batch ]] && batch=$remaining
                [[ $batch -le 0 ]] && break

                for w in $(seq 1 "$batch"); do
                    op_id=$((op_id + 1))
                    local oid=$op_id
                    (
                        local s e rc
                        s=$(now_ms)
                        npm_install_pkg "$test_pkg" "$port" "npm_group" "$oid" >/dev/null 2>&1 && rc=0 || rc=$?
                        e=$(now_ms)
                        echo "$((e - s)) $rc" > "$tdir/op-${oid}.txt"
                    ) &
                done
                wait
            done

            wall_end=$(now_ms)

            csv_row "$npm_results" "npm-proxy-install" "$version" "proxy" "$conc" \
                "$tdir" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# SCENARIO 4: Mixed Read+Write (concurrent publish + group install)
# ============================================================
run_npm_mixed() {
    bench_log "=== Scenario 4: NPM Mixed Read+Write (publish + group install) ==="

    local pkg_template_dir="${FIXTURES_DIR}/npm-package"
    [[ -d "$pkg_template_dir" ]] || { bench_log "  No NPM fixture, skipping"; return; }

    local pkg_name="@bench/perf-test"

    export TIMING_BASE AUTH_TOKEN FIXTURES_DIR
    export -f npm_install_pkg npm_publish_pkg make_npmrc now_ms

    # Mixed concurrency: N readers + N writers simultaneously
    for conc in $CONCURRENCY_LEVELS; do
        local readers=$conc
        local writers=$(( conc > 10 ? conc / 5 : (conc > 1 ? conc / 2 : 1) ))
        # Write ratio: ~20% writes at high concurrency, ~50% at low
        local total=$((readers + writers))

        for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
            IFS=: read -r tag port version <<< "$version_info"

            bench_log "  Mixed c=${total} (${readers}R+${writers}W) ${version}..."

            local tdir_r="${TIMING_BASE}/npm-mixed-read-c${conc}-${tag}"
            local tdir_w="${TIMING_BASE}/npm-mixed-write-c${conc}-${tag}"
            mkdir -p "$tdir_r" "$tdir_w"
            find "$tdir_r" -name 'op-*.txt' -delete 2>/dev/null || true
            find "$tdir_w" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start wall_end
            wall_start=$(now_ms)

            # Launch readers (install through group)
            for r in $(seq 1 "$readers"); do
                (
                    local s e rc
                    s=$(now_ms)
                    npm_install_pkg "$pkg_name" "$port" "npm_group" "mixed-r-${r}" >/dev/null 2>&1 && rc=0 || rc=$?
                    e=$(now_ms)
                    echo "$((e - s)) $rc" > "$tdir_r/op-${r}.txt"
                ) &
            done

            # Launch writers (publish unique versions to local npm)
            for w in $(seq 1 "$writers"); do
                (
                    local pkg_dir="${TIMING_BASE}/npm-mixed-pkg-${tag}-${w}-${$}-${RANDOM}"
                    cp -r "$pkg_template_dir" "$pkg_dir"
                    cat > "$pkg_dir/package.json" <<PKGJSON
{
  "name": "@bench/mixed-test-${w}",
  "version": "1.0.${$}-${RANDOM}",
  "description": "Mixed benchmark package",
  "main": "index.js",
  "license": "MIT"
}
PKGJSON
                    local s e rc
                    s=$(now_ms)
                    npm_publish_pkg "$pkg_dir" "$port" "npm" >/dev/null 2>&1 && rc=0 || rc=$?
                    e=$(now_ms)
                    echo "$((e - s)) $rc" > "$tdir_w/op-${w}.txt"
                    rm -rf "$pkg_dir"
                ) &
            done

            wait
            wall_end=$(now_ms)

            # Record reads
            csv_row "$npm_results" "npm-mixed-read" "$version" "mixed" "$conc" \
                "$tdir_r" "$((wall_end - wall_start))" "0"
            # Record writes
            csv_row "$npm_results" "npm-mixed-write" "$version" "mixed" "$conc" \
                "$tdir_w" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# Main
# ============================================================
main() {
    bench_log "Starting NPM benchmarks (real npm client)"
    bench_log "  OLD: localhost:${OLD_PORT}"
    bench_log "  NEW: localhost:${NEW_PORT}"
    bench_log "  Concurrency: ${CONCURRENCY_LEVELS}"
    bench_log "  Iterations: ${ITERATIONS}"

    if ! command -v npm >/dev/null 2>&1; then
        bench_log "ERROR: npm not found. Install Node.js first."
        exit 1
    fi

    run_npm_publish
    run_npm_install
    run_npm_proxy
    run_npm_mixed

    bench_log "NPM benchmarks complete. Results in ${npm_results}"
}

main "$@"
