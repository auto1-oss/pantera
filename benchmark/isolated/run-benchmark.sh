#!/usr/bin/env bash
##
## Comprehensive Isolated Performance Benchmark
##
## Tests Maven, Docker, and NPM workloads — uploads, downloads, proxy, group,
## mixed — at multiple concurrency levels and artifact sizes.
## Plus sustained HTTP load tests at 800-1000 req/s.
##
## Each image runs in COMPLETE ISOLATION with its own infrastructure restart.
##
## Usage:
##   ./run-benchmark.sh              # Full benchmark (both images)
##   ./run-benchmark.sh old          # Benchmark old image only
##   ./run-benchmark.sh new          # Benchmark new image only
##   ./run-benchmark.sh teardown     # Clean up everything
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="${SCRIPT_DIR}/results"
FIXTURES_DIR="${BENCH_DIR}/fixtures"

# --- Images ---
OLD_IMAGE="167967495118.dkr.ecr.eu-west-1.amazonaws.com/devops/artipie:1.20.12"
NEW_IMAGE="pantera:2.0.0"

# --- Container limits ---
CPUS=8
MEMORY="16g"

# --- JVM args (from spec — shared base) ---
JVM_BASE="-Xms10g -Xmx10g -XX:+UseG1GC -XX:G1HeapRegionSize=16m -XX:MaxGCPauseMillis=300 -XX:G1ReservePercent=10 -XX:InitiatingHeapOccupancyPercent=45 -XX:ParallelGCThreads=6 -XX:ConcGCThreads=2 -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=2g -Dio.netty.allocator.maxOrder=11 -Dio.netty.leakDetection.level=simple -Dvertx.max.worker.execute.time=120000000000 -Dartipie.filesystem.io.threads=14"

JVM_OLD="${JVM_BASE} -XX:HeapDumpPath=/var/artipie/logs/dumps/heapdump.hprof -Xlog:gc*:file=/var/artipie/logs/gc.log:time,uptime:filecount=5,filesize=100m -Djava.io.tmpdir=/var/artipie/cache/tmp -Dvertx.cacheDirBase=/var/artipie/cache/tmp"
JVM_NEW="${JVM_BASE} -XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof -Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m -Djava.io.tmpdir=/var/pantera/cache/tmp -Dvertx.cacheDirBase=/var/pantera/cache/tmp"

# --- Auth ---
AUTH_USER="pantera"
AUTH_PASS="pantera"
AUTH_HEADER="$(echo -n "${AUTH_USER}:${AUTH_PASS}" | base64)"

# --- Test params ---
CONCURRENCY_LEVELS="1 5 10 20 50"
MAVEN_ITERATIONS=10
NPM_ITERATIONS=10
DOCKER_ITERATIONS=5
HTTP_RATE_LEVELS="800 900 1000"
HTTP_DURATION="60s"

# --- Infrastructure ---
NETWORK="isolated_bench-iso"
SUT_NAME="bench-iso-sut"
SUT_PORT=8080
API_PORT=8086
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-isolated.yml"

# Source shared timing functions
source "${BENCH_DIR}/scenarios/common.sh"

log()  { echo ""; echo "================================================================"; echo "  $*"; echo "================================================================"; }
info() { echo "[INFO] $*"; }
warn() { echo "[WARN] $*"; }

# ==============================================================
# Infrastructure
# ==============================================================
start_infra() {
    log "Starting shared infrastructure"
    cd "$SCRIPT_DIR"
    docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
    docker compose -f "$COMPOSE_FILE" up -d
    info "Waiting for postgres..."
    for i in $(seq 1 30); do
        docker exec bench-iso-postgres pg_isready -U pantera >/dev/null 2>&1 && break
        sleep 2
    done
    info "Waiting for valkey..."
    for i in $(seq 1 15); do
        docker exec bench-iso-valkey valkey-cli ping 2>/dev/null | grep -q PONG && break
        sleep 2
    done
    info "Infrastructure ready"
}

stop_infra() {
    cd "$SCRIPT_DIR"
    docker compose -f "$COMPOSE_FILE" down -v 2>/dev/null || true
}

