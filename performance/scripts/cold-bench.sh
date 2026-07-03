#!/usr/bin/env bash
#
# Phase 7 acceptance bench — cold pom-heavy walk under three runtime configs.
#
# Same pantera build for all rows (current 2.2.0 HEAD); only the runtime
# settings (http_client.protocol, prefetch.enabled) differ per row. Wall
# time is captured from `mvn dependency:resolve` against the heavy POM.
#
# Pre-flight (verified manually before invocation):
#   - pantera + nginx + pantera-db running (docker compose up -d)
#   - /tmp/settings-pantera.xml mirror points to localhost:8081/maven_group
#   - remotes.yaml has groovy REMOVED (transparent Maven Central mirror
#     would bias the bench)
#
# Settings are toggled via direct DB UPDATE because the PATCH endpoint
# requires an admin Bearer token (basic auth returns 401). LISTEN/NOTIFY
# propagates the change to the in-process RuntimeSettingsCache within
# a few ms; we sleep 2s after each toggle to be safe.
#
set -euo pipefail

PANTERA_PROXY="${PANTERA_PROXY:-http://localhost:8081}"
PANTERA_METRICS="${PANTERA_METRICS:-http://localhost:8087/metrics/vertx}"
SETTINGS="${SETTINGS:-/tmp/settings-pantera.xml}"
POM_DIR="${POM_DIR:-pantera-main/docker-compose/pantera/artifacts/maven}"
LOCAL_REPO="${LOCAL_REPO:-/tmp/m2-perftest-pantera}"
OUT="${OUT:-performance/results/cold-bench-v2.2.0.md}"
DB_CONTAINER="${DB_CONTAINER:-pantera-db}"

# JSON-literal value forms used by the settings table: {"value": <typed>}
PROTO_H1='"h1"'
PROTO_H2='"h2"'
BOOL_TRUE='true'
BOOL_FALSE='false'

mkdir -p "$(dirname "$OUT")"

setup_run () {
    local name="$1"
    echo ""
    echo ">>> $name <<<"
    rm -rf "$LOCAL_REPO" 2>/dev/null || true
    rm -rf pantera-main/docker-compose/pantera/data/maven_proxy 2>/dev/null || true
    rm -rf pantera-main/docker-compose/pantera/data/groovy 2>/dev/null || true
    docker exec "$DB_CONTAINER" psql -U pantera -d pantera -c "TRUNCATE artifacts;" >/dev/null
}

restart_pantera () {
    # Slice cache holds lease references after settings flip (the
    # documented CONCERN-task9-slice-cache-lag audit item). For a clean
    # acceptance bench we restart pantera so the new HttpTuning takes
    # effect from the very first request.
    docker restart pantera >/dev/null
    # Probe metrics endpoint (8087) which is bound by AsyncMetricVerticle
    # at the end of boot — once it answers, the request path is up.
    local i=0
    while [ "$i" -lt 60 ]; do
        if curl -fsS -o /dev/null --max-time 2 \
            "http://localhost:8087/metrics/vertx" 2>/dev/null; then
            sleep 2  # event-loop verticles fully wired
            return 0
        fi
        sleep 1
        i=$((i + 1))
    done
    echo "ERROR: pantera metrics did not respond within 60s" >&2
    return 1
}

set_runtime_kv () {
    # set_runtime_kv <key> <json-literal-value>
    local key="$1"
    local val="$2"
    docker exec "$DB_CONTAINER" psql -U pantera -d pantera -c \
        "INSERT INTO settings (key, value, updated_by) VALUES ('$key', '{\"value\": $val}'::jsonb, 'cold-bench') \
         ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_by=EXCLUDED.updated_by, updated_at=NOW();" \
        >/dev/null
}

read_metric_sum () {
    # Sum across all label combinations for a given metric base name.
    local metric="$1"
    curl -sS "$PANTERA_METRICS" 2>/dev/null \
        | awk -v m="^${metric}([{ ]|$)" '
            $0 !~ /^#/ && $0 ~ m {
                # last whitespace-separated field is the value
                v = $NF + 0
                sum += v
            }
            END { printf "%.0f", sum }
          '
}

read_metric_lines () {
    # Print every label-set line for a given metric base name. Useful for
    # capturing per-label distributions (h2 vs http/1.1) and per-outcome
    # counts (fetched_200 vs neg_cached_404 vs dropped).
    local metric="$1"
    curl -sS "$PANTERA_METRICS" 2>/dev/null \
        | awk -v m="^${metric}([{ ]|$)" '
            $0 !~ /^#/ && $0 ~ m { print "    " $0 }
          '
}

