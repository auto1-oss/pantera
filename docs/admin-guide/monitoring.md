# Monitoring

> **Guide:** Admin Guide | **Section:** Monitoring

Pantera exposes Prometheus-compatible metrics, lightweight health checks on both ports, and integrates with Grafana for dashboards. This page covers metrics configuration, key metrics to monitor, health check endpoints, and alerting recommendations.

---

## Prometheus Configuration

### Enable Metrics in pantera.yml

```yaml
meta:
  metrics:
    endpoint: /metrics/vertx
    port: 8087
    types:
      - jvm       # Heap usage, GC, threads, classloader
      - storage    # Storage operation counts and latency
      - http       # HTTP request/response metrics
```

| Key | Type | Required | Description |
|-----|------|----------|-------------|
| `endpoint` | string | Yes | URL path for metrics scraping (must start with `/`) |
| `port` | int | Yes | Dedicated metrics port |
| `types` | list | No | Metric categories to enable: `jvm`, `storage`, `http` |

### Prometheus Scrape Configuration

Add the following to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'pantera'
    metrics_path: '/metrics/vertx'
    scrape_interval: 15s
    static_configs:
      - targets: ['pantera:8087']
```

For HA deployments with multiple nodes:

```yaml
scrape_configs:
  - job_name: 'pantera'
    metrics_path: '/metrics/vertx'
    scrape_interval: 15s
    static_configs:
      - targets:
          - 'pantera-1:8087'
          - 'pantera-2:8087'
          - 'pantera-3:8087'
```

---

## Key Metrics

### Thread Pool Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `pantera.pool.read.active` | Gauge | Active threads in READ pool |
| `pantera.pool.write.active` | Gauge | Active threads in WRITE pool |
| `pantera.pool.list.active` | Gauge | Active threads in LIST pool |
| `pantera.pool.read.queue` | Gauge | Queue depth of READ pool |
| `pantera.pool.write.queue` | Gauge | Queue depth of WRITE pool |
| `pantera.pool.list.queue` | Gauge | Queue depth of LIST pool |

### JVM Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `jvm_memory_used_bytes` | Gauge | JVM memory usage by area (heap, non-heap) |
| `jvm_memory_max_bytes` | Gauge | Maximum memory available |
| `jvm_gc_pause_seconds` | Summary | GC pause durations |
| `jvm_threads_live_threads` | Gauge | Live thread count |
| `jvm_threads_peak_threads` | Gauge | Peak thread count |

### HTTP Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `http_server_requests_seconds` | Timer | HTTP request latency distribution |
| `vertx_http_server_active_connections` | Gauge | Current active HTTP connections |

### Database Metrics (HikariCP)

| Metric | Type | Description |
|--------|------|-------------|
| `hikaricp_connections_active` | Gauge | Active database connections |
| `hikaricp_connections_idle` | Gauge | Idle database connections |
| `hikaricp_connections_pending` | Gauge | Threads waiting for a connection |
| `hikaricp_connections_max` | Gauge | Maximum pool size |

### Resilience Metrics (v2.2.0+)

Pantera 2.2.0 introduced a per-host upstream circuit breaker and a per-repo concurrency bulkhead. Both surfaces are observable as Prometheus metrics — log scraping is **not** the supported diagnostic path.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pantera_circuit_breaker_state` | Gauge | `upstream_host` | 1 = open (fast-fail mode), 0 = closed (normal). Polled from the breaker's wall-clock `isOpen()`. |
| `pantera_circuit_breaker_trips_total` | Counter | `upstream_host` | Incremented on every closed → open transition. |
| `pantera_circuit_breaker_fastfail_total` | Counter | `upstream_host` | Incremented on every synthesised 502 returned while the breaker is open. |
| `pantera_bulkhead_overflow_total` | Counter | `repo_name` | Incremented on every 503 returned by the per-repo semaphore. |
| `pantera_proxy_429_total` | Counter | `upstream_host` | Upstream 429 responses (already in the repo since 2.1.x). |
| `proxy_phase_duration_seconds` | Histogram | `phase`, `repo` | Latency per logical proxy phase (negative-cache, preProcess, cacheFirst, cooldown, fetchDirect, store). |

**Alerting hooks:** The v2.2.0 release ships Prometheus alert rules
(`pantera-main/src/main/resources/prometheus/alert-rules.yml` — importable
into any Prometheus instance, not auto-loaded by the local docker-compose
stack) and four corresponding runbooks under `docs/runbooks/` —
`upstream-circuit-breaker-open.md`, `bulkhead-overflow.md`,
`upstream-429-sustained.md`, `low-conditional-get-hit-rate.md`. See
[Runbooks](runbooks.md).

---

### Storage Metrics (v2.3.0+)