# ==============================================================
# SUT lifecycle
# ==============================================================
start_sut() {
    local label="$1" image="$2" jvm="$3" cfg_dir="$4" cfg_file="$5"
    local base_path="$6"  # /var/artipie or /var/pantera

    docker rm -f "$SUT_NAME" 2>/dev/null || true
    for v in bench-iso-data bench-iso-cache bench-iso-logs; do
        docker volume rm "$v" 2>/dev/null || true
        docker volume create "$v" >/dev/null
    done

    log "Starting SUT: ${label} (${image})"

    docker run -d \
        --name "$SUT_NAME" \
        --network "$NETWORK" \
        --cpus="$CPUS" \
        --memory="$MEMORY" \
        -p "${SUT_PORT}:8080" \
        -p "${API_PORT}:8086" \
        -e "JVM_ARGS=${jvm}" \
        -e "PANTERA_USER_NAME=${AUTH_USER}" \
        -e "PANTERA_USER_PASS=${AUTH_PASS}" \
        -e "ARTIPIE_USER_NAME=${AUTH_USER}" \
        -e "ARTIPIE_USER_PASS=${AUTH_PASS}" \
        -e "LOG4J_CONFIGURATION_FILE=$(dirname "$cfg_file")/log4j2.xml" \
        -e "ELASTIC_APM_ENABLED=false" \
        -v "${cfg_dir}/$(basename "$cfg_file"):${cfg_file}:ro" \
        -v "${cfg_dir}/repos:${base_path}/repo:ro" \
        -v "${cfg_dir}/security:${base_path}/security:ro" \
        -v "${BENCH_DIR}/setup/log4j2-bench.xml:$(dirname "$cfg_file")/log4j2.xml:ro" \
        -v "bench-iso-data:${base_path}/data" \
        -v "bench-iso-cache:${base_path}/cache" \
        -v "bench-iso-logs:${base_path}/logs" \
        --user "0:0" \
        "$image" >/dev/null

    info "Waiting for SUT to respond..."
    for i in $(seq 1 90); do
        if curl -sf "http://localhost:${SUT_PORT}/maven/" \
            -H "Authorization: Basic ${AUTH_HEADER}" -o /dev/null 2>/dev/null; then
            info "SUT healthy at iteration $i"
            return 0
        fi
        # Also try the root path
        local code
        code=$(curl -sf -o /dev/null -w '%{http_code}' "http://localhost:${SUT_PORT}/" 2>/dev/null || echo "000")
        if [[ "$code" != "000" ]]; then
            info "SUT responding (HTTP $code) at iteration $i"
            return 0
        fi
        sleep 2
    done
    warn "SUT startup timeout — showing logs"
    docker logs "$SUT_NAME" 2>&1 | tail -30
    return 1
}

stop_sut() {
    docker rm -f "$SUT_NAME" 2>/dev/null || true
    for v in bench-iso-data bench-iso-cache bench-iso-logs; do
        docker volume rm "$v" 2>/dev/null || true
    done
}

# ==============================================================
# Resource stats collector
# ==============================================================
start_stats() {
    local out="$1"
    echo "timestamp,cpu_pct,mem_usage,mem_pct,pids" > "$out"
    (while true; do
        local s
        s=$(docker stats "$SUT_NAME" --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}},{{.PIDs}}' 2>/dev/null || echo "")
        [[ -n "$s" ]] && echo "$(date +%s),$s" >> "$out"
        sleep 3
    done) &
    echo $!
}

