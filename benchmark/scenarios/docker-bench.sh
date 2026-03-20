#!/usr/bin/env bash
##
## Docker benchmark scenarios using the real docker client:
##   1. Local Push   — docker push images of different sizes
##   2. Local Pull   — docker pull (with rmi between iterations)
##   3. Proxy Pull   — docker pull through docker_proxy (Docker Hub via cache)
##   4. Concurrent   — parallel docker push/pull at increasing concurrency
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="${BENCH_DIR}/results"
FIXTURES_DIR="${BENCH_DIR}/fixtures"

source "${SCRIPT_DIR}/common.sh"

OLD_PORT="${OLD_PORT:-9081}"
NEW_PORT="${NEW_PORT:-9091}"
PANTERA_USER="${PANTERA_USER_NAME:-pantera}"
PANTERA_PASS="${PANTERA_USER_PASS:-pantera}"

CONCURRENCY_LEVELS="${DOCKER_CONCURRENCY_LEVELS:-1 5 10}"
ITERATIONS="${DOCKER_ITERATIONS:-5}"

docker_results="${RESULTS_DIR}/docker.csv"
mkdir -p "$RESULTS_DIR"
csv_header "$docker_results"

TIMING_BASE=$(mktemp -d)
trap 'rm -rf "$TIMING_BASE"' EXIT

# Image sizes for throughput tests (built during fixture generation)
SIZES="small medium large"
# small=~5MB, medium=~50MB, large=~200MB

# Number of unique images for concurrency tests (built during fixture generation)
CONC_IMAGE_COUNT=20

# ============================================================
# Helpers
# ============================================================

docker_login() {
    local port="$1"
    echo "$PANTERA_PASS" | docker login "localhost:${port}" \
        -u "$PANTERA_USER" --password-stdin 2>/dev/null || true
}

# Get the size of a Docker image in bytes
image_size_bytes() {
    local image="$1"
    docker image inspect "$image" --format='{{.Size}}' 2>/dev/null || echo "0"
}

# ============================================================
# SCENARIO 1: Docker Local Push (single-threaded throughput)
# ============================================================
run_docker_push() {
    bench_log "=== Scenario 1: Docker Local Push ==="

    for size_label in $SIZES; do
        local src_image="bench-${size_label}:latest"
        if ! docker image inspect "$src_image" >/dev/null 2>&1; then
            bench_log "  Skipping $size_label (image bench-${size_label}:latest not found)"
            continue
        fi
        local img_bytes
        img_bytes=$(image_size_bytes "$src_image")

        for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
            IFS=: read -r tag port version <<< "$version_info"
            docker_login "$port"

            bench_log "  Push ${size_label} → ${version} (${ITERATIONS} ops)..."

            local tdir="${TIMING_BASE}/docker-push-${size_label}-${tag}"
            mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start wall_end
            wall_start=$(now_ms)

            for i in $(seq 1 "$ITERATIONS"); do
                local dest="localhost:${port}/docker_local/bench-${size_label}:v${i}"
                docker tag "$src_image" "$dest" 2>/dev/null

                local s e rc
                s=$(now_ms)
                docker push "$dest" >/dev/null 2>&1
                rc=$?
                e=$(now_ms)
                echo "$((e - s)) $rc" > "$tdir/op-${i}.txt"

                # Clean up local tag (keep the src)
                docker rmi "$dest" >/dev/null 2>&1 || true
            done

            wall_end=$(now_ms)

            csv_row "$docker_results" "docker-local-push" "$version" "$size_label" "1" \
                "$tdir" "$((wall_end - wall_start))" "$img_bytes"
        done
    done
}

