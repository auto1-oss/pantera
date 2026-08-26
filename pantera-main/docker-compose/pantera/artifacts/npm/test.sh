#!/usr/bin/env bash
# =================================================================
# npm / yarn / pnpm / corepack conformance harness.
#
# Guards the endpoint matrix in
#   docs/superpowers/specs/2026-08-26-npm-cli-conformance-design.md
# against the running local stack. A status that changes shape fails
# here rather than being discovered months later.
#
# Usage:
#   ./test.sh                 # everything
#   ./test.sh --endpoints     # endpoint matrix only
#   ./test.sh --clients       # npm / yarn / pnpm installs only
#   ./test.sh --corepack      # corepack only
#   ./test.sh --rev           # packument revision lifecycle only
# =================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Backend directly, bypassing the nginx artifact cache.
HOST="${PANTERA_HOST:-http://localhost:8088}"
# Clients need port 8081 for correct tarball URLs (in client_base_host_allowlist)
CLIENT_HOST="${PANTERA_CLIENT_HOST:-http://localhost:8081}"
CREDS="${PANTERA_CREDS:-ayd:ayd}"
LOCAL="$HOST/test_prefix/api/npm"
PROXY="$HOST/npm_proxy"
GROUP="$HOST/npm_group"
CLIENT_GROUP="$CLIENT_HOST/npm_group"
PKG='@ayd/npm-proxy-test'
PKG_ENC='@ayd%2fnpm-proxy-test'
UPSTREAM_PKG='lodash'

FAILURES=0
SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Encode credentials as base64 for .npmrc
base64_creds() {
  printf '%s' "$1" | base64
}

# expect_status <label> <method> <url> <expected-code> [json-body]
expect_status() {
  local label=$1 method=$2 url=$3 want=$4 body=${5:-} got
  if [ -n "$body" ]; then
    got=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
          -u "$CREDS" -X "$method" -H 'Content-Type: application/json' \
          -d "$body" "$url" 2>/dev/null || echo "000")
  else
    got=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
          -u "$CREDS" -X "$method" "$url" 2>/dev/null || echo "000")
  fi
  if [ "$got" = "$want" ]; then pass "$label ($got)"; else
    fail "$label: expected $want, got $got"
  fi
}

# expect_declined <label> <url> — 404, a reason header, and never a 5xx
expect_declined() {
  local label=$1 url=$2 hdrs code reason
  hdrs=$(curl -s -D - -o /dev/null --max-time 30 -u "$CREDS" "$url" 2>/dev/null || true)
  code=$(printf '%s' "$hdrs" | awk 'NR==1{print $2}')
  reason=$(printf '%s' "$hdrs" | tr -d '\r' \
           | awk -F': ' 'tolower($1)=="x-pantera-reason"{print $2}')
  if [ "${code:-000}" -ge 500 ] 2>/dev/null; then
    fail "$label: status $code is >= 500, npm clients will retry it for ~70s"
  elif [ "$code" != "404" ]; then
    fail "$label: expected 404, got ${code:-none}"
  elif [ "$reason" != "not_implemented" ]; then
    fail "$label: missing X-Pantera-Reason: not_implemented (got '${reason:-none}')"
  else
    pass "$label (404 + reason)"
  fi
}