# ==============================================================
# Maven benchmarks
# ==============================================================
run_maven_benchmarks() {
    local label="$1" csv="$2"
    local port=$SUT_PORT

    # Create Maven settings for this run
    local settings="${SCRIPT_DIR}/results/${label}/maven-settings.xml"
    cat > "$settings" <<MVNEOF
<settings>
  <servers>
    <server><id>pantera</id><username>${AUTH_USER}</username><password>${AUTH_PASS}</password></server>
  </servers>
  <profiles>
    <profile>
      <id>bench</id>
      <repositories>
        <repository><id>pantera</id><url>http://localhost:${port}/maven</url><releases><enabled>true</enabled></releases><snapshots><enabled>false</enabled></snapshots></repository>
      </repositories>
    </profile>
    <profile>
      <id>bench-group</id>
      <repositories>
        <repository><id>pantera</id><url>http://localhost:${port}/maven_group</url><releases><enabled>true</enabled></releases><snapshots><enabled>false</enabled></snapshots></repository>
      </repositories>
    </profile>
  </profiles>
</settings>
MVNEOF

    TIMING_BASE=$(mktemp -d)

    # --- Upload ---
    log "Maven: Local Upload"
    for size_label in 1KB 1MB 10MB; do
        local jar="${FIXTURES_DIR}/maven-artifact-${size_label}.jar"
        [[ -f "$jar" ]] || continue
        local bytes; bytes=$(wc -c < "$jar" | tr -d ' ')
        local ops=$MAVEN_ITERATIONS
        [[ "$size_label" == "10MB" ]] && ops=$(( ops > 5 ? 5 : ops ))

        info "Upload ${size_label} (${ops} ops)..."
        local tdir="${TIMING_BASE}/upload-${size_label}"
        mkdir -p "$tdir"
        local wall_start; wall_start=$(now_ms)

        for i in $(seq 1 "$ops"); do
            local s; s=$(now_ms)
            mvn deploy:deploy-file -B -q \
                -Daether.connector.http.expectContinue=false \
                -s "$settings" \
                -DgroupId="com.bench.upload" -DartifactId="artifact-${size_label}" -Dversion="1.0.${i}" \
                -Dpackaging=jar -Dfile="$jar" -DrepositoryId=pantera \
                -Durl="http://localhost:${port}/maven" -DgeneratePom=true >/dev/null 2>&1
            local rc=$?; local e; e=$(now_ms)
            echo "$((e - s)) $rc" > "$tdir/op-${i}.txt"
        done

        local wall_end; wall_end=$(now_ms)
        csv_row "$csv" "maven-local-upload" "$label" "$size_label" "1" "$tdir" "$((wall_end - wall_start))" "$bytes"
    done

    # --- Seed for download tests ---
    info "Seeding download artifacts..."
    for size_label in 1MB 10MB; do
        local jar="${FIXTURES_DIR}/maven-artifact-${size_label}.jar"
        [[ -f "$jar" ]] || continue
        mvn deploy:deploy-file -B -q \
            -Daether.connector.http.expectContinue=false \
            -s "$settings" \
            -DgroupId="com.bench.download" -DartifactId="dl-artifact-${size_label}" -Dversion="1.0.0" \
            -Dpackaging=jar -Dfile="$jar" -DrepositoryId=pantera \
            -Durl="http://localhost:${port}/maven" -DgeneratePom=true >/dev/null 2>&1 || true
    done
    sleep 2

    # --- Group Download (local member) ---
    log "Maven: Group Download (local artifact through maven_group)"
    export TIMING_BASE
    export -f now_ms

    for size_label in 1MB 10MB; do
        local jar="${FIXTURES_DIR}/maven-artifact-${size_label}.jar"
        [[ -f "$jar" ]] || continue
        local bytes; bytes=$(wc -c < "$jar" | tr -d ' ')
        local artifact="com.bench.download:dl-artifact-${size_label}:1.0.0"

        for conc in $CONCURRENCY_LEVELS; do
            local ops=$conc
            [[ $ops -lt $MAVEN_ITERATIONS ]] && ops=$MAVEN_ITERATIONS

            info "Group download ${size_label} c=${conc} (${ops} ops)..."
            local tdir="${TIMING_BASE}/group-dl-${size_label}-c${conc}"
            mkdir -p "$tdir"
            local wall_start; wall_start=$(now_ms)

            local op_id=0
            local rounds=$(( (ops + conc - 1) / conc ))
            for round in $(seq 1 "$rounds"); do
                local batch=$conc remaining=$((ops - op_id))
                [[ $remaining -lt $batch ]] && batch=$remaining
                [[ $batch -le 0 ]] && break
                for w in $(seq 1 "$batch"); do
                    op_id=$((op_id + 1))
                    local oid=$op_id
                    (
                        local lr="${TIMING_BASE}/m2-${oid}-$$"
                        mkdir -p "$lr"
                        local od="${TIMING_BASE}/out-${oid}-$$"
                        mkdir -p "$od"
                        local s; s=$(now_ms)
                        mvn dependency:copy -B -q \
                            -Daether.connector.http.expectContinue=false \
                            -s "$settings" -P bench-group \
                            -Dmaven.repo.local="$lr" \
                            -Dartifact="$artifact" \
                            -DoutputDirectory="$od" >/dev/null 2>&1
                        local rc=$?; local e; e=$(now_ms)
                        echo "$((e - s)) $rc" > "$tdir/op-${oid}.txt"
                        rm -rf "$lr" "$od"
                    ) &
                done
                wait
            done

            local wall_end; wall_end=$(now_ms)
            csv_row "$csv" "maven-group-download" "$label" "$size_label" "$conc" "$tdir" "$((wall_end - wall_start))" "$bytes"
        done
    done

    # --- Mixed read+write ---
    log "Maven: Mixed Read+Write"
    local mix_jar="${FIXTURES_DIR}/maven-artifact-1MB.jar"
    if [[ -f "$mix_jar" ]]; then
        local mix_artifact="com.bench.download:dl-artifact-1MB:1.0.0"
        for conc in $CONCURRENCY_LEVELS; do
            local readers=$conc
            local writers=$(( conc > 10 ? conc / 5 : (conc > 1 ? conc / 2 : 1) ))
            info "Mixed c=${conc} (${readers}R+${writers}W)..."

            local tdir_r="${TIMING_BASE}/mixed-read-c${conc}"
            local tdir_w="${TIMING_BASE}/mixed-write-c${conc}"
            mkdir -p "$tdir_r" "$tdir_w"
            local wall_start; wall_start=$(now_ms)

            for r in $(seq 1 "$readers"); do
                (
                    local lr="${TIMING_BASE}/m2-mixed-r-${r}-$$"; mkdir -p "$lr"
                    local od="${TIMING_BASE}/out-mixed-r-${r}-$$"; mkdir -p "$od"
                    local s; s=$(now_ms)
                    mvn dependency:copy -B -q \
                        -Daether.connector.http.expectContinue=false \
                        -s "$settings" -P bench-group \
                        -Dmaven.repo.local="$lr" \
                        -Dartifact="$mix_artifact" \
                        -DoutputDirectory="$od" >/dev/null 2>&1
                    local rc=$?; local e; e=$(now_ms)
                    echo "$((e - s)) $rc" > "$tdir_r/op-${r}.txt"
                    rm -rf "$lr" "$od"
                ) &
            done
            for w in $(seq 1 "$writers"); do
                (
                    local s; s=$(now_ms)
                    mvn deploy:deploy-file -B -q \
                        -Daether.connector.http.expectContinue=false \
                        -s "$settings" \
                        -DgroupId="com.bench.mixed" -DartifactId="mixed-artifact" -Dversion="1.0.$$-${RANDOM}" \
                        -Dpackaging=jar -Dfile="$mix_jar" -DrepositoryId=pantera \
                        -Durl="http://localhost:${port}/maven" -DgeneratePom=true >/dev/null 2>&1
                    local rc=$?; local e; e=$(now_ms)
                    echo "$((e - s)) $rc" > "$tdir_w/op-${w}.txt"
                ) &
            done
            wait

            local wall_end; wall_end=$(now_ms)
            csv_row "$csv" "maven-mixed-read" "$label" "1MB" "$conc" "$tdir_r" "$((wall_end - wall_start))" "0"
            csv_row "$csv" "maven-mixed-write" "$label" "1MB" "$conc" "$tdir_w" "$((wall_end - wall_start))" "0"
        done
    fi

    rm -rf "$TIMING_BASE"
}

