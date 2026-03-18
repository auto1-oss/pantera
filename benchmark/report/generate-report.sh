#!/usr/bin/env bash
##
## Generate a Markdown benchmark report from CSV results.
## Reads: results/maven.csv, results/docker.csv, results/npm.csv
## Outputs: results/BENCHMARK-REPORT.md
##
## CSV format:
##   scenario,version,size,concurrency,ops,rps,latency_mean_ms,latency_p50_ms,
##   latency_p95_ms,latency_p99_ms,latency_max_ms,error_pct,transfer_mbps
##
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="${BENCH_DIR}/results"
REPORT="${RESULTS_DIR}/BENCHMARK-REPORT.md"

log() { echo "[report] $*"; }

# Compute delta percentage between old and new values.
# Usage: delta_pct <old> <new>
delta_pct() {
    local old="$1" new="$2"
    if [[ "$old" == "0" || -z "$old" || "$old" == "N/A" ]]; then
        echo "N/A"
        return
    fi
    local pct
    pct=$(echo "scale=1; ($new - $old) * 100 / $old" | bc 2>/dev/null || echo "0")
    local sign=""
    [[ "${pct:0:1}" != "-" ]] && sign="+"
    echo "${sign}${pct}%"
}

# Generate a comparison table for a given scenario from CSV.
# Usage: generate_table <csv_file> <scenario_filter> <title>
generate_table() {
    local csv="$1" scenario="$2" title="$3"

    # Check if this scenario has data
    local row_count
    row_count=$(tail -n +2 "$csv" | awk -F, -v sc="$scenario" '$1==sc' | wc -l | tr -d ' ')
    if [[ "$row_count" -eq 0 ]]; then
        return
    fi

    echo ""
    echo "### ${title}"
    echo ""
    echo "| Size | Concurrency | Ops | Metric | v1.20.12 | v1.22.0 | Delta |"
    echo "|------|-------------|-----|--------|----------|---------|-------|"

    # Get unique size+concurrency combos
    local combos
    combos=$(tail -n +2 "$csv" | awk -F, -v sc="$scenario" '$1==sc {print $3","$4}' | sort -u -t, -k1,1 -k2,2n)

    while IFS=, read -r size conc; do
        [[ -z "$size" ]] && continue

        # Extract values for old and new
        local old_row new_row
        old_row=$(tail -n +2 "$csv" | awk -F, -v sc="$scenario" -v s="$size" -v c="$conc" \
            '$1==sc && $2=="v1.20.12" && $3==s && $4==c' | head -1)
        new_row=$(tail -n +2 "$csv" | awk -F, -v sc="$scenario" -v s="$size" -v c="$conc" \
            '$1==sc && $2=="v1.22.0" && $3==s && $4==c' | head -1)

        [[ -z "$old_row" && -z "$new_row" ]] && continue

        # Parse: scenario,version,size,concurrency,ops,rps,mean,p50,p95,p99,max,error_pct,transfer_mbps
        local old_ops old_rps old_mean old_p50 old_p95 old_p99 old_max old_err old_mbps
        local new_ops new_rps new_mean new_p50 new_p95 new_p99 new_max new_err new_mbps

        IFS=, read -r _ _ _ _ old_ops old_rps old_mean old_p50 old_p95 old_p99 old_max old_err old_mbps <<< "$old_row"
        IFS=, read -r _ _ _ _ new_ops new_rps new_mean new_p50 new_p95 new_p99 new_max new_err new_mbps <<< "$new_row"

        local display_ops="${old_ops:-${new_ops:-0}}"

        # RPS (higher is better)
        echo "| ${size} | ${conc} | ${display_ops} | **Ops/sec** | ${old_rps:-N/A} | ${new_rps:-N/A} | $(delta_pct "${old_rps:-0}" "${new_rps:-0}") |"
        # Mean latency
        echo "| | | | Mean (ms) | ${old_mean:-N/A} | ${new_mean:-N/A} | $(delta_pct "${old_mean:-0}" "${new_mean:-0}") |"
        # p50 latency
        echo "| | | | p50 (ms) | ${old_p50:-N/A} | ${new_p50:-N/A} | $(delta_pct "${old_p50:-0}" "${new_p50:-0}") |"
        # p95 latency
        echo "| | | | p95 (ms) | ${old_p95:-N/A} | ${new_p95:-N/A} | $(delta_pct "${old_p95:-0}" "${new_p95:-0}") |"
        # p99 latency
        echo "| | | | p99 (ms) | ${old_p99:-N/A} | ${new_p99:-N/A} | $(delta_pct "${old_p99:-0}" "${new_p99:-0}") |"
        # Error rate
        echo "| | | | Error % | ${old_err:-0} | ${new_err:-0} | $(delta_pct "${old_err:-0}" "${new_err:-0}") |"
        # Transfer rate (only if meaningful)
        if [[ "${old_mbps:-0}" != "0" || "${new_mbps:-0}" != "0" ]]; then
            echo "| | | | Transfer (MB/s) | ${old_mbps:-N/A} | ${new_mbps:-N/A} | $(delta_pct "${old_mbps:-0}" "${new_mbps:-0}") |"
        fi
    done <<< "$combos"
}