section_endpoints() {
  echo "--- 1. Endpoint matrix ---"
  for base in "$LOCAL" "$PROXY" "$GROUP"; do
    expect_status "registry root      $base" GET "$base/" 200
    expect_status "ping               $base" GET "$base/-/ping" 200
    expect_status "search             $base" GET "$base/-/v1/search?text=lodash" 200
    expect_status "keys               $base" GET "$base/-/npm/v1/keys" 200
    expect_status "audit bulk         $base" POST \
      "$base/-/npm/v1/security/advisories/bulk" 200 '{"lodash":["4.17.21"]}'
  done

  # Package resolution: each mode against a package it owns.
  expect_status "local packument"  GET "$LOCAL/$PKG" 200
  expect_status "proxy packument"  GET "$PROXY/$UPSTREAM_PKG" 200
  expect_status "group packument"  GET "$GROUP/$UPSTREAM_PKG" 200
  expect_status "group local pkg"  GET "$GROUP/$PKG" 200
  expect_status "local dist-tags"  GET "$LOCAL/-/package/$PKG_ENC/dist-tags" 200

  # Abbreviated (corgi) packument must be honoured in every mode.
  for base in "$LOCAL/$PKG" "$PROXY/$UPSTREAM_PKG" "$GROUP/$UPSTREAM_PKG"; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 -u "$CREDS" \
           -H 'Accept: application/vnd.npm.install-v1+json' "$base" 2>/dev/null)
    if [ "$code" = "200" ]; then pass "corgi packument $base"; else
      fail "corgi packument $base: expected 200, got $code"
    fi
  done

  # Identity is user-scoped: same answer in every mode.
  for base in "$LOCAL" "$PROXY" "$GROUP"; do
    expect_status "whoami             $base" GET "$base/-/whoami" 200
    expect_status "profile get        $base" GET "$base/-/npm/v1/user" 200
  done

  # Declined endpoints: fast, explicit, never 5xx.
  for base in "$LOCAL" "$PROXY" "$GROUP"; do
    expect_declined "tokens  $base" "$base/-/npm/v1/tokens"
    expect_declined "hooks   $base" "$base/-/npm/v1/hooks"
    expect_declined "team    $base" "$base/-/team/x/y/user"
  done
}

section_rev() {
  echo "--- 2. Packument revision lifecycle ---"
  local rev
  rev=$(curl -s --max-time 30 -u "$CREDS" "$LOCAL/$PKG" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("_rev",""))')
  if [ -z "$rev" ]; then
    fail "packument carries no _rev; unpublish cannot be validated"
    return
  fi
  pass "packument carries _rev ($rev)"
  expect_status "unpublish with literal 'undefined' is refused" DELETE \
    "$LOCAL/$PKG/-rev/undefined" 428
  expect_status "unpublish with a stale revision is refused" DELETE \
    "$LOCAL/$PKG/-rev/9-0000000000000000000000000000000" 409
  expect_status "package survived both refusals" GET "$LOCAL/$PKG" 200
}

section_clients() {
  echo "--- 3. Clients: npm / yarn 1.x / pnpm ---"
  local reg="$CLIENT_GROUP/"
  local creds_b64=$(base64_creds "$CREDS")
  for client in npm yarn pnpm; do
    command -v "$client" >/dev/null 2>&1 || { fail "$client not installed"; continue; }
    local dir="$SCRATCH/$client"
    mkdir -p "$dir"
    printf '{"name":"h-%s","version":"1.0.0","dependencies":{"is-positive":"3.1.0"}}\n' \
      "$client" > "$dir/package.json"

    # npm and yarn both require credentials scoped to the full registry
    # path, not just the host. npm's fetch layer does walk the request
    # path upward looking for credentials, but its config layer
    # (getCredentialsByURI), which actually builds the request's auth,
    # does an exact match against the registry URI with no walk-up, so
    # host-root-scoped credentials are silently never applied. yarn 1.x
    # has the same full-path requirement, plus always-auth=true (npm
    # does not need always-auth; it is deprecated/removed in npm 7+).
    # pnpm gets the same full-path scoping here for consistency: a
    # host-root pass in this harness could just as easily mean a warm
    # local store as a correct config.
    # Each client gets its own .npmrc to avoid cross-client compatibility issues.
    if [ "$client" = "yarn" ]; then
      {
        printf 'registry=%s\n' "$reg"
        printf 'cache-folder=%s/.yarn-cache\n' "$dir"
        printf '//localhost:8081/npm_group/:_auth=%s\n' "$creds_b64"
        printf 'always-auth=true\n'
      } > "$dir/.npmrc"
    elif [ "$client" = "pnpm" ]; then
      {
        printf 'registry=%s\n' "$reg"
        printf 'cache=%s/.cache\n' "$dir"
        printf '//localhost:8081/npm_group/:_auth=%s\n' "$creds_b64"
        printf 'store-dir=%s/.pnpm-store\n' "$dir"
      } > "$dir/.npmrc"
    else
      # npm
      {
        printf 'registry=%s\n' "$reg"
        printf 'cache=%s/.cache\n' "$dir"
        printf '//localhost:8081/npm_group/:_auth=%s\n' "$creds_b64"
      } > "$dir/.npmrc"
    fi

    if ( cd "$dir" && "$client" install --silent >/dev/null 2>&1 ); then
      pass "$client install"
    else
      fail "$client install"
      continue
    fi
    # The failure mode a plain exit code cannot see: a lockfile whose
    # resolved URL points upstream, silently bypassing the registry.
    if [ -f "$dir/package-lock.json" ] \
       && grep -q '"resolved": "http' "$dir/package-lock.json" \
       && ! grep -q '"resolved": "'"$CLIENT_HOST" "$dir/package-lock.json"; then
      fail "$client resolved a tarball from a host other than Pantera"
    else
      pass "$client resolved through Pantera"
    fi
  done
  expect_status "npm search via group" GET "$GROUP/-/v1/search?text=is-positive" 200
}