# ==============================================================
# NPM benchmarks
# ==============================================================
run_npm_benchmarks() {
    local label="$1" csv="$2"
    local port=$SUT_PORT

    TIMING_BASE=$(mktemp -d)
    export TIMING_BASE AUTH_HEADER

    make_npmrc() {
        local p="$1" rp="$2" out="$3"
        cat > "$out" <<NEOF
registry=http://localhost:${p}/${rp}/
//localhost:${p}/${rp}/:_auth=${AUTH_HEADER}
//localhost:${p}/${rp}/:always-auth=true
NEOF
    }

    # --- Publish ---
    log "NPM: Local Publish"
    local pkg_template="${FIXTURES_DIR}/npm-package"
    if [[ -d "$pkg_template" ]]; then
        local tdir="${TIMING_BASE}/npm-publish"
        mkdir -p "$tdir"
        local epoch; epoch=$(date +%s)
        local wall_start; wall_start=$(now_ms)

        for i in $(seq 1 "$NPM_ITERATIONS"); do
            local pkg_dir="${TIMING_BASE}/npm-pub-${i}"
            cp -r "$pkg_template" "$pkg_dir"
            cat > "$pkg_dir/package.json" <<PJSON
{"name":"@bench/perf-test","version":"${epoch}.0.${i}","description":"Benchmark","main":"index.js","license":"MIT"}
PJSON
            local npmrc="${TIMING_BASE}/.npmrc-${i}"
            make_npmrc "$port" "npm" "$npmrc"

            local s; s=$(now_ms)
            npm publish "$pkg_dir" \
                --registry="http://localhost:${port}/npm/" \
                --userconfig="$npmrc" \
                --no-git-checks >/dev/null 2>&1
            local rc=$?; local e; e=$(now_ms)
            echo "$((e - s)) $rc" > "$tdir/op-${i}.txt"
            rm -rf "$pkg_dir" "$npmrc"
        done

        local wall_end; wall_end=$(now_ms)
        csv_row "$csv" "npm-local-publish" "$label" "package" "1" "$tdir" "$((wall_end - wall_start))" "0"
    fi

    # --- Group Install (local member) ---
    log "NPM: Group Install (local package through npm_group)"
    local pkg_name="@bench/perf-test"

    for conc in $CONCURRENCY_LEVELS; do
        local ops=$conc
        [[ $ops -lt $NPM_ITERATIONS ]] && ops=$NPM_ITERATIONS

        info "Group install c=${conc} (${ops} ops)..."
        local tdir="${TIMING_BASE}/npm-install-c${conc}"
        mkdir -p "$tdir"
        local wall_start; wall_start=$(now_ms)

        local op_id=0
        local rounds=$(( (ops + conc - 1) / conc ))
        for round in $(seq 1 "$rounds"); do
            local batch=$conc remaining=$((ops - op_id))
            [[ $remaining -lt $batch ]] && batch=$remaining
            [[ $batch -le 0 ]] && break
            for w in $(seq 1 "$batch"); do
                op_id=$((op_id + 1))
                local oid=$op_id
                (
                    local wd="${TIMING_BASE}/npm-i-${oid}-$$"
                    mkdir -p "$wd"
                    local rc="${wd}/.npmrc"
                    make_npmrc "$port" "npm_group" "$rc"
                    echo '{"name":"bench-c","version":"1.0.0","private":true}' > "$wd/package.json"
                    local s; s=$(now_ms)
                    cd "$wd" && npm install "$pkg_name" \
                        --registry="http://localhost:${port}/npm_group/" \
                        --userconfig="$rc" \
                        --no-audit --no-fund --no-update-notifier \
                        --cache="${wd}/.npm-cache" >/dev/null 2>&1
                    local xc=$?; local e; e=$(now_ms)
                    echo "$((e - s)) $xc" > "$tdir/op-${oid}.txt"
                    cd - >/dev/null; rm -rf "$wd"
                ) &
            done
            wait
        done

        local wall_end; wall_end=$(now_ms)
        csv_row "$csv" "npm-group-install" "$label" "package" "$conc" "$tdir" "$((wall_end - wall_start))" "0"
    done

    rm -rf "$TIMING_BASE"
}