# ============================================================
# Generate report
# ============================================================
main() {
    log "Generating benchmark report..."

    cat > "$REPORT" <<'HEADER'
# Artipie Performance Benchmark Report
## v1.20.12 vs v1.22.0

> Benchmarks use **real client tools** (mvn, docker, npm) — not synthetic HTTP load generators.
> All measurements reflect actual client-perceived latency including protocol overhead, authentication, and content negotiation.

HEADER

    # Environment info
    cat >> "$REPORT" <<ENV
### Test Environment

| Parameter | Value |
|-----------|-------|
| Date | $(date -u '+%Y-%m-%d %H:%M UTC') |
| OS | $(uname -s) $(uname -r) |
| CPU | $(sysctl -n machdep.cpu.brand_string 2>/dev/null || lscpu 2>/dev/null | grep 'Model name' | sed 's/.*: //' || echo 'N/A') |
| CPU Cores | $(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 'N/A') |
| Memory | $(sysctl -n hw.memsize 2>/dev/null | awk '{printf "%.0f GB", $1/1073741824}' 2>/dev/null || free -h 2>/dev/null | awk '/^Mem:/{print $2}' || echo 'N/A') |
| Docker | $(docker --version 2>/dev/null | head -1 || echo 'N/A') |
| Maven | $(mvn --version 2>&1 | head -1 || echo 'N/A') |
| NPM | npm $(npm --version 2>/dev/null || echo 'N/A') |
| Container CPUs | 4 per Artipie instance |
| Container Memory | 8 GB per Artipie instance |

**Methodology**: Each operation is timed individually. Latency percentiles are computed from per-operation measurements.
Ops/sec = total successful operations / wall-clock time. Concurrency = parallel client processes.

---

ENV

    # Maven results
    if [[ -f "${RESULTS_DIR}/maven.csv" ]]; then
        {
            echo "## Maven Repository Benchmarks"
            echo ""
            echo "Upload uses \`mvn deploy:deploy-file\` to the local \`maven\` repo."
            echo "All downloads use \`mvn dependency:copy\` through \`maven_group\` (fan-out to local + Maven Central proxy) — the production read path."
            generate_table "${RESULTS_DIR}/maven.csv" "maven-local-upload" "Maven Local Upload (mvn deploy:deploy-file)"
            generate_table "${RESULTS_DIR}/maven.csv" "maven-group-download" "Maven Group Download — local artifact (maven_group → maven member)"
            generate_table "${RESULTS_DIR}/maven.csv" "maven-proxy-download" "Maven Group Download — Central artifact (maven_group → maven_proxy member, warm cache)"
            generate_table "${RESULTS_DIR}/maven.csv" "maven-mixed-read" "Maven Mixed — Read latency during concurrent upload+download"
            generate_table "${RESULTS_DIR}/maven.csv" "maven-mixed-write" "Maven Mixed — Write latency during concurrent upload+download"
            echo ""
            echo "---"
            echo ""
        } >> "$REPORT"
    fi

    # Docker results
    if [[ -f "${RESULTS_DIR}/docker.csv" ]]; then
        {
            echo "## Docker Registry Benchmarks"
            echo ""
            echo "Push/pull use the real \`docker\` CLI. Each pull iteration removes the local image first (\`docker rmi\`)."
            echo "Concurrent pulls use unique images with distinct layer digests to avoid Docker daemon deduplication."
            echo "Proxy tests pull Docker Hub images through \`docker_proxy\` (warm cache after initial seed)."
            generate_table "${RESULTS_DIR}/docker.csv" "docker-local-push" "Docker Local Push (docker push)"
            generate_table "${RESULTS_DIR}/docker.csv" "docker-local-pull" "Docker Local Pull (docker pull)"
            generate_table "${RESULTS_DIR}/docker.csv" "docker-concurrent-push" "Docker Concurrent Push"
            generate_table "${RESULTS_DIR}/docker.csv" "docker-proxy-pull" "Docker Proxy Pull (warm cache)"
            echo ""
            echo "---"
            echo ""
        } >> "$REPORT"
    fi

    # NPM results
    if [[ -f "${RESULTS_DIR}/npm.csv" ]]; then
        {
            echo "## NPM Registry Benchmarks"
            echo ""
            echo "Publish uses \`npm publish\` to the local \`npm\` repo."
            echo "All installs use \`npm install\` through \`npm_group\` (fan-out to local + npmjs.org proxy) — the production read path."
            generate_table "${RESULTS_DIR}/npm.csv" "npm-local-publish" "NPM Local Publish (npm publish)"
            generate_table "${RESULTS_DIR}/npm.csv" "npm-group-install" "NPM Group Install — local package (npm_group → npm member)"
            generate_table "${RESULTS_DIR}/npm.csv" "npm-proxy-install" "NPM Group Install — npmjs.org package (npm_group → npm_proxy member, warm cache)"
            generate_table "${RESULTS_DIR}/npm.csv" "npm-mixed-read" "NPM Mixed — Read latency during concurrent publish+install"
            generate_table "${RESULTS_DIR}/npm.csv" "npm-mixed-write" "NPM Mixed — Write latency during concurrent publish+install"
            echo ""
            echo "---"
            echo ""
        } >> "$REPORT"
    fi

    # Summary
    cat >> "$REPORT" <<'SUMMARY'
## Notes

- **Group resolution**: All Maven and NPM downloads go through group repos (`maven_group`, `npm_group`), mirroring the production read path. The group fans out to local + proxy members with parallel resolution. This exercises the `GroupSlice` code path that was heavily optimized in v1.22.0.
- **Latency includes client overhead**: Maven JVM startup (~2-3s), npm/Node.js startup (~0.5s), Docker daemon processing.
  This overhead is constant across both versions, so relative comparisons remain valid.
- **Error %** reflects complete client-side failures (non-zero exit code from mvn/docker/npm).
- **Proxy warm cache**: Proxy scenarios seed the cache first, then benchmark. This measures Artipie's cache-serving performance, not upstream network speed.
- **Docker concurrency**: Concurrent pull tests use unique images (distinct layer digests) to prevent Docker daemon from sharing layer downloads between concurrent operations.

SUMMARY

    log "Report generated: ${REPORT}"
    echo ""
    echo "=========================================="
    echo "  Report: ${REPORT}"
    echo "=========================================="
}

main "$@"