Pantera 2.3.0 instruments the blob-store tier (S3 and compatible) and its
on-disk cache — previously `RepoConfig` deliberately skipped storage
metrics, so slow-at-scale storage was undiagnosable. All of the below are
guarded by `MicrometerMetrics.isInitialized()` and use bounded tags only.

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `pantera_storage_blobstore_requests_total` | Counter | `backend`, `operation`, `outcome` | One per `BlobStore`-tier call (`exists`/`head`/`get`/`put`/`delete`/`list`). Zero on a disk-cache hit by design — only calls that actually reach the object store are counted. `backend` ∈ `s3`/`gcs`/`azure`/`other`; `outcome` ∈ `success`/`throttled`/`error`. |
| `pantera_storage_blobstore_request_duration_seconds` | Timer (transfer SLO ladder) | `backend`, `operation`, `outcome` | Latency of the same calls. GET/PUT stream full artifact bodies, so this is on the transfer ladder, not control-plane. |
| `pantera_storage_cache_disk_bytes_used` / `..._bytes_max` | Gauge | `cache` | Current vs configured (`cache.max-disk-bytes`) size of one `cache.mode: index` disk-cache instance. `cache` is the cache's disk-root path, not a repo name. |
| `pantera_storage_cache_writeback_queue_depth` / `..._queue_capacity` | Gauge | `cache` | Outstanding durable write-back admissions vs the configured high-water mark (`cache.write-back-queue-capacity`). Depth reaching capacity triggers admission-first backpressure (client-visible 503). |
| `pantera_storage_cache_writeback_oldest_pending_age_seconds` | Gauge | `cache` | Age of the longest-outstanding pending upload; 0 if none pending. High age with unchanged depth indicates stuck uploads, not just volume. |
| `pantera_cache_eviction_bytes_total` | Counter | `cache_type`, `cache_tier` | Bytes freed by cache eviction; the disk tier reports under `cache_type="blob_disk"`. Chart `rate()` for an eviction-bytes/sec panel. |
| `pantera_storage_invalidation_total` | Counter | `stage`, `outcome` | Cross-node cache-invalidation lifecycle over the Valkey pub/sub bus: `stage` ∈ `published`/`received`/`applied`. |
| `pantera_storage_download_decision_total` | Counter | `repo_name`, `decision` | Presigned-direct-download serving decision per redirect-eligible byte GET (Docker blob GET is the first wired route). `decision="redirect"` is a `302` to a presigned URL (zero blob-store round trip); `decision="stream"` is served through Pantera. The redirect rate IS the presign issuance rate; redirect vs stream share is the redirect ratio. |
| `pantera_go_sumdb_cache_total` | Counter | `repo_name`, `kind`, `result` | Go module proxy sumdb lookup/tile immutable-cache hit/miss. `kind` ∈ `lookup`/`tile`; `result` ∈ `hit`/`miss`. |

The existing `pantera_cache_requests_total{cache_type,cache_tier,result}` and
`pantera_cache_evictions_total{cache_type,cache_tier,reason}` counters (not
new in 2.3.0) also carry the disk tier's hit/miss and eviction-count data
under `cache_type="blob_disk"`.

**Dashboard:** all of the above are charted on the **Cache & Storage**
Grafana dashboard (`pantera-cache-storage.json`) — see
[Grafana Dashboards](../observability/dashboards.md).

**Alerting hook:** `PanteraWriteBackQueueNearCapacity` fires when a cache's
write-back queue depth sustains ≥ 80% of capacity for 5 minutes — see
[Write-Back Queue Near Capacity](../runbooks/write-back-queue-near-capacity.md).

**Known gap:** index entry count and boot-rebuild duration
(`StorageIndex.rebuildFromDisk`) are currently logged only (a
non-ECS custom log field, `pantera.storage.index.entries`) — no Micrometer
meter exists yet, so there is no panel for either. Likewise, the WS2 HA
surface (revocation-set size, event-queue depth/drain lag, per-dependency
readiness state, settings-propagation lag) has state-transition logs but no
metrics. Both are tracked as follow-up work, not silently dropped.

---

## Grafana Dashboards

The Docker Compose stack includes pre-configured Grafana with dashboards for:

- JVM memory and GC metrics
- HTTP request rates and latency
- Storage operation throughput
- Thread pool utilization
- Database connection pool status

Two dashboards new in v2.2.0 ship under `pantera-main/src/main/resources/grafana/` (importable into any Grafana 10+ instance):

- **Upstream Circuit Breaker** (`upstream-circuit-breaker.json`) — per-host breaker state, trip frequency, fast-fail rate, time-since-last-trip. Open this first when the `upstream-circuit-breaker-open` alert fires.
- **Proxy Phase Latency** (`proxy-phase-latency.json`) — stacked p99 of `proxy_phase_duration_seconds` per `(phase, repo)`. The dominant phase identifies the bottleneck — read this before launching any further performance work.

