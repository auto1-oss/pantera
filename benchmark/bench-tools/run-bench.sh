#!/usr/bin/env bash
##
## Pantera Benchmark v3 — Real Client Traffic
## Runs inside the bench-tools sidecar container.
##
## Phases:
##   1. Correctness — deploy + install per protocol (sequential)
##   2. Seed — deploy read-target artifacts
##   3. Throughput — N writers + M readers in parallel
##
## Environment:
##   REGISTRY_HOST     Pantera host:port (default: localhost:9080)
##   BENCH_USER        Auth username (default: pantera)
##   BENCH_PASS        Auth password (default: pantera)
##   BENCH_WRITERS     Writers per protocol (default: 100)
##   BENCH_READERS     Readers per protocol (default: 100)
##   BENCH_DURATION    Seconds per protocol throughput test (default: 60)
##   BENCH_PROTOCOLS   Comma-separated list (default: maven,npm,docker,php,pypi)
##   BENCH_SKIP_CORRECTNESS  Set to 1 to skip phase 1 (default: 0)
##
set -euo pipefail

REGISTRY_HOST="${REGISTRY_HOST:-localhost:9080}"
BENCH_USER="${BENCH_USER:-pantera}"
BENCH_PASS="${BENCH_PASS:-pantera}"
BENCH_CONCURRENCY="${BENCH_CONCURRENCY:-40}"
# 80/20 read/write split
BENCH_READERS=$(( BENCH_CONCURRENCY * 80 / 100 ))
BENCH_WRITERS=$(( BENCH_CONCURRENCY * 20 / 100 ))
[[ $BENCH_WRITERS -lt 1 ]] && BENCH_WRITERS=1
BENCH_DURATION="${BENCH_DURATION:-60}"
BENCH_PROTOCOLS="${BENCH_PROTOCOLS:-maven,npm,docker,php,pypi}"
BENCH_SKIP_CORRECTNESS="${BENCH_SKIP_CORRECTNESS:-0}"

PROJECTS="/bench/projects"
RESULTS="/results"
RAW_CSV="${RESULTS}/raw.csv"
MVN_SETTINGS="${PROJECTS}/maven/settings.xml"
PYPIRC="${PROJECTS}/pypi/pypirc"

AUTH_HEADER="Authorization: Basic $(echo -n "${BENCH_USER}:${BENCH_PASS}" | base64)"
NPM_AUTH_TOKEN=$(echo -n "${BENCH_USER}:${BENCH_PASS}" | base64)

IFS=',' read -ra PROTOCOLS <<< "$BENCH_PROTOCOLS"

log()  { echo ""; echo "================================================================"; echo "  $*"; echo "================================================================"; }
info() { echo "  [INFO] $*"; }
warn() { echo "  [WARN] $*"; }
pass() { echo "  [PASS] $*"; }
fail() { echo "  [FAIL] $*"; }

# ================================================================
# Record a timing entry (each worker writes its own file — no locking needed)
# ================================================================
WORKER_CSV_DIR=""

record() {
    local worker="$1" protocol="$2" op="$3" start_ns="$4" end_ns="$5" exit_code="$6"
    echo "${worker},${protocol},${op},${start_ns},${end_ns},${exit_code}" >> "${WORKER_CSV_DIR}/${worker}.csv"
}

