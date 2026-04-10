#!/usr/bin/env bash
# =================================================================
# PEP 691 JSON Simple API test via uv
#
# Prerequisites:
#   - Pantera running at localhost:8081 with pypi_group configured
#   - hello package uploaded to the hosted pypi repo
#   - uv installed (curl -LsSf https://astral.sh/uv/install.sh | sh)
#
# Usage:
#   ./test.sh                    # full test: lock + sync + pytest
#   ./test.sh --lock-only        # just test resolution (no install)
#   ./test.sh --json-only        # just test the raw JSON endpoint
# =================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PANTERA_URL="${PANTERA_URL:-http://ayd:ayd@localhost:8081/test_prefix/api/pypi/pypi_group/simple/}"

echo "=== Pantera PEP 691 / uv test ==="
echo "Index: $PANTERA_URL"
echo ""

# ------------------------------------------------------------------
# Test 1: Raw JSON endpoint (no uv dependency)
# ------------------------------------------------------------------
if [[ "${1:-}" == "--json-only" ]] || [[ "${1:-}" == "" ]]; then
    echo "--- Test 1: Raw PEP 691 JSON endpoint ---"
    HTTP_CODE=$(curl -s -o /tmp/pantera-pep691.json -w '%{http_code}' \
        -H 'Accept: application/vnd.pypi.simple.v1+json' \
        "${PANTERA_URL}hello/")

    if [[ "$HTTP_CODE" != "200" ]]; then
        echo "FAIL: Expected HTTP 200, got $HTTP_CODE"
        cat /tmp/pantera-pep691.json
        exit 1
    fi

    # Must be parseable JSON (not HTML)
    if ! python3 -m json.tool /tmp/pantera-pep691.json > /dev/null 2>&1; then
        echo "FAIL: Response is not valid JSON"
        head -5 /tmp/pantera-pep691.json
        exit 1
    fi

    # Check PEP 691 structure
    python3 -c "
import json, sys
with open('/tmp/pantera-pep691.json') as f:
    data = json.load(f)
assert data.get('meta', {}).get('api-version') == '1.1', 'missing api-version 1.1'
assert data.get('name') == 'hello', f'wrong name: {data.get(\"name\")}'
assert len(data.get('files', [])) > 0, 'no files in response'
for f in data['files']:
    url = f.get('url', '')
    assert not url.startswith('//'), f'protocol-relative URL: {url}'
    assert 'sha256' in f.get('hashes', {}), f'missing sha256 for {f.get(\"filename\")}'
    yanked = f.get('yanked')
    assert yanked is False or isinstance(yanked, str), f'yanked must be false or string: {yanked}'
print(f'  OK: {len(data[\"files\"])} file(s), all URLs relative, PEP 691 compliant')
"
    echo ""
fi

if [[ "${1:-}" == "--json-only" ]]; then
    echo "=== JSON test passed ==="
    exit 0
fi

# ------------------------------------------------------------------
# Test 2: uv lock (PEP 691 + PEP 700 exclude-newer)
# ------------------------------------------------------------------
echo "--- Test 2: uv lock (PEP 691 + exclude-newer) ---"
if ! command -v uv &> /dev/null; then
    echo "SKIP: uv not installed (curl -LsSf https://astral.sh/uv/install.sh | sh)"
    exit 0
fi

# Clean previous state
rm -f uv.lock
rm -rf .venv

uv lock --verbose 2>&1 | tail -5
echo "  OK: uv lock succeeded"
echo ""

# ------------------------------------------------------------------
# Test 2b: exclude-newer against PROXIED package
#
# This is the real PEP 700 test. We create a throwaway project that
# depends on requests>=2.28.0 with exclude-newer = 2022-07-01.
# requests 2.28.0 was published 2022-06-29, 2.28.1 on 2022-08-29.
# uv must resolve exactly 2.28.0 — if the proxy doesn't forward
# upload-time from upstream, uv ignores the constraint and resolves
# the latest version.
# ------------------------------------------------------------------
echo "--- Test 2b: exclude-newer against proxied package ---"
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT
cat > "$TMPDIR/pyproject.toml" <<PYPROJECT
[project]
name = "exclude-newer-proxy-test"
version = "0.1.0"
requires-python = ">=3.10"
dependencies = ["requests>=2.28.0"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.uv]
index-url = "${PANTERA_URL}"
exclude-newer = "2022-07-01T00:00:00Z"
PYPROJECT

if uv lock --directory "$TMPDIR" 2>&1 | tail -3; then
    # Check that requests resolved to 2.28.0
    RESOLVED=$(grep -A1 'name = "requests"' "$TMPDIR/uv.lock" | grep 'version' | head -1 | sed 's/.*"\(.*\)"/\1/')
    if [[ "$RESOLVED" == "2.28.0" ]]; then
        echo "  OK: proxied exclude-newer works — requests pinned to $RESOLVED"
    else
        echo "  FAIL: expected requests==2.28.0, got $RESOLVED"
        echo "  This means the proxy is not forwarding PEP 700 upload-time."
        exit 1
    fi
else
    echo "  FAIL: uv lock with exclude-newer failed"
    exit 1
fi
echo ""

if [[ "${1:-}" == "--lock-only" ]]; then
    echo "=== Lock test passed ==="
    exit 0
fi

# ------------------------------------------------------------------
# Test 3: uv sync + pytest
# ------------------------------------------------------------------
echo "--- Test 3: uv sync + pytest ---"
uv sync 2>&1 | tail -3
uv add --dev pytest 2>&1 | tail -1
uv run python -m pytest tests/ -v

echo ""
echo "=== All PEP 691 tests passed ==="