See [Grafana Dashboards](../observability/dashboards.md) for import instructions and metric coverage detail.

Access Grafana at `http://pantera-host:3000` (default credentials from `.env`).

### Custom Dashboards

Import the Pantera dashboard JSON or create custom panels using the metrics above. Recommended panels:

| Panel | Metrics | Visualization |
|-------|---------|---------------|
| Request Rate | `rate(http_server_requests_seconds_count[5m])` | Time series |
| Request Latency (p99) | `histogram_quantile(0.99, http_server_requests_seconds_bucket)` | Time series |
| Pool Queue Depth | `pantera.pool.{read,write,list}.queue` | Time series |
| Heap Usage | `jvm_memory_used_bytes{area="heap"}` | Gauge |
| DB Pool Utilization | `hikaricp_connections_active / hikaricp_connections_max` | Gauge |
| GC Pause Time | `rate(jvm_gc_pause_seconds_sum[5m])` | Time series |

---

## Health Checks

Pantera provides lightweight health endpoints on both the repository port and the API port. Both are suitable for load balancer and orchestrator health probes.

### Repository Port Health Check

```bash
curl http://pantera-host:8080/.health
# {"status":"ok"}
```

- **Port:** 8080
- **Authentication:** None
- **Behavior:** Returns HTTP 200 immediately. No I/O, no probes, no blocking. Returns OK as long as the JVM is running and the Vert.x event loop is responsive.

### API Port Health Check

```bash
curl http://pantera-host:8086/api/v1/health
# {"status":"ok"}
```

- **Port:** 8086
- **Authentication:** None
- **Behavior:** Returns HTTP 200. Same lightweight check.

### Version Endpoint

```bash
curl http://pantera-host:8080/.version
# [{"version":"2.0.0"}]
```

### Health Check Usage

| Environment | Endpoint | Interval |
|-------------|----------|----------|
| Docker Compose | `GET /.health` on port 8080 | 10s |
| Kubernetes liveness | `GET /.health` on port 8080 | 15s |
| Kubernetes readiness | `GET /api/v1/health` on port 8086 | 10s |
| Load balancer (NLB/ALB) | `GET /.health` on port 8080 | 10s |

---

## Alerting Recommendations

The following alert rules are recommended for production Pantera deployments. Adapt thresholds to your workload.

### Critical Alerts

| Alert | Condition | Description |
|-------|-----------|-------------|
| Instance Down | `up{job="pantera"} == 0` for 2m | Pantera instance is not responding to Prometheus scrapes |
| High Heap Usage | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9` for 5m | Heap is above 90% -- risk of OOM |
| DB Pool Exhaustion | `hikaricp_connections_pending > 10` for 2m | Database connection pool is saturated |
| Health Check Failing | Probe to `/.health` returns non-200 for 30s | Instance is unresponsive |

### Warning Alerts

| Alert | Condition | Description |
|-------|-----------|-------------|
| Read Pool Saturated | `pantera.pool.read.queue > 100` for 5m | Read thread pool is backlogged; increase `PANTERA_IO_READ_THREADS` |
| Write Pool Saturated | `pantera.pool.write.queue > 50` for 5m | Write thread pool is backlogged; increase `PANTERA_IO_WRITE_THREADS` |
| High GC Pause | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.1` | GC is consuming more than 10% of time |
| Elevated Error Rate | `rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 1` | More than 1 server error per second |
| High Request Latency | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 10` | p99 latency exceeds 10 seconds |

### Informational Alerts

| Alert | Condition | Description |
|-------|-----------|-------------|
| Cooldown Blocks High | Cooldown blocked count > 100 (via API polling) | Many artifacts are being held by cooldown |
| Disk Cache Full | Disk cache usage approaching `max-bytes` | Consider increasing cache size |

---

## Metrics Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_METRICS_MAX_REPOS` | `50` | Maximum distinct repository names in metrics labels |
| `PANTERA_METRICS_PERCENTILES_HISTOGRAM` | `false` | Publish SLO-bucket histograms for latency timers - required for every p95/p99 dashboard panel (they query `_bucket` series and show "No data" without it) |

See [Environment Variables](environment-variables.md) for the complete list.

---

## Related Pages

- [Configuration](configuration.md) -- meta.metrics section
- [Logging](logging.md) -- Structured logging for operational visibility
- [Performance Tuning](performance-tuning.md) -- Thread pool sizing based on metrics
- [High Availability](high-availability.md) -- Multi-node monitoring
- [Troubleshooting](troubleshooting.md) -- Using metrics to diagnose issues