merge_worker_csvs() {
    echo "worker_id,protocol,operation,start_ns,end_ns,exit_code" > "$RAW_CSV"
    cat "${WORKER_CSV_DIR}"/*.csv >> "$RAW_CSV" 2>/dev/null || true
}

# Nanosecond timestamp
now_ns() { date +%s%N; }

# ================================================================
# Maven operations
# ================================================================
maven_deploy() {
    local project_dir="$1"
    mvn deploy -B -q -s "$MVN_SETTINGS" -f "${project_dir}/pom.xml" \
        -DskipTests -Dmaven.install.skip=true 2>&1
}

maven_resolve() {
    local project_dir="${PROJECTS}/maven/read"
    local local_repo
    local_repo=$(mktemp -d)
    mvn dependency:resolve -B -q -s "$MVN_SETTINGS" -f "${project_dir}/pom.xml" \
        -Dmaven.repo.local="${local_repo}" 2>&1
    rm -rf "${local_repo}"
}

# ================================================================
# npm operations
# Each publish must use a unique version (npm rejects duplicate versions).
# The caller passes a version string.
# ================================================================
npm_publish() {
    local project_dir="$1" version="${2:-1.0.0}"
    local tmpdir
    tmpdir=$(mktemp -d)
    cp "${project_dir}/package.json" "${tmpdir}/package.json"
    cp "${project_dir}/index.js" "${tmpdir}/index.js"
    # Patch version in the copy
    sed -i "s/\"version\": \"1.0.0\"/\"version\": \"${version}\"/" "${tmpdir}/package.json"
    cp "${PROJECTS}/npm/npmrc-write" "${tmpdir}/.npmrc"
    cd "${tmpdir}"
    npm publish 2>&1
    local rc=$?
    cd /bench
    rm -rf "${tmpdir}"
    return $rc
}

npm_install() {
    local tmpdir
    tmpdir=$(mktemp -d)
    cp "${PROJECTS}/npm/read/package.json" "${tmpdir}/package.json"
    cp "${PROJECTS}/npm/npmrc-read" "${tmpdir}/.npmrc"
    cd "${tmpdir}"
    npm install --prefer-online 2>&1
    local rc=$?
    cd /bench
    rm -rf "${tmpdir}"
    return $rc
}

# ================================================================
# Docker operations
# Uses skopeo — a real OCI/Docker container image client.
# Push to docker_local, pull from docker_group (local + proxy).
# ================================================================
SKOPEO_CREDS="--creds=${BENCH_USER}:${BENCH_PASS}"
SKOPEO_TLS="--tls-verify=false"

docker_push() {
    local repo="$1" tag="${2:-1.0}"
    skopeo copy ${SKOPEO_TLS} --dest-creds="${BENCH_USER}:${BENCH_PASS}" \
        "oci:${PROJECTS}/docker/oci-image:latest" \
        "docker://${REGISTRY_HOST}/docker_local/${repo}:${tag}" 2>&1
}

docker_pull() {
    local repo="bench-read" tag="1.0"
    local tmpdir
    tmpdir=$(mktemp -d)
    skopeo copy ${SKOPEO_TLS} --src-creds="${BENCH_USER}:${BENCH_PASS}" \
        "docker://${REGISTRY_HOST}/docker_group/${repo}:${tag}" \
        "dir:${tmpdir}" 2>&1
    local rc=$?
    rm -rf "${tmpdir}"
    return $rc
}

# Build a minimal OCI image for pushing (done once during seed)
docker_build_oci() {
    local oci_dir="${PROJECTS}/docker/oci-image"
    mkdir -p "${oci_dir}/blobs/sha256"

    # Create a minimal layer
    local layer_data="benchmark-payload-$(date +%s)"
    local layer_file="${oci_dir}/blobs/sha256/layer.tar.gz"
    echo "$layer_data" | gzip > "$layer_file"
    local layer_digest layer_size
    layer_digest=$(sha256sum "$layer_file" | awk '{print $1}')
    layer_size=$(stat -c%s "$layer_file" 2>/dev/null || stat -f%z "$layer_file")
    mv "$layer_file" "${oci_dir}/blobs/sha256/${layer_digest}"

    # Create config
    local config='{"architecture":"amd64","os":"linux","rootfs":{"type":"layers","diff_ids":["sha256:'${layer_digest}'"]}}'
    echo -n "$config" > "${oci_dir}/blobs/sha256/config.json"
    local config_digest config_size
    config_digest=$(sha256sum "${oci_dir}/blobs/sha256/config.json" | awk '{print $1}')
    config_size=$(stat -c%s "${oci_dir}/blobs/sha256/config.json" 2>/dev/null || stat -f%z "${oci_dir}/blobs/sha256/config.json")
    mv "${oci_dir}/blobs/sha256/config.json" "${oci_dir}/blobs/sha256/${config_digest}"

    # Create manifest
    local manifest="{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"sha256:${config_digest}\",\"size\":${config_size}},\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"digest\":\"sha256:${layer_digest}\",\"size\":${layer_size}}]}"
    echo -n "$manifest" > "${oci_dir}/blobs/sha256/manifest.json"
    local manifest_digest manifest_size
    manifest_digest=$(sha256sum "${oci_dir}/blobs/sha256/manifest.json" | awk '{print $1}')
    manifest_size=$(stat -c%s "${oci_dir}/blobs/sha256/manifest.json" 2>/dev/null || stat -f%z "${oci_dir}/blobs/sha256/manifest.json")
    mv "${oci_dir}/blobs/sha256/manifest.json" "${oci_dir}/blobs/sha256/${manifest_digest}"

    # Create OCI layout
    echo '{"imageLayoutVersion":"1.0.0"}' > "${oci_dir}/oci-layout"
    cat > "${oci_dir}/index.json" << IEOF
{"schemaVersion":2,"manifests":[{"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"sha256:${manifest_digest}","size":${manifest_size},"annotations":{"org.opencontainers.image.ref.name":"latest"}}]}
IEOF
    info "OCI image built for Docker push benchmarks"
}

# ================================================================
# PHP / Composer operations
# Write: curl -XPUT package archive to php repo
# Read: composer install from php_group
# ================================================================
php_upload() {
    local project_dir="$1"
    local name
    name=$(php -r "echo json_decode(file_get_contents('${project_dir}/composer.json'))->name;" | tr '/' '-')
    local archive="/tmp/php-${RANDOM}.zip"
    cd "${project_dir}"
    zip -q "${archive}" composer.json
    cd /bench
    curl -sf -X PUT \
        -u "${BENCH_USER}:${BENCH_PASS}" \
        -H "Content-Type: application/zip" \
        --data-binary "@${archive}" \
        "http://${REGISTRY_HOST}/php/${name}-1.0.0.zip" 2>&1
    local rc=$?
    rm -f "${archive}"
    return $rc
}

php_install() {
    local tmpdir
    tmpdir=$(mktemp -d)
    cp "${PROJECTS}/php/read/composer.json" "${tmpdir}/composer.json"
    cd "${tmpdir}"
    COMPOSER_HOME="${tmpdir}/.composer" \
    COMPOSER_ALLOW_SUPERUSER=1 \
    composer install --no-interaction --no-progress --prefer-dist 2>&1
    local rc=$?
    cd /bench
    rm -rf "${tmpdir}"
    return $rc
}

# ================================================================
# PyPI operations
# Each upload must use a unique version (PyPI rejects duplicates).
# ================================================================
pypi_upload() {
    local project_dir="$1" version="${2:-1.0.0}"
    local tmpdir
    tmpdir=$(mktemp -d)
    cp "${project_dir}/setup.cfg" "${tmpdir}/setup.cfg"
    cp "${project_dir}/setup.py" "${tmpdir}/setup.py"
    # Copy the Python module file
    cp "${project_dir}"/*.py "${tmpdir}/" 2>/dev/null || true
    # Patch version
    sed -i "s/^version = 1.0.0/version = ${version}/" "${tmpdir}/setup.cfg"
    cd "${tmpdir}"
    python3 setup.py sdist --formats=gztar -d "${tmpdir}/dist" 2>&1
    twine upload --config-file "${PYPIRC}" -r pantera "${tmpdir}/dist"/*.tar.gz 2>&1
    local rc=$?
    cd /bench
    rm -rf "${tmpdir}"
    return $rc
}

pypi_install() {
    local tmpdir
    tmpdir=$(mktemp -d)
    pip3 install --target "${tmpdir}" \
        --index-url "http://${BENCH_USER}:${BENCH_PASS}@${REGISTRY_HOST}/pypi/simple/" \
        --trusted-host "${REGISTRY_HOST%%:*}" \
        --no-deps \
        bench-read 2>&1
    local rc=$?
    rm -rf "${tmpdir}"
    return $rc
}

# ================================================================
# Phase 1: Correctness
# ================================================================
PASSED_PROTOCOLS=()

phase_correctness() {
    log "PHASE 1: Correctness Tests"
    local failures=0

    for proto in "${PROTOCOLS[@]}"; do
        info "Testing ${proto}..."
        local proto_ok=true
        case "$proto" in
            maven)
                if maven_deploy "${PROJECTS}/maven/seed" > /dev/null 2>&1; then
                    pass "maven deploy"
                else
                    fail "maven deploy"; proto_ok=false
                fi
                if maven_resolve > /dev/null 2>&1; then
                    pass "maven resolve (dependency:resolve)"
                else
                    fail "maven resolve"; proto_ok=false
                fi
                ;;
            npm)
                if npm_publish "${PROJECTS}/npm/seed" > /dev/null 2>&1; then
                    pass "npm publish"
                else
                    fail "npm publish"; proto_ok=false
                fi
                if npm_install > /dev/null 2>&1; then
                    pass "npm install"
                else
                    fail "npm install"; proto_ok=false
                fi
                ;;
            docker)
                docker_build_oci
                if docker_push "bench-read" "1.0" > /dev/null 2>&1; then
                    pass "docker push (skopeo → docker_local)"
                else
                    fail "docker push"; proto_ok=false
                fi
                if docker_pull > /dev/null 2>&1; then
                    pass "docker pull (skopeo ← docker_group)"
                else
                    fail "docker pull"; proto_ok=false
                fi
                ;;
            php)
                if php_upload "${PROJECTS}/php/seed" > /dev/null 2>&1; then
                    pass "php upload (curl PUT)"
                else
                    fail "php upload"; proto_ok=false
                fi
                if php_install > /dev/null 2>&1; then
                    pass "composer install"
                else
                    fail "composer install"; proto_ok=false
                fi
                ;;
            pypi)
                if pypi_upload "${PROJECTS}/pypi/seed" > /dev/null 2>&1; then
                    pass "twine upload"
                else
                    fail "twine upload"; proto_ok=false
                fi
                if pypi_install > /dev/null 2>&1; then
                    pass "pip install"
                else
                    fail "pip install"; proto_ok=false
                fi
                ;;
        esac
        if $proto_ok; then
            PASSED_PROTOCOLS+=("$proto")
        else
            failures=$((failures + 1))
            warn "${proto}: SKIPPED for throughput"
        fi
    done

    if (( failures > 0 )); then
        warn "Correctness: ${failures} protocol(s) failed — running throughput for: ${PASSED_PROTOCOLS[*]}"
    else
        info "Correctness: ALL PASSED"
    fi
}

# ================================================================
# Phase 2: Seed read targets
# ================================================================
phase_seed() {
    log "PHASE 2: Seed Read Targets"

    for proto in "${PASSED_PROTOCOLS[@]}"; do
        info "Seeding ${proto}..."
        case "$proto" in
            maven)
                # Already deployed in correctness phase; re-deploy to be safe
                maven_deploy "${PROJECTS}/maven/seed" > /dev/null 2>&1 || true
                pass "maven seed: bench-read:1.0"
                ;;
            npm)
                # Already published; npm publish of same version will fail (expected)
                npm_publish "${PROJECTS}/npm/seed" > /dev/null 2>&1 || true
                pass "npm seed: @bench/read@1.0.0"
                ;;
            docker)
                docker_push "bench-read" "1.0" > /dev/null 2>&1 || true
                pass "docker seed: bench-read:1.0"
                ;;
            php)
                php_upload "${PROJECTS}/php/seed" > /dev/null 2>&1 || true
                pass "php seed: bench/read@1.0.0"
                ;;
            pypi)
                pypi_upload "${PROJECTS}/pypi/seed" > /dev/null 2>&1 || true
                pass "pypi seed: bench-read==1.0.0"
                ;;
        esac
    done
    info "Seed complete"
}

# ================================================================
# Phase 3: Throughput
# ================================================================

# Writer worker loop
writer_worker() {
    local id="$1" protocol="$2" duration="$3"
    local end=$((SECONDS + duration))
    local project_dir="${PROJECTS}/${protocol}/w${id}"
    local iter=0

    while (( SECONDS < end )); do
        iter=$((iter + 1))
        local version="1.0.${iter}"
        local t0
        t0=$(now_ns)
        local rc=0

        case "$protocol" in
            maven)
                # Maven allows overwriting same version — no version bump needed
                maven_deploy "${project_dir}" > /dev/null 2>&1 || rc=$?
                ;;
            npm)
                # npm rejects duplicate versions — bump each iteration
                npm_publish "${project_dir}" "${version}" > /dev/null 2>&1 || rc=$?
                ;;
            docker)
                # Each worker pushes unique repo, bump tag per iteration
                docker_push "bench-w${id}" "${version}" > /dev/null 2>&1 || rc=$?
                ;;
            php)
                # PHP PUT overwrites — no version bump needed
                php_upload "${project_dir}" > /dev/null 2>&1 || rc=$?
                ;;
            pypi)
                # PyPI rejects duplicate versions — bump each iteration
                pypi_upload "${project_dir}" "${version}" > /dev/null 2>&1 || rc=$?
                ;;
        esac

        local t1
        t1=$(now_ns)
        record "w${id}" "$protocol" "write" "$t0" "$t1" "$rc"
    done
}

# Reader worker loop
reader_worker() {
    local id="$1" protocol="$2" duration="$3"
    local end=$((SECONDS + duration))

    while (( SECONDS < end )); do
        local t0
        t0=$(now_ns)
        local rc=0

        case "$protocol" in
            maven)  maven_resolve > /dev/null 2>&1 || rc=$? ;;
            npm)    npm_install > /dev/null 2>&1 || rc=$? ;;
            docker) docker_pull > /dev/null 2>&1 || rc=$? ;;
            php)    php_install > /dev/null 2>&1 || rc=$? ;;
            pypi)   pypi_install > /dev/null 2>&1 || rc=$? ;;
        esac

        local t1
        t1=$(now_ns)
        record "r${id}" "$protocol" "read" "$t0" "$t1" "$rc"
    done
}

# ================================================================
# Phase 3: Registry Throughput (hey — direct HTTP)
# Measures registry-level performance isolated from client overhead.
# ================================================================
HEY_STATS_CSV="${RESULTS}/registry-throughput.csv"

run_hey_test() {
    local name="$1" url="$2" concurrency="$3" duration="${4:-10}"
    local method="${5:-GET}" body="${6:-}" content_type="${7:-}"

    local hey_args=(-z "${duration}s" -c "$concurrency" -t 5 -o csv)
    hey_args+=(-H "$AUTH_HEADER")
    [[ "$method" != "GET" ]] && hey_args+=(-m "$method")
    [[ -n "$content_type" ]] && hey_args+=(-H "Content-Type: ${content_type}")
    [[ -n "$body" ]] && hey_args+=(-d "$body")

    local csv_file="${RESULTS}/.hey_${name}.csv"
    hey "${hey_args[@]}" "$url" > "$csv_file" 2>/dev/null || true

    # Parse hey CSV: compute stats. Use sort(1) for O(n log n) percentiles instead of awk bubble sort.
    if [[ ! -s "$csv_file" ]]; then
        echo "${name},${concurrency},0,0,0,0,0,0,0,0" >> "$HEY_STATS_CSV"
        return
    fi

    # First pass: compute totals and wall clock
    local stats
    stats=$(awk -F, '
    NR == 1 { next }
    {
        n++; sum += $1 * 1000
        status = $7 + 0; if (status < 200 || status >= 400) errors++
        offset = $8; if (n == 1 || offset < first) first = offset
        end = offset + $1; if (end > last) last = end
    }
    END { printf "%d %.1f %d %.6f %.6f", n, sum, errors, first, last }' "$csv_file")
    local n sum_ms errs first_t last_t
    read -r n sum_ms errs first_t last_t <<< "$stats"

    if [[ "$n" -eq 0 ]]; then
        echo "${name},${concurrency},0,0,0,0,0,0,0,0" >> "$HEY_STATS_CSV"
        return
    fi

    # Percentiles via sorted response times (O(n log n))
    local sorted_file="/tmp/hey_sorted_$$.txt"
    tail -n +2 "$csv_file" | awk -F, '{printf "%.4f\n", $1 * 1000}' | sort -n > "$sorted_file"
    local p50_line=$((n * 50 / 100 + 1))
    local p95_line=$((n * 95 / 100 + 1))
    local p99_line=$((n * 99 / 100 + 1))
    local p50 p95 p99 mx avg wall rps err_pct
    p50=$(sed -n "${p50_line}p" "$sorted_file")
    p95=$(sed -n "${p95_line}p" "$sorted_file")
    p99=$(sed -n "${p99_line}p" "$sorted_file")
    mx=$(tail -1 "$sorted_file")
    avg=$(echo "scale=1; $sum_ms / $n" | bc)
    wall=$(echo "scale=6; $last_t - $first_t" | bc)
    if (( $(echo "$wall <= 0" | bc -l) )); then wall=1; fi
    rps=$(echo "scale=1; $n / $wall" | bc)
    err_pct=$(echo "scale=2; $errs * 100 / $n" | bc)
    rm -f "$sorted_file"

    printf "%s,%s,%d,%s,%s,%s,%s,%s,%s,%s\n" \
        "$name" "$concurrency" "$n" "$rps" "$avg" "$p50" "$p95" "$p99" "$mx" "$err_pct" >> "$HEY_STATS_CSV"

    # Print summary
    local line; line=$(tail -1 "$HEY_STATS_CSV")
    local rps avg p95 err
    rps=$(echo "$line" | cut -d, -f4)
    avg=$(echo "$line" | cut -d, -f5)
    p95=$(echo "$line" | cut -d, -f7)
    err=$(echo "$line" | cut -d, -f10)
    info "  ${name} (c=${concurrency}): ${rps} req/s, avg=${avg}ms, p95=${p95}ms, err=${err}%"
}

phase_registry_throughput() {
    log "PHASE 3: Registry Throughput (hey — direct HTTP)"

    echo "test,concurrency,total_reqs,rps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,error_pct" > "$HEY_STATS_CSV"

    local base="http://${REGISTRY_HOST}"

    # Health baseline
    info "Health endpoint..."
    run_hey_test "health-c100" "${base}/.health" 100 10
    run_hey_test "health-c200" "${base}/.health" 200 10

    # Maven — read seeded POM
    if [[ " ${PASSED_PROTOCOLS[*]} " =~ " maven " ]]; then
        info "Maven POM reads..."
        run_hey_test "maven-pom-c1"   "${base}/maven/com/bench/bench-read/1.0/bench-read-1.0.pom" 1 10
        run_hey_test "maven-pom-c10"  "${base}/maven/com/bench/bench-read/1.0/bench-read-1.0.pom" 10 10
        run_hey_test "maven-pom-c100" "${base}/maven/com/bench/bench-read/1.0/bench-read-1.0.pom" 100 10

        info "Maven group resolution..."
        run_hey_test "maven-group-pom-c10"  "${base}/maven_group/com/bench/bench-read/1.0/bench-read-1.0.pom" 10 10
        run_hey_test "maven-group-pom-c50"  "${base}/maven_group/com/bench/bench-read/1.0/bench-read-1.0.pom" 50 10
        run_hey_test "maven-group-pom-c100" "${base}/maven_group/com/bench/bench-read/1.0/bench-read-1.0.pom" 100 10
    fi

    # npm — read seeded metadata
    if [[ " ${PASSED_PROTOCOLS[*]} " =~ " npm " ]]; then
        info "npm metadata reads..."
        run_hey_test "npm-meta-c1"   "${base}/npm/@bench%2Fread" 1 10
        run_hey_test "npm-meta-c10"  "${base}/npm/@bench%2Fread" 10 10
        run_hey_test "npm-meta-c100" "${base}/npm/@bench%2Fread" 100 10

        info "npm group reads..."
        run_hey_test "npm-group-meta-c10"  "${base}/npm_group/@bench%2Fread" 10 10
        run_hey_test "npm-group-meta-c100" "${base}/npm_group/@bench%2Fread" 100 10
    fi

    # Docker — manifest reads via local and group
    if [[ " ${PASSED_PROTOCOLS[*]} " =~ " docker " ]]; then
        info "Docker manifest reads..."
        run_hey_test "docker-manifest-c1"  "${base}/docker_local/v2/bench-read/manifests/1.0" 1 10
        run_hey_test "docker-manifest-c10" "${base}/docker_local/v2/bench-read/manifests/1.0" 10 10
        run_hey_test "docker-manifest-c50" "${base}/docker_local/v2/bench-read/manifests/1.0" 50 10

        info "Docker group manifest reads..."
        run_hey_test "docker-group-manifest-c10" "${base}/docker_group/v2/bench-read/manifests/1.0" 10 10
        run_hey_test "docker-group-manifest-c50" "${base}/docker_group/v2/bench-read/manifests/1.0" 50 10
    fi

    # PyPI — simple index reads
    if [[ " ${PASSED_PROTOCOLS[*]} " =~ " pypi " ]]; then
        info "PyPI index reads..."
        run_hey_test "pypi-index-c1"   "${base}/pypi/simple/bench-read/" 1 10
        run_hey_test "pypi-index-c10"  "${base}/pypi/simple/bench-read/" 10 10
        run_hey_test "pypi-index-c100" "${base}/pypi/simple/bench-read/" 100 10
    fi

    info "Registry throughput phase complete"
    info "Results: ${HEY_STATS_CSV}"
}

phase_throughput() {
    log "PHASE 4: Real-Client Throughput (${BENCH_WRITERS}W + ${BENCH_READERS}R x ${BENCH_DURATION}s)"

    for proto in "${PASSED_PROTOCOLS[@]}"; do
        log "Throughput: ${proto}"
        local pids=()

        # Launch writers
        info "Launching ${BENCH_WRITERS} writers..."
        for i in $(seq 1 "$BENCH_WRITERS"); do
            writer_worker "$i" "$proto" "$BENCH_DURATION" &
            pids+=($!)
        done

        # Launch readers
        info "Launching ${BENCH_READERS} readers..."
        for i in $(seq 1 "$BENCH_READERS"); do
            reader_worker "$i" "$proto" "$BENCH_DURATION" &
            pids+=($!)
        done

        info "Waiting for ${#pids[@]} workers (${BENCH_DURATION}s)..."
        for pid in "${pids[@]}"; do
            wait "$pid" 2>/dev/null || true
        done
        info "${proto} throughput phase complete"
    done
}

# ================================================================
# Main
# ================================================================
main() {
    echo ""
    echo "  Pantera Benchmark v3 — Real Client Traffic"
    echo "  Registry:   ${REGISTRY_HOST}"
    echo "  Auth:       ${BENCH_USER}:****"
    echo "  Concurrency: ${BENCH_CONCURRENCY} (${BENCH_READERS}R / ${BENCH_WRITERS}W per protocol)"
    echo "  Duration:   ${BENCH_DURATION}s per protocol"
    echo "  Protocols:  ${BENCH_PROTOCOLS}"
    echo ""

    mkdir -p "${RESULTS}"
    WORKER_CSV_DIR="${RESULTS}/.workers"
    mkdir -p "$WORKER_CSV_DIR"
    rm -f "${WORKER_CSV_DIR}"/*.csv 2>/dev/null || true

    # Regenerate projects with runtime registry host (templates used build-time defaults)
    REGISTRY_HOST="$REGISTRY_HOST" \
    BENCH_USER="$BENCH_USER" \
    BENCH_PASS="$BENCH_PASS" \
    NPM_AUTH_TOKEN="$NPM_AUTH_TOKEN" \
    /bench/generate-projects.sh "$BENCH_WRITERS"

    # Phase 1: Correctness
    if [[ "$BENCH_SKIP_CORRECTNESS" != "1" ]]; then
        phase_correctness
    else
        PASSED_PROTOCOLS=("${PROTOCOLS[@]}")
    fi

    # Phase 2: Seed
    phase_seed

    # Phase 3: Registry throughput (hey)
    phase_registry_throughput

    # Phase 4: Real-client throughput
    phase_throughput

    # Phase 5: Merge + Aggregate + Report
    log "PHASE 5: Report"
    merge_worker_csvs
    info "Merged $(wc -l < "$RAW_CSV") timing entries"
    /bench/aggregate.sh

    log "BENCHMARK COMPLETE"
    echo ""
    echo "  Raw data: ${RAW_CSV}"
    echo "  Report:   ${RESULTS}/BENCHMARK-REPORT.md"
}

main "$@"