run_cell () {
    local name="$1"; local proto="$2"; local prefetch="$3"
    setup_run "$name"
    set_runtime_kv "http_client.protocol" "$proto"
    set_runtime_kv "prefetch.enabled"     "$prefetch"
    # Restart pantera so the slice-cache picks up the new HttpTuning on
    # the very first acquire. (LISTEN/NOTIFY-based hot-flip is documented
    # to lag for warm slices — see CONCERN-task9-slice-cache-lag.)
    restart_pantera

    # Restart resets cumulative counters to zero, so before-snapshots
    # would be 0 anyway, but read them defensively.
    local h2_before; h2_before=$(read_metric_sum 'pantera_http2_negotiated_total')
    local pf_before; pf_before=$(read_metric_sum 'pantera_prefetch_dispatched_total')
    [ -z "$h2_before" ] && h2_before=0
    [ -z "$pf_before" ] && pf_before=0

    local t0; t0=$(date +%s.%N)
    set +e
    ( cd "$POM_DIR" && mvn -B -q -s "$SETTINGS" -Dmaven.repo.local="$LOCAL_REPO" \
        -f pom.xml dependency:resolve >/tmp/cold-bench-mvn.log 2>&1 )
    local rc=$?
    set -e
    local t1; t1=$(date +%s.%N)
    local wall; wall=$(echo "$t1 - $t0" | bc)

    local h2_after; h2_after=$(read_metric_sum 'pantera_http2_negotiated_total')
    local pf_after; pf_after=$(read_metric_sum 'pantera_prefetch_dispatched_total')
    [ -z "$h2_after" ] && h2_after=0
    [ -z "$pf_after" ] && pf_after=0
    local h2_delta; h2_delta=$((h2_after - h2_before))
    local pf_delta; pf_delta=$((pf_after - pf_before))

    printf '| %s | %.2f s | %d | %d | rc=%d |\n' \
        "$name" "$wall" "$h2_delta" "$pf_delta" "$rc" | tee -a "$OUT"

    {
        echo
        echo "<details><summary>$name — metrics</summary>"
        echo
        echo '```'
        echo "# pantera_http2_negotiated_total (per upstream/version)"
        read_metric_lines 'pantera_http2_negotiated_total'
        echo
        echo "# pantera_prefetch_dispatched_total"
        read_metric_lines 'pantera_prefetch_dispatched_total'
        echo
        echo "# pantera_prefetch_completed_total (outcome breakdown)"
        read_metric_lines 'pantera_prefetch_completed_total'
        echo
        echo "# pantera_prefetch_dropped_total"
        read_metric_lines 'pantera_prefetch_dropped_total'
        echo '```'
        echo
        echo "</details>"
    } >> "$OUT"
}

reset_settings () {
    # Restore the spec defaults (h2, prefetch on).
    set_runtime_kv "http_client.protocol" "$PROTO_H2"
    set_runtime_kv "prefetch.enabled"     "$BOOL_TRUE"
}

{
    echo "# Cold pom-heavy bench — v2.2.0 perf-pack"
    echo
    echo "Hardware: $(uname -srm)"
    echo "Date: $(date -u +%FT%TZ)"
    echo "Pantera HEAD: $(git rev-parse --short HEAD)"
    echo "Settings file: $SETTINGS"
    echo "POM: $POM_DIR/pom.xml"
    echo "remotes.yaml groovy member: REMOVED for bench"
    echo
    echo '| Configuration | Wall time | upstream resp count | prefetch dispatched | mvn rc |'
    echo '|---|---|---|---|---|'
    echo
    echo '> "upstream resp count" sums `pantera_http2_negotiated_total` across all label values (h2 + http/1.1). Per-version breakdown is in the per-row metrics block.'
    echo
} > "$OUT"

run_cell "h1, no prefetch (baseline)"      "$PROTO_H1" "$BOOL_FALSE"
run_cell "h2, no prefetch"                 "$PROTO_H2" "$BOOL_FALSE"
run_cell "h2 + prefetch (shipped default)" "$PROTO_H2" "$BOOL_TRUE"

reset_settings

echo
echo "=== Final result ==="
cat "$OUT"
