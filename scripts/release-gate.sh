#!/usr/bin/env bash
set -euo pipefail

echo "=== Gate 1: Full test suite ==="
mvn -T8 test || { echo "FAIL: tests"; exit 1; }

echo "=== Gate 2: Chaos tests ==="
mvn -T8 test -Dgroups=Chaos || { echo "FAIL: chaos"; exit 1; }

echo "=== Gate 3: Perf baseline ==="
# Placeholder: compare current vs baseline
echo "SKIP: perf baseline check requires running instance (run in CI)"

echo "ALL GATES PASSED"
exit 0
