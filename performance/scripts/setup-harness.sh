#!/usr/bin/env bash
#
# Copyright (c) 2025-2026 Auto1 Group
# Maintainers: Auto1 DevOps Team
# Lead Maintainer: Ayd Asraf
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License v3.0.
#
# setup-harness.sh — make the scaling/perf-gate stack bootable from a
# fresh checkout with zero local state. Everything here is idempotent.
#
#   1. Generate the WireMock response bodies (gitignored, ~12 MB).
#   2. Render pantera-config.yml from the template. Pantera's YAML loader
#      does NOT substitute ${VAR}, so the file must be rendered before the
#      compose mount. Values come from the environment when set (run-cell.sh
#      sources a cell env file first); otherwise the C1 defaults apply.
#   3. Generate a THROWAWAY RSA keypair for JWT signing when none exists.
#      The bench stack needs a valid RS256 keypair to boot; real keys are
#      never committed (secrets/*.pem is gitignored).
#   4. Seed the local-repo artifact files (hardlinks, idempotent, marker-gated).
#
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

echo "=== setup-harness: WireMock bodies ==="
[ -f wiremock/__files/body-100k.bin ] && echo "bodies present — skip" \
  || ./wiremock/__files/generate.sh

echo "=== setup-harness: render pantera-config.yml ==="
: "${SUT_DB_POOL_MAX:=40}"
: "${SUT_DB_POOL_MIN:=10}"
sed -e "s|\${SUT_DB_POOL_MAX}|${SUT_DB_POOL_MAX}|g" \
    -e "s|\${SUT_DB_POOL_MIN}|${SUT_DB_POOL_MIN}|g" \
    pantera-config.yml.template > pantera-config.yml
echo "rendered (pool_max=${SUT_DB_POOL_MAX}, pool_min=${SUT_DB_POOL_MIN})"

echo "=== setup-harness: JWT keypair ==="
mkdir -p secrets data results
if [ ! -f secrets/jwt-private.pem ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out secrets/jwt-private.pem 2>/dev/null
  openssl pkey -in secrets/jwt-private.pem -pubout \
    -out secrets/jwt-public.pem 2>/dev/null
  echo "generated throwaway RS256 keypair"
else
  echo "keypair present — skip"
fi

echo "=== setup-harness: seed local-repo files ==="
./scripts/seed-files.sh

echo "=== setup-harness: done ==="