# ==============================================================
# HTTP sustained load (hey)
# ==============================================================
run_http_load() {
    local label="$1" csv="$2"
    local port=$SUT_PORT

    log "HTTP Sustained Load Tests (hey)"

    # Seed artifacts for HTTP tests
    local jar1k="${FIXTURES_DIR}/maven-artifact-1KB.jar"
    curl -sf -X PUT "http://localhost:${port}/maven/com/bench/http/1.0/http-1.0.jar" \
        -H "Authorization: Basic ${AUTH_HEADER}" --data-binary "@${jar1k}" >/dev/null 2>&1 || true

    local jar1m="${FIXTURES_DIR}/maven-artifact-1MB.jar"
    curl -sf -X PUT "http://localhost:${port}/maven/com/bench/http-large/1.0/http-large-1.0.jar" \
        -H "Authorization: Basic ${AUTH_HEADER}" --data-binary "@${jar1m}" >/dev/null 2>&1 || true
    sleep 1

    # Warmup
    info "Warmup: 20c x 15s"
    hey -z 15s -c 20 \
        -H "Authorization: Basic ${AUTH_HEADER}" \
        "http://localhost:${port}/maven/com/bench/http/1.0/http-1.0.jar" > "${RESULTS_DIR}/${label}/warmup.txt" 2>&1
    sleep 5

    # Sustained load at each rate
    local endpoint="/maven/com/bench/http/1.0/http-1.0.jar"
    for rps in $HTTP_RATE_LEVELS; do
        local conc=$((rps / 10))

        for run in 1 2; do
            info "HTTP ${rps} req/s — run ${run}"
            hey -z "$HTTP_DURATION" -c "$conc" -q 10 \
                -H "Authorization: Basic ${AUTH_HEADER}" \
                "http://localhost:${port}${endpoint}" \
                > "${RESULTS_DIR}/${label}/hey-${rps}rps-run${run}-summary.txt" 2>&1

            hey -z "$HTTP_DURATION" -c "$conc" -q 10 \
                -H "Authorization: Basic ${AUTH_HEADER}" \
                -o csv \
                "http://localhost:${port}${endpoint}" \
                > "${RESULTS_DIR}/${label}/hey-${rps}rps-run${run}.csv" 2>&1

            sleep 5
        done
    done

    # Large artifact HTTP test
    info "HTTP 500 req/s — 1MB artifact"
    hey -z 30s -c 50 -q 10 \
        -H "Authorization: Basic ${AUTH_HEADER}" \
        "http://localhost:${port}/maven/com/bench/http-large/1.0/http-large-1.0.jar" \
        > "${RESULTS_DIR}/${label}/hey-500rps-1mb-summary.txt" 2>&1

    # Parse hey summaries into the CSV format
    for summary in "${RESULTS_DIR}/${label}"/hey-*-summary.txt; do
        [[ -f "$summary" ]] || continue
        local base; base=$(basename "$summary" -summary.txt)
        local rps_label; rps_label=$(echo "$base" | sed 's/hey-\([0-9]*\)rps.*/\1/')
        local run_label; run_label=$(echo "$base" | sed 's/.*-\(run[0-9]*\)/\1/' | grep -o 'run[0-9]*' || echo "single")

        # Parse hey output
        local achieved_rps avg_ms p50_ms p90_ms p95_ms p99_ms max_ms total errors
        achieved_rps=$(grep 'Requests/sec:' "$summary" | awk '{print $2}' || echo "0")
        avg_ms=$(grep 'Average:' "$summary" | awk '{printf "%.2f", $2 * 1000}' || echo "0")
        p50_ms=$(grep '50%' "$summary" | awk '{printf "%.2f", $3 * 1000}' 2>/dev/null || echo "0")
        p90_ms=$(grep '90%' "$summary" | awk '{printf "%.2f", $3 * 1000}' 2>/dev/null || echo "0")
        p95_ms=$(grep '95%' "$summary" | awk '{printf "%.2f", $3 * 1000}' 2>/dev/null || echo "0")
        p99_ms=$(grep '99%' "$summary" | awk '{printf "%.2f", $3 * 1000}' 2>/dev/null || echo "0")
        max_ms=$(grep 'Slowest:' "$summary" | awk '{printf "%.2f", $2 * 1000}' || echo "0")

        # Status codes
        total=0; errors=0
        while IFS= read -r line; do
            local code count
            code=$(echo "$line" | awk '{print $1}' | tr -d '[]')
            count=$(echo "$line" | awk '{print $2}')
            total=$((total + count))
            [[ "$code" != 2* ]] && errors=$((errors + count))
        done < <(grep -E '^\s*\[' "$summary" 2>/dev/null || true)

        local err_pct=0
        [[ $total -gt 0 ]] && err_pct=$(echo "scale=2; $errors * 100 / $total" | bc 2>/dev/null || echo "0")

        # Append to CSV
        echo "http-load-${rps_label}rps,${label},1KB-${run_label},1,${total},${achieved_rps},${avg_ms},${p50_ms},${p95_ms},${p99_ms},${max_ms},${err_pct},0" >> "$csv"
    done
}

