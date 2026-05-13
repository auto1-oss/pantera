#!/usr/bin/env bash
# perf-gate-check.sh — assert M3-M4 invariants from a running Pantera.
#
# Scrapes the /metrics/vertx endpoint and fails the build when any of:
#
#   pantera_proxy_429_total                      > 0
#   pantera_outbound_rate_limited_total
#       {reason="gate_closed"}                   > 0
#   pantera_upstream_amplification_ratio         > 1.5
#
# Each is the production paging signal for the corresponding amplifier:
# the first means an upstream throttled us (M3's gate was set too high),
# the second means our own gate closed sustained (an upstream is still
# 429-ing through the back-off), the third means a regression added a
# new outbound source past M2's prefetch deletion. The thresholds match
# the Grafana alert rules shipped in
# pantera-main/docker-compose/prometheus/rules/amplification.yml.
#
# Usage:
#   perf-gate-check.sh <metrics-url>
#
# Returns:
#   0 — all invariants hold.
#   1 — at least one invariant breached. The breach is printed.
#
# Requires: curl, awk.
set -euo pipefail

METRICS_URL="${1:?Usage: perf-gate-check.sh <metrics-url>}"

echo "=== Pantera perf-gate check ==="
echo "Scraping: ${METRICS_URL}"

METRICS="$(curl -fsS --max-time 5 "${METRICS_URL}")"
if [ -z "${METRICS}" ]; then
  echo "ERROR: metrics endpoint returned empty body" >&2
  exit 1
fi

fail=0

# ------------------------------------------------------------------
# Check 1: pantera_proxy_429_total. Sum across all label combinations.
# Pre-M3 production saw sustained 429s from Maven Central during cold
# walks. Post-M3 this must stay at zero against a properly-tuned
# upstream — any non-zero is a regression in the rate-limit
# configuration or a new outbound source bypassing the limiter.
# ------------------------------------------------------------------
total_429="$(echo "${METRICS}" \
  | awk '/^pantera_proxy_429_total\{/ { sum += $NF } END { print sum + 0 }')"
echo "pantera_proxy_429_total = ${total_429}"
if awk -v v="${total_429}" 'BEGIN { exit !(v + 0 > 0) }'; then
  echo "FAIL: upstream returned at least one 429 during this run" >&2
  fail=1
fi

# ------------------------------------------------------------------
# Check 2: outbound rate-limited gate_closed. A non-zero bucket_empty
# count is healthy (limiter is doing its job during bursts); a non-zero
# gate_closed count means an upstream is still rate-limiting us
# through the back-off window. The latter is the operational signal
# that the limiter is set too high.
# ------------------------------------------------------------------
gate_closed="$(echo "${METRICS}" \
  | awk '/^pantera_outbound_rate_limited_total\{[^}]*reason="gate_closed"/ { sum += $NF } END { print sum + 0 }')"
echo "pantera_outbound_rate_limited_total{reason=\"gate_closed\"} = ${gate_closed}"
if awk -v v="${gate_closed}" 'BEGIN { exit !(v + 0 > 0) }'; then
  echo "FAIL: rate-limit gate closed during this run — upstream is throttling us through the back-off" >&2
  fail=1
fi

# ------------------------------------------------------------------
# Check 3: amplification ratio. Use the recording-rule output if
# Prometheus is colocated (it usually isn't in CI), else compute
# directly from raw counters. The ratio is undefined when there is no
# inbound traffic; we tolerate "no samples yet" as a pass (ratio = 0).
# ------------------------------------------------------------------
outbound="$(echo "${METRICS}" \
  | awk '/^pantera_upstream_requests_total\{/ { sum += $NF } END { print sum + 0 }')"
inbound="$(echo "${METRICS}" \
  | awk '/^pantera_http_requests_total\{/ { sum += $NF } END { print sum + 0 }')"
echo "pantera_upstream_requests_total (sum) = ${outbound}"
echo "pantera_http_requests_total    (sum) = ${inbound}"
if [ "${inbound}" -gt 0 ]; then
  ratio="$(awk -v o="${outbound}" -v i="${inbound}" 'BEGIN { printf "%.2f", o / i }')"
  echo "amplification ratio = ${ratio}"
  if awk -v r="${ratio}" 'BEGIN { exit !(r + 0 > 1.5) }'; then
    echo "FAIL: amplification ratio ${ratio} exceeds 1.5 — a regression has added a new outbound source" >&2
    fail=1
  fi
else
  echo "(no inbound traffic yet — amplification check skipped)"
fi

if [ "${fail}" -eq 0 ]; then
  echo "PASS: all M3-M4 invariants hold."
  exit 0
fi
exit 1
