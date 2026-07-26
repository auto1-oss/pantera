#!/usr/bin/env bash
#
# WS1 storage load test runner — the >=1000 req/s R+W release gate.
#
# Drives the real production CachedBlobStorage -> MeteredBlobStore -> S3Storage
# stack (cache.mode: index) against a MinIO container (managed automatically by
# Testcontainers) and records the measured read/write throughput.
#
# Usage:  docs/slo/load-test/run-load-test.sh
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
cd "$root"

results="$here/RESULTS.md"
log="$(mktemp -t ws1-loadtest.XXXXXX.log)"

echo ">> Checking Docker..."
docker ps >/dev/null 2>&1 || { echo "ERROR: Docker is not reachable — the MinIO container cannot start." >&2; exit 1; }

echo ">> Running S3CacheLoadITCase (MinIO via Testcontainers)..."
# The itcase profile runs failsafe over *ITCase; -Dexec.skip=true skips the
# pom-bound docker buildx. Testcontainers pulls/starts/stops MinIO itself.
# -Drun.load.test=true opts the throughput gate in (it is @EnabledIfSystemProperty
# on that flag, so the regular CI build skips it).
if ! mvn verify -pl pantera-storage/pantera-storage-s3 -Pitcase \
      -Dit.test=S3CacheLoadITCase -Drun.load.test=true -Dexec.skip=true -ntp -DskipUTs=true >"$log" 2>&1; then
  echo "ERROR: load test build failed. Tail of log:" >&2
  tail -40 "$log" >&2
  exit 1
fi

result_block="$(grep -A3 'WS1 LOAD RESULT' "$log" || true)"
if [[ -z "$result_block" ]]; then
  echo "ERROR: no WS1 LOAD RESULT emitted — see $log" >&2
  exit 1
fi

echo
echo "$result_block"
echo

{
  echo "## Run $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo '```'
  echo "$result_block"
  echo '```'
  echo
} >>"$results"

echo ">> PASS — both phases cleared the >=1000 req/s gate (assertions in the ITCase). Appended to $results"