# ==============================================================
# Full benchmark for one image
# ==============================================================
benchmark_image() {
    local label="$1" image="$2" jvm="$3" cfg_dir="$4" cfg_file="$5" base_path="$6"

    local out_dir="${RESULTS_DIR}/${label}"
    rm -rf "$out_dir"
    mkdir -p "$out_dir"

    # Record metadata
    cat > "${out_dir}/image-info.txt" <<IEOF
image=${image}
label=${label}
cpus=${CPUS}
memory=${MEMORY}
timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
IEOF

    # CSV for real-client results
    local csv="${out_dir}/results.csv"
    csv_header "$csv"

    start_sut "$label" "$image" "$jvm" "$cfg_dir" "$cfg_file" "$base_path"

    # Start resource monitoring
    local stats_pid
    stats_pid=$(start_stats "${out_dir}/docker-stats.csv")

    # Run benchmarks
    run_maven_benchmarks "$label" "$csv"
    run_npm_benchmarks "$label" "$csv"
    run_http_load "$label" "$csv"

    # Collect artifacts
    kill "$stats_pid" 2>/dev/null || true
    docker cp "${SUT_NAME}:${base_path}/logs/gc.log" "${out_dir}/gc.log" 2>/dev/null || true
    docker inspect "$SUT_NAME" > "${out_dir}/container-inspect.json" 2>/dev/null || true
    docker logs "$SUT_NAME" > "${out_dir}/container.log" 2>&1 || true

    stop_sut
    info "Benchmark for ${label} complete."
}