section_corepack() {
  echo "--- 4. corepack ---"
  command -v corepack >/dev/null 2>&1 || { fail "corepack not installed"; return; }
  local dir="$SCRATCH/corepack"
  mkdir -p "$dir"
  # Integrity verification stays ENABLED. corepack checks package-manager
  # tarball signatures against npm's bundled keys; signatures cover
  # <package>@<version>:<integrity>, so Pantera's dist.tarball rewrite must
  # leave dist.signatures and dist.integrity untouched. Setting
  # COREPACK_INTEGRITY_KEYS=0 here would delete the only assertion that matters.
  #
  # corepack does not read .npmrc credentials at all -- unlike the npm/yarn/
  # pnpm blocks above, it authenticates only via its own COREPACK_NPM_TOKEN or
  # COREPACK_NPM_USERNAME/COREPACK_NPM_PASSWORD env vars. Do not "simplify"
  # this to an .npmrc _auth entry to match those blocks: it silently no-ops.
  # COREPACK_HOME is pointed at a fresh scratch dir so this stays a real,
  # cold fetch through Pantera rather than a hit on corepack's shared cache.
  local npmrc="$dir/.npmrc"
  printf 'registry=%s\n' "$CLIENT_GROUP/" > "$npmrc"
  local corepack_username="${CREDS%%:*}"
  local corepack_password="${CREDS#*:}"
  if ( cd "$dir" && COREPACK_NPM_REGISTRY="$CLIENT_GROUP/" \
        COREPACK_NPM_USERNAME="$corepack_username" \
        COREPACK_NPM_PASSWORD="$corepack_password" \
        COREPACK_HOME="$dir/.corepack-home" \
        corepack prepare pnpm@11.17.0 --activate >/dev/null 2>&1 ); then
    pass "corepack prepare through Pantera with integrity verification enabled"
  else
    fail "corepack prepare failed — check dist.signatures survived the packument rewrite"
  fi
}

RUN_ALL=1
case "${1:-}" in
  --endpoints) RUN_ALL=0; section_endpoints ;;
  --clients)   RUN_ALL=0; section_clients ;;
  --corepack)  RUN_ALL=0; section_corepack ;;
  --rev)       RUN_ALL=0; section_rev ;;
esac
if [ "$RUN_ALL" = "1" ]; then
  section_endpoints; section_rev; section_clients; section_corepack
fi

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "=== all checks passed ==="
else
  echo "=== $FAILURES check(s) failed ==="
  exit 1
fi
