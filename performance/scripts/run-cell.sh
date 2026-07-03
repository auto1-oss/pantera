#!/usr/bin/env bash
# run-cell.sh — run one benchmark cell end-to-end.
#
# Usage: run-cell.sh <config> <variant>
#   config   — C1 | C2 | C3 | C4
#   variant  — V0 | V1 | V2   (V0=warm+cooldown-on, V1=cold, V2=cooldown-off)
#
# Output:
#   results/<config>-<variant>.json     (k6 summary)
#   results/<config>-<variant>.ndjson   (k6 per-request metrics)
#   results/<config>-<variant>.stats.csv (docker stats samples)
set -euo pipefail

CONFIG="${1:?usage: run-cell.sh <config> <variant>}"
VARIANT="${2:?usage: run-cell.sh <config> <variant>}"

DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"

ENV_FILE=".env.${CONFIG}"
[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE"; exit 1; }

CELL="${CONFIG}-${VARIANT}"
RESULTS="results/${CELL}"
STATS="results/${CELL}.stats.csv"

# variant overrides
WARMUP_FLAG="true"
case "$VARIANT" in
  V0) ;;
  V1) WARMUP_FLAG="false" ;;                                 # cold
  V2) sed -i.bak 's/^SUT_COOLDOWN=.*/SUT_COOLDOWN=false/' "$ENV_FILE" ;;
  *)  echo "unknown variant $VARIANT"; exit 1 ;;
esac

# Bodies + rendered config + throwaway JWT keys + seeded local-repo files.
# setup-harness.sh reads SUT_DB_POOL_* from the sourced cell env file and is
# idempotent; the rendered pantera-config.yml stays gitignored.
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
./scripts/setup-harness.sh
echo "=== $CELL :: harness setup complete (env: $ENV_FILE) ==="

echo "=== $CELL :: bringing stack up ==="
docker compose --env-file "$ENV_FILE" -f docker-compose-scaling.yml up -d
# wait for SUT health
for i in {1..120}; do
  if docker compose -f docker-compose-scaling.yml ps pantera-sut | grep -q '(healthy)'; then
    echo "SUT healthy after ${i}s"
    break
  fi
  sleep 1
  [ "$i" = "120" ] && { echo "SUT never became healthy"; docker compose logs pantera-sut | tail -80; exit 2; }
done

# Bootstrap auth. On a fresh DB, Pantera auto-creates user 'admin' with password
# 'admin' and the must_change_password flag set — which blocks API calls until
# rotated. We clear the flag directly in the DB (fast; rotation API also works).
# Pantera's API port (8086 → 8089 on host) requires JWT bearer tokens; basic auth
# only works on the repo port (8080 → 8088). So we log in via POST /auth/token to
# exchange admin:admin for a JWT, then use the JWT to create the bench user.
# The bench user's credentials are then used (basic auth) for all k6 repo traffic.
echo "=== $CELL :: bootstrap auth ==="
docker compose -f docker-compose-scaling.yml exec -T postgres \
  psql -U pantera -d pantera -c \
  "UPDATE users SET must_change_password = FALSE WHERE username = 'admin';" \
  >/dev/null
sleep 2  # let Pantera's user cache pick up the update (TTL-bounded)

API_BASE="http://localhost:8089"

# Step 1: log admin in via POST /api/v1/auth/token to receive a JWT.
ADMIN_TOKEN=$(curl -s -X POST "${API_BASE}/api/v1/auth/token" \
  -H "Content-Type: application/json" \
  -d '{"name":"admin","pass":"admin"}' \
  | python3 -c 'import sys, json; print(json.load(sys.stdin).get("token", ""))')
if [ -z "$ADMIN_TOKEN" ]; then
  echo "FAILED to obtain admin JWT — admin/admin login rejected"
  docker compose -f docker-compose-scaling.yml logs pantera-sut --tail 30
  exit 3
fi
echo "admin JWT obtained (${#ADMIN_TOKEN} chars)"

# Step 2: create the bench user with the pre-seeded 'admin' role.
HTTP_CODE=$(curl -s -o /tmp/bench-create.json -w "%{http_code}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -X PUT "${API_BASE}/api/v1/users/bench" \
  -H "Content-Type: application/json" \
  -d '{"type":"plain","pass":"benchpass","enabled":true,"roles":["admin"]}')
if [ "$HTTP_CODE" != "201" ] && [ "$HTTP_CODE" != "200" ]; then
  echo "FAILED to create bench user (HTTP $HTTP_CODE):"
  cat /tmp/bench-create.json
  docker compose -f docker-compose-scaling.yml logs pantera-sut --tail 30
  exit 3
fi
echo "bench user created (HTTP $HTTP_CODE)"

# Step 3: verify bench credentials authenticate by requesting a token with them.
BENCH_TOKEN=$(curl -s -X POST "${API_BASE}/api/v1/auth/token" \
  -H "Content-Type: application/json" \
  -d '{"name":"bench","pass":"benchpass"}' \
  | python3 -c 'import sys, json; print(json.load(sys.stdin).get("token", ""))')
if [ -z "$BENCH_TOKEN" ]; then
  echo "bench user auth smoke: POST /api/v1/auth/token did not return a token"
  exit 4
fi
echo "bench user auth verified (JWT issued)"

# V1 — flush caches after startup
if [ "$VARIANT" = "V1" ]; then
  echo "=== $CELL :: flushing caches for cold start ==="
  docker compose -f docker-compose-scaling.yml exec -T valkey valkey-cli FLUSHALL
  # L1 caches are in-process — SUT restart clears them
  docker compose -f docker-compose-scaling.yml restart pantera-sut
  for i in {1..60}; do
    docker compose -f docker-compose-scaling.yml ps pantera-sut | grep -q '(healthy)' && break
    sleep 1
  done
fi

# kick stats collector
./scripts/stats-collector.sh "$STATS" &
STATS_PID=$!

# run k6 (host-native)
echo "=== $CELL :: running k6 ==="
K6_WARMUP="true"
[ "$VARIANT" = "V1" ] && K6_WARMUP="false"

k6 run \
  --env BASE_URL=http://localhost:8088 \
  --env CELL_LABEL="$CELL" \
  --env WARMUP="$K6_WARMUP" \
  --summary-export "${RESULTS}.json" \
  --out json="${RESULTS}.ndjson" \
  k6/scenario.js

# stop stats
kill $STATS_PID 2>/dev/null || true
wait $STATS_PID 2>/dev/null || true

echo "=== $CELL :: tearing down ==="
docker compose -f docker-compose-scaling.yml down -v

# restore .env if mutated
[ -f "${ENV_FILE}.bak" ] && mv "${ENV_FILE}.bak" "$ENV_FILE"

echo "=== $CELL :: done ==="
ls -lh "${RESULTS}".*