# ==============================================================
# Report
# ==============================================================
generate_report() {
    log "Generating comparison report"

    local old_csv="${RESULTS_DIR}/old/results.csv"
    local new_csv="${RESULTS_DIR}/new/results.csv"

    if [[ ! -f "$old_csv" || ! -f "$new_csv" ]]; then
        warn "Missing results. Need both old and new."
        return 1
    fi

    # Merge into single comparison CSV
    local merged="${RESULTS_DIR}/merged.csv"
    head -1 "$old_csv" > "$merged"
    tail -n +2 "$old_csv" >> "$merged"
    tail -n +2 "$new_csv" >> "$merged"

    # Use the existing report generator (adapted)
    local report="${RESULTS_DIR}/BENCHMARK-REPORT.md"

    python3 - "$merged" "$report" "$RESULTS_DIR" <<'PYEOF'
import sys, csv, os
from collections import defaultdict
from datetime import datetime

merged_csv = sys.argv[1]
report_path = sys.argv[2]
results_dir = sys.argv[3]

rows = []
with open(merged_csv) as f:
    reader = csv.DictReader(f)
    for row in reader:
        rows.append(row)

# Group by scenario+size+concurrency
groups = defaultdict(dict)
for r in rows:
    key = (r['scenario'], r['size'], r['concurrency'])
    groups[key][r['version']] = r

# Load stats
def load_stats(label):
    path = os.path.join(results_dir, label, "docker-stats.csv")
    cpus, mems = [], []
    try:
        with open(path) as f:
            reader = csv.DictReader(f)
            for row in reader:
                try:
                    cpus.append(float(row.get('cpu_pct', '0').replace('%', '')))
                    mems.append(float(row.get('mem_pct', '0').replace('%', '')))
                except ValueError:
                    pass
    except FileNotFoundError:
        pass
    if not cpus:
        return {'avg_cpu': 0, 'max_cpu': 0, 'avg_mem': 0, 'max_mem': 0}
    return {
        'avg_cpu': round(sum(cpus)/len(cpus), 1),
        'max_cpu': round(max(cpus), 1),
        'avg_mem': round(sum(mems)/len(mems), 1),
        'max_mem': round(max(mems), 1),
    }

old_stats = load_stats("old")
new_stats = load_stats("new")

def delta(old_v, new_v):
    try:
        o, n = float(old_v), float(new_v)
        if o == 0: return "N/A"
        d = (n - o) / o * 100
        return f"{d:+.1f}%"
    except (ValueError, TypeError):
        return "N/A"

lines = []
def w(s=""): lines.append(s)

w("# Performance Benchmark Report")
w(f"**Generated:** {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S UTC')}")
w()
w("## Overview")
w()
w("Comprehensive comparison of `artipie:1.20.12` (old) vs `pantera:2.0.0` (new).")
w("Each image was benchmarked in **complete isolation** with identical resources.")
w()

w("## Test Environment")
w()
w("| Parameter | Value |")
w("|-----------|-------|")
w(f"| CPU limit | 8 cores |")
w(f"| Memory limit | 16 GB |")
w("| JVM heap | 10g fixed (-Xms10g -Xmx10g) |")
w("| GC | G1GC (MaxGCPauseMillis=300, G1HeapRegionSize=16m) |")
w("| IO threads | 14 |")
w("| Load tools | mvn, npm, hey |")
w()

w("## Methodology")
w()
w("1. Start fresh infrastructure (PostgreSQL + Valkey)")
w("2. Start ONE image with `--cpus=8 --memory=16g`")
w("3. Run Maven benchmarks: upload (1KB/1MB/10MB), group download, mixed")
w("4. Run NPM benchmarks: publish, group install at multiple concurrencies")
w("5. Run HTTP sustained load: 800/900/1000 req/s with `hey`")
w("6. Collect GC logs, container stats, and artifacts")
w("7. Tear down completely")
w("8. Repeat for second image")
w()

# === Scenario tables ===
scenarios_order = [
    ("maven-local-upload", "Maven Local Upload"),
    ("maven-group-download", "Maven Group Download (maven_group -> local member)"),
    ("maven-mixed-read", "Maven Mixed - Read (during concurrent upload+download)"),
    ("maven-mixed-write", "Maven Mixed - Write (during concurrent upload+download)"),
    ("npm-local-publish", "NPM Local Publish"),
    ("npm-group-install", "NPM Group Install (npm_group -> local member)"),
]

for scenario_prefix, scenario_title in scenarios_order:
    scenario_rows = [(k, v) for k, v in groups.items() if k[0] == scenario_prefix]
    if not scenario_rows:
        continue

    w(f"## {scenario_title}")
    w()
    w("| Size | Conc | Ops | Metric | Old | New | Delta |")
    w("|------|------|-----|--------|-----|-----|-------|")

    for key in sorted(scenario_rows, key=lambda x: (x[0][1], int(x[0][2]))):
        k, versions = key
        _, size, conc = k
        old_r = versions.get('old', {})
        new_r = versions.get('new', {})

        ops = old_r.get('ops', new_r.get('ops', ''))

        for metric, field, lower_better in [
            ("Ops/sec", "rps", False),
            ("Mean (ms)", "latency_mean_ms", True),
            ("p50 (ms)", "latency_p50_ms", True),
            ("p95 (ms)", "latency_p95_ms", True),
            ("p99 (ms)", "latency_p99_ms", True),
            ("Error %", "error_pct", True),
        ]:
            ov = old_r.get(field, 'N/A')
            nv = new_r.get(field, 'N/A')
            d = delta(ov, nv)
            w(f"| {size} | {conc} | {ops} | **{metric}** | {ov} | {nv} | {d} |")

    w()

# === HTTP Load Results ===
http_rows = [(k, v) for k, v in groups.items() if k[0].startswith('http-load')]
if http_rows:
    w("## HTTP Sustained Load (hey)")
    w()
    w("| Target RPS | Run | Achieved RPS | Mean (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Error % |")
    w("|------------|-----|-------------|-----------|----------|----------|----------|----------|---------|")

    for key in sorted(http_rows, key=lambda x: x[0]):
        k, versions = key
        scenario, size, conc = k
        rps_target = scenario.replace('http-load-', '').replace('rps', '')
        for lbl in ['old', 'new']:
            r = versions.get(lbl, {})
            if r:
                w(f"| {rps_target} | {lbl} ({size}) | {r.get('rps','?')} | {r.get('latency_mean_ms','?')} | {r.get('latency_p50_ms','?')} | {r.get('latency_p95_ms','?')} | {r.get('latency_p99_ms','?')} | {r.get('latency_max_ms','?')} | {r.get('error_pct','?')} |")
    w()

# === Resource Usage ===
w("## Resource Usage")
w()
w("| Metric | Old | New | Delta |")
w("|--------|-----|-----|-------|")
for metric, key in [("Avg CPU %", "avg_cpu"), ("Max CPU %", "max_cpu"), ("Avg Mem %", "avg_mem"), ("Max Mem %", "max_mem")]:
    ov = old_stats.get(key, 0)
    nv = new_stats.get(key, 0)
    w(f"| {metric} | {ov} | {nv} | {delta(ov, nv)} |")
w()

# === Executive Summary ===
w("## Executive Summary")
w()

# Aggregate comparison
improvements = []
regressions = []
for k, versions in groups.items():
    scenario, size, conc = k
    old_r = versions.get('old', {})
    new_r = versions.get('new', {})
    if not old_r or not new_r:
        continue
    try:
        old_rps = float(old_r.get('rps', 0))
        new_rps = float(new_r.get('rps', 0))
        if old_rps > 0:
            change = (new_rps - old_rps) / old_rps * 100
            label = f"{scenario} {size} c={conc}"
            if change > 5:
                improvements.append(f"{label}: RPS {old_rps:.1f} -> {new_rps:.1f} ({change:+.1f}%)")
            elif change < -5:
                regressions.append(f"{label}: RPS {old_rps:.1f} -> {new_rps:.1f} ({change:+.1f}%)")
    except (ValueError, TypeError):
        pass

if improvements:
    w("### Improvements")
    for i in improvements:
        w(f"- {i}")
    w()

if regressions:
    w("### Regressions")
    for r in regressions:
        w(f"- {r}")
    w()

if not regressions:
    w("**Verdict:** `pantera:2.0.0` shows no performance regressions compared to `artipie:1.20.12`.")
    if improvements:
        w("Performance improvements were detected. Safe to promote from a performance standpoint.")
    else:
        w("Performance is comparable. Safe to promote from a performance standpoint.")
else:
    w("**Verdict:** Performance regressions detected. Review before promoting.")
w()

w("**Caveats:**")
w("- Benchmarked on Docker Desktop (macOS). Production Linux may differ.")
w("- Maven/NPM client overhead (~2-3s JVM startup) is constant across both versions.")
w("- Proxy scenarios require network access to Maven Central / npmjs.org.")
w("- Docker scenarios skipped (require insecure registry config).")
w()

with open(report_path, 'w') as f:
    f.write('\n'.join(lines))
print(f"Report written to {report_path}")
PYEOF

    info "Report: ${report}"
}