# ============================================================
# SCENARIO 2: Docker Local Pull (with rmi between iterations)
# ============================================================
run_docker_pull() {
    bench_log "=== Scenario 2: Docker Local Pull ==="

    # Seed: push one tag per size for pull testing
    for size_label in $SIZES; do
        local src_image="bench-${size_label}:latest"
        docker image inspect "$src_image" >/dev/null 2>&1 || continue

        for version_info in "old:${OLD_PORT}" "new:${NEW_PORT}"; do
            IFS=: read -r tag port <<< "$version_info"
            docker_login "$port"
            local dest="localhost:${port}/docker_local/bench-${size_label}:pull-test"
            docker tag "$src_image" "$dest" 2>/dev/null
            docker push "$dest" >/dev/null 2>&1 || true
            docker rmi "$dest" >/dev/null 2>&1 || true
        done
    done
    sleep 2

    for size_label in $SIZES; do
        local src_image="bench-${size_label}:latest"
        docker image inspect "$src_image" >/dev/null 2>&1 || continue
        local img_bytes
        img_bytes=$(image_size_bytes "$src_image")

        for conc in $CONCURRENCY_LEVELS; do
            local ops=$ITERATIONS
            [[ $conc -gt $ops ]] && ops=$conc

            for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
                IFS=: read -r tag port version <<< "$version_info"
                docker_login "$port"

                bench_log "  Pull ${size_label} c=${conc} ${version} (${ops} ops)..."

                local tdir="${TIMING_BASE}/docker-pull-${size_label}-c${conc}-${tag}"
                mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

                # For concurrent pulls we need unique images (Docker daemon deduplicates same-digest layers).
                # Seed unique images if concurrency > 1.
                if [[ $conc -gt 1 ]]; then
                    bench_log "    Seeding ${ops} unique images for concurrent pull..."
                    for i in $(seq 1 "$ops"); do
                        local conc_src="bench-conc-${i}:latest"
                        if docker image inspect "$conc_src" >/dev/null 2>&1; then
                            local dest="localhost:${port}/docker_local/bench-conc-${i}:pull-c${conc}"
                            docker tag "$conc_src" "$dest" 2>/dev/null
                            docker push "$dest" >/dev/null 2>&1 || true
                            docker rmi "$dest" >/dev/null 2>&1 || true
                        fi
                    done
                    sleep 1
                fi

                local wall_start wall_end
                wall_start=$(now_ms)

                if [[ $conc -eq 1 ]]; then
                    # Sequential pulls of the same image (rmi between each)
                    local pull_img="localhost:${port}/docker_local/bench-${size_label}:pull-test"
                    for i in $(seq 1 "$ops"); do
                        docker rmi "$pull_img" >/dev/null 2>&1 || true
                        local s e rc
                        s=$(now_ms)
                        docker pull "$pull_img" >/dev/null 2>&1
                        rc=$?
                        e=$(now_ms)
                        echo "$((e - s)) $rc" > "$tdir/op-${i}.txt"
                    done
                else
                    # Concurrent pulls of unique images
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
                                local pull_img="localhost:${port}/docker_local/bench-conc-${oid}:pull-c${conc}"
                                docker rmi "$pull_img" >/dev/null 2>&1 || true
                                local s e rc
                                s=$(now_ms)
                                docker pull "$pull_img" >/dev/null 2>&1
                                rc=$?
                                e=$(now_ms)
                                echo "$((e - s)) $rc" > "$tdir/op-${oid}.txt"
                            ) &
                        done
                        wait
                    done
                fi

                wall_end=$(now_ms)

                csv_row "$docker_results" "docker-local-pull" "$version" "$size_label" "$conc" \
                    "$tdir" "$((wall_end - wall_start))" "$img_bytes"
            done
        done
    done
}

