# Grafana Dashboards

Pantera ships pre-built Grafana 10+ dashboards as JSON under
`pantera-main/src/main/resources/grafana/`. Each dashboard targets a Prometheus
datasource with UID `prometheus` — adjust the UID at import time if your
deployment uses a different name.

## Reference

| Dashboard                               | File                                                                      | Purpose                                                                              |
|-----------------------------------------|---------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| **Upstream Circuit Breaker** (T-O01)    | `grafana/upstream-circuit-breaker.json`                                   | Per-host breaker state, trip frequency, fast-fail rate, time-since-last-trip.        |
| **Proxy Phase Latency** (T-O04)         | `grafana/proxy-phase-latency.json`                                        | Stacked p99 of `proxy_phase_duration_seconds` per repo. Drives cold-bench debugging. |

## Importing

```bash
# Grafana 10+ — POST to /api/dashboards/db
curl -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $GRAFANA_TOKEN" \
  -d @pantera-main/src/main/resources/grafana/upstream-circuit-breaker.json \
  https://grafana.example.com/api/dashboards/db
```

The bundled `docker-compose/grafana/provisioning/dashboards/` directory carries
older dashboards already auto-provisioned in the local stack. New dashboards
under `src/main/resources/grafana/` ship inside the JAR and can be imported in
any Grafana instance without docker-compose.

## Metric coverage

The Upstream Circuit Breaker dashboard consumes three metrics produced by
`MicrometerMetrics` in `pantera-core`:

- `pantera_circuit_breaker_state{upstream_host}` — gauge, 1=open / 0=closed.
- `pantera_circuit_breaker_trips_total{upstream_host}` — counter, incremented
  on every closed → open transition.
- `pantera_circuit_breaker_fastfail_total{upstream_host}` — counter,
  incremented on every synthetic 502 returned by the fast-fail path.

The Proxy Phase Latency dashboard consumes `proxy_phase_duration_seconds`,
a histogram broken out by `(phase, repo)` recorded by
`BaseCachedProxySlice.recordProxyPhase(...)`.

## Operational use

Read the **Upstream Circuit Breaker** dashboard whenever the
`upstream-circuit-breaker-open` alert fires
(see `docs/runbooks/upstream-circuit-breaker-open.md`). The per-host state
table tells you immediately which upstream is broken; the trip-count panel
tells you how often the breaker has cycled in the last 24 h; the fast-fail
rate tells you how much client traffic is being absorbed without reaching the
broken upstream.

Read the **Proxy Phase Latency** dashboard before launching any further
performance work. The dominant phase identifies the bottleneck — work on
that one rather than guessing.

## Docker Compose provisioned dashboards

`pantera-main/docker-compose/grafana/provisioning/dashboards/` auto-provisions
into the local stack's Grafana on `docker compose up` (no import step
needed) — distinct from the two JAR-shipped dashboards above.

| Dashboard | File | Purpose |
|-----------|------|---------|
| Cache & Storage | `pantera-cache-storage.json` | Generic `Storage`-interface op rate/latency, cache hit/eviction/dedup/error rates, the WS1.6/WS1.7 blob-store tier (op rate/latency, disk-cache bytes used/max, write-back queue depth/capacity, oldest-pending age, eviction bytes/sec, cross-node invalidation), presigned direct-download redirect-vs-stream decision, and the Go module proxy sumdb cache. |
| Main Overview | `pantera-main-overview.json` | Fleet-wide landing dashboard. |
| Repository | `pantera-repository.json` | Per-repo download/upload rate and bandwidth. |
| Proxy | `pantera-proxy.json` | Proxy phase latency, bulkhead permits, cache-integrity failures. |
| Group | `pantera-group.json` | Group-repository member walk metrics. |
| Cooldown | `pantera-cooldown.json` | Cooldown filter duration, cache size, active blocks. |
| Upstream Circuit Breaker | `pantera-upstream-circuit-breaker.json` | Same coverage as the JAR-shipped dashboard above, pre-provisioned. |
| Infrastructure | `pantera-infrastructure.json` | JVM, GC, Vert.x, DB connection pool. |
| Vert.x Metrics | `pantera-vertx-metrics.json` | Vert.x event-loop and server metrics. |
| Proxy Phase Latency | `proxy-phase-latency.json` | Same coverage as the JAR-shipped dashboard above, pre-provisioned. |

### Cache & Storage — 2.3.0 metric coverage

The WS1.6/WS1.7 panels on this dashboard consume:

- `pantera_storage_blobstore_requests_total{backend,operation,outcome}` +
  `pantera_storage_blobstore_request_duration_seconds{backend,operation,outcome}`
  (transfer SLO ladder — `histogram_quantile` panels need
  `PANTERA_METRICS_PERCENTILES_HISTOGRAM=true`).
- `pantera_storage_cache_disk_bytes_used` / `_max`,
  `pantera_storage_cache_writeback_queue_depth` / `_capacity`,
  `pantera_storage_cache_writeback_oldest_pending_age_seconds` — all gauges
  tagged `cache` (the disk-cache's root path, not a repo name).
- `pantera_cache_eviction_bytes_total{cache_type,cache_tier}` and
  `pantera_storage_invalidation_total{stage,outcome}` — counters, charted as
  `rate()`.
- `pantera_storage_download_decision_total{repo_name,decision}` — the
  redirect-vs-stream ratio and presign issuance rate both derive from this
  one counter.
- `pantera_go_sumdb_cache_total{repo_name,kind,result}` — already
  provisioned prior to this addition.

See [Monitoring: Storage Metrics](../admin-guide/monitoring.md) for the full
per-metric description table and the known-gap notes (index entry
count/rebuild duration and the WS2 HA surface have no meter yet).