# ==============================================================
# Main
# ==============================================================
main() {
    local mode="${1:-all}"

    case "$mode" in
        teardown)
            stop_sut
            stop_infra
            exit 0
            ;;
        old)
            start_infra
            benchmark_image "old" "$OLD_IMAGE" "$JVM_OLD" "${SCRIPT_DIR}/config-old" "/etc/artipie/artipie.yml" "/var/artipie"
            stop_infra
            ;;
        new)
            start_infra
            benchmark_image "new" "$NEW_IMAGE" "$JVM_NEW" "${SCRIPT_DIR}/config-new" "/etc/pantera/pantera.yml" "/var/pantera"
            stop_infra
            ;;
        report)
            generate_report
            ;;
        all)
            command -v hey >/dev/null 2>&1 || { echo "ERROR: hey not found"; exit 1; }
            command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not found"; exit 1; }
            command -v npm >/dev/null 2>&1 || { echo "ERROR: npm not found"; exit 1; }

            start_infra
            benchmark_image "old" "$OLD_IMAGE" "$JVM_OLD" "${SCRIPT_DIR}/config-old" "/etc/artipie/artipie.yml" "/var/artipie"
            stop_infra

            info "Cooling 15s between image runs..."
            sleep 15

            start_infra
            benchmark_image "new" "$NEW_IMAGE" "$JVM_NEW" "${SCRIPT_DIR}/config-new" "/etc/pantera/pantera.yml" "/var/pantera"
            stop_infra

            generate_report
            echo ""
            echo "================================================================"
            echo "  BENCHMARK COMPLETE"
            echo "  Report: ${RESULTS_DIR}/BENCHMARK-REPORT.md"
            echo "  CSV:    ${RESULTS_DIR}/old/results.csv"
            echo "          ${RESULTS_DIR}/new/results.csv"
            echo "================================================================"
            ;;
        *)
            echo "Usage: $0 [all|old|new|report|teardown]"
            exit 1
            ;;
    esac
}

main "$@"
