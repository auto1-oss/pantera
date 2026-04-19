#!/usr/bin/env bash
# perf-benchmark.sh — drive N requests against Pantera and record latency stats.
#
# Usage: perf-benchmark.sh <base_url> <output_json>
#   base_url    — e.g. http://localhost:8080
#   output_json — path to write results JSON
#
# Requires: wrk (https://github.com/wg/wrk)
set -euo pipefail

BASE_URL="${1:?Usage: perf-benchmark.sh <base_url> <output_json>}"
OUTPUT="${2:?Usage: perf-benchmark.sh <base_url> <output_json>}"

DURATION="30s"
THREADS=4
CONNECTIONS=50
PATH_UNDER_TEST="/artifactory/api/npm/npm_proxy/@types/node/-/node-22.0.0.tgz"

if ! command -v wrk &>/dev/null; then
  echo "ERROR: 'wrk' is not installed. Install it with: apt-get install wrk / brew install wrk"
  exit 1
fi

echo "=== Perf benchmark ==="
echo "Target: ${BASE_URL}${PATH_UNDER_TEST}"
echo "Duration: ${DURATION}, Threads: ${THREADS}, Connections: ${CONNECTIONS}"

# wrk outputs a latency distribution; we parse the summary line.
# wrk --latency flag prints percentile breakdown.
RAW=$(wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${DURATION}" --latency \
  "${BASE_URL}${PATH_UNDER_TEST}" 2>&1) || true

echo "${RAW}"

# Parse latency percentiles from wrk output.
# wrk --latency output format:
#   50%    1.23ms
#   75%    2.34ms
#   90%    3.45ms
#   99%    4.56ms
parse_latency_ms() {
  local pct="$1"
  local val
  val=$(echo "${RAW}" | grep -E "^\s+${pct}%" | awk '{print $2}')
  if [ -z "${val}" ]; then
    echo "0"
    return
  fi
  # Convert units: wrk reports in us, ms, or s
  if echo "${val}" | grep -q 'us$'; then
    echo "${val}" | sed 's/us$//' | awk '{printf "%.2f", $1 / 1000}'
  elif echo "${val}" | grep -q 'ms$'; then
    echo "${val}" | sed 's/ms$//'
  elif echo "${val}" | grep -q 's$'; then
    echo "${val}" | sed 's/s$//' | awk '{printf "%.2f", $1 * 1000}'
  else
    echo "0"
  fi
}

# Parse throughput (Requests/sec line)
parse_throughput() {
  echo "${RAW}" | grep 'Requests/sec' | awk '{printf "%.0f", $2}'
}

P50=$(parse_latency_ms 50)
P95=$(parse_latency_ms 99)  # wrk does not print 95th; use 99th as upper bound
P99=$(parse_latency_ms 99)
THROUGHPUT=$(parse_throughput)

# Default to placeholder values if wrk could not connect (e.g. no running server)
P50="${P50:-0}"
P95="${P95:-0}"
P99="${P99:-0}"
THROUGHPUT="${THROUGHPUT:-0}"

cat > "${OUTPUT}" <<EOF
{"p50_ms": ${P50}, "p95_ms": ${P95}, "p99_ms": ${P99}, "throughput_rps": ${THROUGHPUT}}
EOF

echo ""
echo "Results written to ${OUTPUT}:"
cat "${OUTPUT}"
