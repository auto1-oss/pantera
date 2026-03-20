#!/usr/bin/env bash
##
## Maven benchmark scenarios using the real mvn client:
##   1. Local Upload   — mvn deploy:deploy-file (JARs of 1KB, 1MB, 10MB)
##   2. Group Download — mvn dependency:copy through maven_group (local artifact)
##   3. Group Proxy Download — mvn dependency:copy through maven_group (→ Central)
##   4. Mixed Read+Write — concurrent upload + group download
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="${BENCH_DIR}/results"
FIXTURES_DIR="${BENCH_DIR}/fixtures"
SETUP_DIR="${BENCH_DIR}/setup"

source "${SCRIPT_DIR}/common.sh"

OLD_PORT="${OLD_PORT:-9081}"
NEW_PORT="${NEW_PORT:-9091}"
SETTINGS_OLD="${SETUP_DIR}/settings-old.xml"
SETTINGS_NEW="${SETUP_DIR}/settings-new.xml"

# Higher concurrency — each mvn JVM makes 6-10 HTTP requests through group fan-out,
# so c=50 generates ~300-500 concurrent HTTP requests to Pantera
CONCURRENCY_LEVELS="${CONCURRENCY_LEVELS:-1 5 10 20 50}"
ITERATIONS="${MAVEN_ITERATIONS:-10}"

maven_results="${RESULTS_DIR}/maven.csv"
mkdir -p "$RESULTS_DIR"
csv_header "$maven_results"

TIMING_BASE=$(mktemp -d)
trap 'rm -rf "$TIMING_BASE"' EXIT

# ============================================================
# Helpers
# ============================================================

mvn_deploy() {
    local settings="$1" port="$2" jar="$3" gid="$4" aid="$5" ver="$6"
    mvn deploy:deploy-file -B -q \
        -Daether.connector.http.expectContinue=false \
        -s "$settings" \
        -DgroupId="$gid" \
        -DartifactId="$aid" \
        -Dversion="$ver" \
        -Dpackaging=jar \
        -Dfile="$jar" \
        -DrepositoryId=pantera \
        -Durl="http://localhost:${port}/maven" \
        -DgeneratePom=true
}

mvn_download() {
    local settings="$1" profile="$2" artifact="$3" worker_id="$4"
    local local_repo="${TIMING_BASE}/m2-${worker_id}-${$}-${RANDOM}"
    rm -rf "$local_repo"
    mkdir -p "$local_repo"
    local out_dir="${TIMING_BASE}/out-${worker_id}-${$}-${RANDOM}"
    mkdir -p "$out_dir"

    mvn dependency:copy -B -q \
        -Daether.connector.http.expectContinue=false \
        -s "$settings" \
        -P "$profile" \
        -Dmaven.repo.local="$local_repo" \
        -Dartifact="$artifact" \
        -DoutputDirectory="$out_dir"

    rm -rf "$local_repo" "$out_dir"
}

# ============================================================
# SCENARIO 1: Maven Local Upload (deploy:deploy-file)
# ============================================================
run_maven_upload() {
    bench_log "=== Scenario 1: Maven Local Upload ==="

    for size_label in 1KB 1MB 10MB; do
        local jar_file="${FIXTURES_DIR}/maven-artifact-${size_label}.jar"
        [[ -f "$jar_file" ]] || { bench_log "  Skipping $size_label (no fixture)"; continue; }
        local jar_bytes
        jar_bytes=$(wc -c < "$jar_file" | tr -d ' ')

        local ops=$ITERATIONS
        case "$size_label" in
            10MB) ops=$(( ITERATIONS > 5 ? 5 : ITERATIONS )) ;;
        esac

        for version_info in "old:${OLD_PORT}:v1.20.12:${SETTINGS_OLD}" "new:${NEW_PORT}:v1.22.0:${SETTINGS_NEW}"; do
            IFS=: read -r tag port version settings <<< "$version_info"
            bench_log "  Upload ${size_label} → ${version} (${ops} ops)..."

            local tdir="${TIMING_BASE}/maven-upload-${size_label}-${tag}"
            mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start
            wall_start=$(now_ms)

            for i in $(seq 1 "$ops"); do
                local start end rc
                start=$(now_ms)
                mvn_deploy "$settings" "$port" "$jar_file" \
                    "com.bench.upload" "artifact-${size_label}" "1.0.${i}" >/dev/null 2>&1 && rc=0 || rc=$?
                end=$(now_ms)
                echo "$((end - start)) $rc" > "$tdir/op-${i}.txt"
            done

            local wall_end
            wall_end=$(now_ms)

            csv_row "$maven_results" "maven-local-upload" "$version" "$size_label" "1" \
                "$tdir" "$((wall_end - wall_start))" "$jar_bytes"
        done
    done
}