# ============================================================
# SCENARIO 3: Docker Proxy Pull (through docker_proxy → Docker Hub)
# ============================================================
run_docker_proxy() {
    bench_log "=== Scenario 3: Docker Proxy Pull (docker_proxy → Docker Hub) ==="

    # Small well-known images for proxy testing
    local -a PROXY_IMAGES=(
        "library/alpine:3.19"
        "library/busybox:1.36"
    )

    # Warm proxy cache
    bench_log "  Warming proxy cache..."
    for img in "${PROXY_IMAGES[@]}"; do
        for version_info in "old:${OLD_PORT}" "new:${NEW_PORT}"; do
            IFS=: read -r tag port <<< "$version_info"
            docker_login "$port"
            local pull_name="localhost:${port}/docker_proxy/${img}"
            docker pull "$pull_name" >/dev/null 2>&1 || true
            docker rmi "$pull_name" >/dev/null 2>&1 || true
        done
    done
    sleep 2

    # Benchmark warm cache pulls
    local test_image="${PROXY_IMAGES[0]}"
    bench_log "  Benchmarking warm proxy pulls for ${test_image}..."

    for conc in $CONCURRENCY_LEVELS; do
        local ops=$ITERATIONS
        [[ $conc -gt $ops ]] && ops=$conc

        for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
            IFS=: read -r tag port version <<< "$version_info"
            docker_login "$port"

            bench_log "  Proxy pull c=${conc} ${version} (${ops} ops)..."

            local tdir="${TIMING_BASE}/docker-proxy-c${conc}-${tag}"
            mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

            local pull_name="localhost:${port}/docker_proxy/${test_image}"

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
                        docker rmi "$pull_name" >/dev/null 2>&1 || true
                        local s e rc
                        s=$(now_ms)
                        docker pull "$pull_name" >/dev/null 2>&1
                        rc=$?
                        e=$(now_ms)
                        echo "$((e - s)) $rc" > "$tdir/op-${oid}.txt"
                    ) &
                done
                wait
            done

            wall_end=$(now_ms)

            csv_row "$docker_results" "docker-proxy-pull" "$version" "proxy" "$conc" \
                "$tdir" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# SCENARIO 4: Docker Concurrent Push
# ============================================================
run_docker_concurrent_push() {
    bench_log "=== Scenario 4: Docker Concurrent Push ==="

    for conc in $CONCURRENCY_LEVELS; do
        [[ $conc -eq 1 ]] && continue  # Already covered in scenario 1
        local ops=$conc  # One round of $conc parallel pushes

        for version_info in "old:${OLD_PORT}:v1.20.12" "new:${NEW_PORT}:v1.22.0"; do
            IFS=: read -r tag port version <<< "$version_info"
            docker_login "$port"

            bench_log "  Concurrent push c=${conc} ${version}..."

            local tdir="${TIMING_BASE}/docker-conc-push-c${conc}-${tag}"
            mkdir -p "$tdir" && find "$tdir" -name 'op-*.txt' -delete 2>/dev/null || true

            local wall_start wall_end
            wall_start=$(now_ms)

            for w in $(seq 1 "$ops"); do
                (
                    local src="bench-conc-${w}:latest"
                    if docker image inspect "$src" >/dev/null 2>&1; then
                        local dest="localhost:${port}/docker_local/bench-conc-${w}:cpush-${conc}"
                        docker tag "$src" "$dest" 2>/dev/null
                        local s e rc
                        s=$(now_ms)
                        docker push "$dest" >/dev/null 2>&1
                        rc=$?
                        e=$(now_ms)
                        echo "$((e - s)) $rc" > "$tdir/op-${w}.txt"
                        docker rmi "$dest" >/dev/null 2>&1 || true
                    else
                        echo "0 1" > "$tdir/op-${w}.txt"
                    fi
                ) &
            done
            wait

            wall_end=$(now_ms)

            csv_row "$docker_results" "docker-concurrent-push" "$version" "small" "$conc" \
                "$tdir" "$((wall_end - wall_start))" "0"
        done
    done
}

# ============================================================
# Main
# ============================================================
main() {
    bench_log "Starting Docker benchmarks (real docker client)"
    bench_log "  OLD: localhost:${OLD_PORT}"
    bench_log "  NEW: localhost:${NEW_PORT}"
    bench_log "  Concurrency: ${CONCURRENCY_LEVELS}"
    bench_log "  Iterations: ${ITERATIONS}"

    if ! command -v docker >/dev/null 2>&1; then
        bench_log "ERROR: docker not found."
        exit 1
    fi

    # Login to both instances
    docker_login "$OLD_PORT"
    docker_login "$NEW_PORT"

    run_docker_push
    run_docker_pull
    run_docker_proxy
    run_docker_concurrent_push

    bench_log "Docker benchmarks complete. Results in ${docker_results}"
}

main "$@"