# ============================================================
# SCENARIO 2: Maven Group Download (dependency:copy via maven_group)
# ============================================================
run_maven_download() {
    bench_log "=== Scenario 2: Maven Group Download (maven_group → local member) ==="

    # Seed: deploy artifacts to local maven repo for download testing
    for size_label in 1KB 1MB 10MB; do
        local jar_file="${FIXTURES_DIR}/maven-artifact-${size_label}.jar"
        [[ -f "$jar_file" ]] || continue
        for version_info in "old:${OLD_PORT}:${SETTINGS_OLD}" "new:${NEW_PORT}:${SETTINGS_NEW}"; do
            IFS=: read -r tag port settings <<< "$version_info"
            bench_log "  Seeding ${size_label} to ${tag}..."
            mvn_deploy "$settings" "$port" "$jar_file" \
                "com.bench.download" "dl-artifact-${size_label}" "1.0.0" >/dev/null 2>&1 || true
        done
    done
    sleep 2

    export TIMING_BASE SETTINGS_OLD SETTINGS_NEW
    export -f mvn_download now_ms

    # Focus on 1MB — large enough to be meaningful, small enough for many iterations
    for size_label in 1MB 10MB; do
        local jar_file="${FIXTURES_DIR}/maven-artifact-${size_label}.jar"
        [[ -f "$jar_file" ]] || continue
        local jar_bytes
        jar_bytes=$(wc -c < "$jar_file" | tr -d ' ')
        local artifact="com.bench.download:dl-artifact-${size_label}:1.0.0"

        for conc in $CONCURRENCY_LEVELS; do
            local ops=$conc  # one full round at each concurrency level
            [[ $ops -lt $ITERATIONS ]] && ops=$ITERATIONS

            for version_info in "old:${OLD_PORT}:v1.20.12:${SETTINGS_OLD}:bench-group" "new:${NEW_PORT}:v1.22.0:${SETTINGS_NEW}:bench-group"; do
                IFS=: read -r tag port version settings profile <<< "$version_info"

                bench_log "  Group download ${size_label} c=${conc} ${version} (${ops} ops)..."

                local tdir="${TIMING_BASE}/maven-download-${size_label}-c${conc}-${tag}"
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
                            local s e r
                            s=$(now_ms)
                            mvn_download "$settings" "$profile" "$artifact" "$oid" >/dev/null 2>&1 && r=0 || r=$?
                            e=$(now_ms)
                            echo "$((e - s)) $r" > "$tdir/op-${oid}.txt"
                        ) &
                    done
                    wait
                done

                wall_end=$(now_ms)

                csv_row "$maven_results" "maven-group-download" "$version" "$size_label" "$conc" \
                    "$tdir" "$((wall_end - wall_start))" "$jar_bytes"
            done
        done
    done
}

# ============================================================
# SCENARIO 3: Maven Proxy Download (through maven_group → Central)
# ============================================================
run_maven_proxy() {
    bench_log "=== Scenario 3: Maven Proxy Download (maven_group → Central) ==="

    local -a PROXY_ARTIFACTS=(
        "commons-io:commons-io:2.15.1"
        "com.google.code.gson:gson:2.10.1"
        "org.slf4j:slf4j-api:2.0.11"
    )

    bench_log "  Warming proxy cache..."
    for artifact in "${PROXY_ARTIFACTS[@]}"; do
        for version_info in "old:${SETTINGS_OLD}" "new:${SETTINGS_NEW}"; do
            IFS=: read -r tag settings <<< "$version_info"
            local warm_repo="${TIMING_BASE}/m2-warm-${tag}-$$"
            mvn dependency:copy -B -q \
                -s "$settings" -P bench-group \
                -Dmaven.repo.local="$warm_repo" \
                -Dartifact="$artifact" \
                -DoutputDirectory="${TIMING_BASE}/warm-out" >/dev/null 2>&1 || true
            rm -rf "$warm_repo" "${TIMING_BASE}/warm-out"
        done
    done
    sleep 2

    local artifact="${PROXY_ARTIFACTS[0]}"
    bench_log "  Benchmarking warm proxy reads for ${artifact}..."

    for conc in $CONCURRENCY_LEVELS; do
        local ops=$conc
        [[ $ops -lt $ITERATIONS ]] && ops=$ITERATIONS

        for version_info in "old:${OLD_PORT}:v1.20.12:${SETTINGS_OLD}" "new:${NEW_PORT}:v1.22.0:${SETTINGS_NEW}"; do
            IFS=: read -r tag port version settings <<< "$version_info"

            bench_log "  Proxy download c=${conc} ${version} (${ops} ops)..."

            local tdir="${TIMING_BASE}/maven-proxy-c${conc}-${tag}"
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
                        local s e r
                        s=$(now_ms)
                        mvn_download "$settings" "bench-group" "$artifact" "$oid" >/dev/null 2>&1
                        r=$?
                        e=$(now_ms)
                        echo "$((e - s)) $r" > "$tdir/op-${oid}.txt"
                    ) &
                done
                wait
            done

            wall_end=$(now_ms)

            csv_row "$maven_results" "maven-proxy-download" "$version" "proxy" "$conc" \
                "$tdir" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# SCENARIO 4: Mixed Read+Write (concurrent upload + group download)
# ============================================================
run_maven_mixed() {
    bench_log "=== Scenario 4: Maven Mixed Read+Write (upload + group download) ==="

    local jar_file="${FIXTURES_DIR}/maven-artifact-1MB.jar"
    [[ -f "$jar_file" ]] || { bench_log "  No 1MB fixture, skipping"; return; }

    local artifact="com.bench.download:dl-artifact-1MB:1.0.0"

    export TIMING_BASE SETTINGS_OLD SETTINGS_NEW FIXTURES_DIR
    export -f mvn_deploy mvn_download now_ms

    for conc in $CONCURRENCY_LEVELS; do
        local readers=$conc
        local writers=$(( conc > 10 ? conc / 5 : (conc > 1 ? conc / 2 : 1) ))
        local total=$((readers + writers))

        for version_info in "old:${OLD_PORT}:v1.20.12:${SETTINGS_OLD}" "new:${NEW_PORT}:v1.22.0:${SETTINGS_NEW}"; do
            IFS=: read -r tag port version settings <<< "$version_info"

            bench_log "  Mixed c=${total} (${readers}R+${writers}W) ${version}..."

            local tdir_r="${TIMING_BASE}/maven-mixed-read-c${conc}-${tag}"
            local tdir_w="${TIMING_BASE}/maven-mixed-write-c${conc}-${tag}"
            mkdir -p "$tdir_r" "$tdir_w"
            find "$tdir_r" -name 'op-*.txt' -delete 2>/dev/null || true
            find "$tdir_w" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start wall_end
            wall_start=$(now_ms)

            # Launch readers (download through maven_group)
            for r in $(seq 1 "$readers"); do
                (
                    local s e rc
                    s=$(now_ms)
                    mvn_download "$settings" "bench-group" "$artifact" "mixed-r-${r}" >/dev/null 2>&1 && rc=0 || rc=$?
                    e=$(now_ms)
                    echo "$((e - s)) $rc" > "$tdir_r/op-${r}.txt"
                ) &
            done

            # Launch writers (deploy unique versions to local maven)
            for w in $(seq 1 "$writers"); do
                (
                    local s e rc
                    s=$(now_ms)
                    mvn_deploy "$settings" "$port" "$jar_file" \
                        "com.bench.mixed" "mixed-artifact" "1.0.${$}-${RANDOM}" >/dev/null 2>&1 && rc=0 || rc=$?
                    e=$(now_ms)
                    echo "$((e - s)) $rc" > "$tdir_w/op-${w}.txt"
                ) &
            done

            wait
            wall_end=$(now_ms)

            csv_row "$maven_results" "maven-mixed-read" "$version" "1MB" "$conc" \
                "$tdir_r" "$((wall_end - wall_start))" "0"
            csv_row "$maven_results" "maven-mixed-write" "$version" "1MB" "$conc" \
                "$tdir_w" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# Main
# ============================================================
main() {
    bench_log "Starting Maven benchmarks (real mvn client)"
    bench_log "  OLD: localhost:${OLD_PORT}"
    bench_log "  NEW: localhost:${NEW_PORT}"
    bench_log "  Concurrency: ${CONCURRENCY_LEVELS}"
    bench_log "  Iterations: ${ITERATIONS}"

    if ! command -v mvn >/dev/null 2>&1; then
        bench_log "ERROR: mvn not found. Install Maven first."
        exit 1
    fi

    run_maven_upload
    run_maven_download
    run_maven_proxy
    run_maven_mixed

    bench_log "Maven benchmarks complete. Results in ${maven_results}"
}

main "$@"
