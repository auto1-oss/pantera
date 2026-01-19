# Artipie Metrics Reference

This document provides a comprehensive reference of all metrics emitted by Artipie.

## Metrics Overview

Artipie uses **Micrometer** with a **Prometheus** registry for metrics collection. All metrics are exposed at the `/metrics/vertx` endpoint in Prometheus format.

### Quick Stats

| Category | Metric Count | Description |
|----------|--------------|-------------|
| HTTP | 5 | Request rate, latency, size, active requests |
| Repository | 5 | Downloads, uploads, metadata operations |
| Cache | 6 | Hits, misses, evictions, size, latency |
| Storage | 2 | Operations count and duration |
| Proxy | 5 | Upstream requests, latency, errors, availability |
| Group | 4 | Group requests, member resolution, member latency |
| JVM | 14+ | Memory, threads, GC, CPU |
| Vert.x | 28 | HTTP server/client, worker pools, event bus |
| **Total** | **69+** | Comprehensive observability |

## Metric Categories

- [HTTP Request Metrics](#http-request-metrics)
- [Repository Operation Metrics](#repository-operation-metrics)
- [Cache Metrics](#cache-metrics)
- [Storage Metrics](#storage-metrics)
- [Proxy & Upstream Metrics](#proxy--upstream-metrics)
- [Group Repository Metrics](#group-repository-metrics)
- [JVM & System Metrics](#jvm--system-metrics)
- [Vert.x Metrics](#vertx-metrics)

---

## HTTP Request Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_http_requests_total` | Counter | `method`, `status_code`, `repo_name`*, `repo_type`* | Total HTTP requests (repo labels added when in repository context) | requests |
| `artipie_http_request_duration_seconds` | Timer/Histogram | `method`, `status_code`, `repo_name`*, `repo_type`* | HTTP request duration (repo labels added when in repository context) | seconds |
| `artipie_http_request_size_bytes` | Distribution Summary | `method` | HTTP request body size | bytes |
| `artipie_http_response_size_bytes` | Distribution Summary | `method`, `status_code` | HTTP response body size | bytes |
| `artipie_http_active_requests` | Gauge | - | Currently active HTTP requests | requests |

**Note:** Labels marked with `*` are optional and only present when the request is in a repository context.

---

## Repository Operation Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_repo_bytes_uploaded_total` | Counter | `repo_name`, `repo_type` | Total bytes uploaded to repository | bytes |
| `artipie_repo_bytes_downloaded_total` | Counter | `repo_name`, `repo_type` | Total bytes downloaded from repository | bytes |

**Repository Types:**
- `file` - Local file repository
- `npm` - Local npm repository
- `maven` - Local Maven repository
- `docker` - Local Docker repository
- `file-proxy` - Proxy repository (caches upstream)
- `npm-proxy` - npm proxy repository
- `maven-proxy` - Maven proxy repository
- `npm-group` - Group repository (aggregates multiple repositories)
- `maven-group` - Maven group repository

---

## Legacy Repository Operation Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_artifact_downloads_total` | Counter | `repo_name`, `repo_type` | Artifact download count | downloads |
| `artipie_artifact_uploads_total` | Counter | `repo_name`, `repo_type` | Artifact upload count | uploads |
| `artipie_artifact_size_bytes` | Distribution Summary | `repo_name`, `repo_type`, `operation` | Artifact size distribution (operation: download/upload) | bytes |
| `artipie_metadata_operations_total` | Counter | `repo_name`, `repo_type`, `operation` | Metadata operations count | operations |
| `artipie_metadata_generation_duration_seconds` | Timer/Histogram | `repo_name`, `repo_type` | Metadata generation duration | seconds |

---

## Cache Metrics

All caches in Artipie emit consistent metrics for monitoring hit rates, latency, and evictions.

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_cache_requests_total` | Counter | `cache_type`, `cache_tier`, `result` | Cache requests (result: hit/miss) | requests |
| `artipie_cache_evictions_total` | Counter | `cache_type`, `cache_tier`, `reason` | Cache evictions (reason: size, expired, explicit) | evictions |
| `artipie_cache_errors_total` | Counter | `cache_type`, `cache_tier`, `error_type` | Cache errors | errors |
| `artipie_cache_operation_duration_seconds` | Timer/Histogram | `cache_type`, `cache_tier`, `operation` | Cache operation latency (operation: get/put) | seconds |
| `artipie_cache_deduplications_total` | Counter | `cache_type`, `cache_tier` | Deduplicated cache requests (in-flight request tracking) | requests |

### Cache Types

All cache implementations emit metrics with consistent labels:

| Cache Type | Description | Location | Tiers |
|------------|-------------|----------|-------|
| `auth` | Authentication/authorization cache | `artipie-main/.../CachedUsers.java` | L1, L2 |
| `cooldown` | Rate limiting/cooldown decisions cache | `artipie-core/.../CooldownCache.java` | L1, L2 |
| `negative` | Negative responses (404) cache | `artipie-core/.../NegativeCache.java` | L1, L2 |
| `maven_negative` | Maven-specific 404 cache | `maven-adapter/.../NegativeCache.java` | L1, L2 |
| `storage` | Storage instances cache | `artipie-core/.../StoragesCache.java` | L1 |
| `filters` | Repository filter configurations cache | `artipie-main/.../GuavaFiltersCache.java` | L1 |
| `metadata` | Maven group metadata cache | `artipie-main/.../MavenGroupSlice.java` | L1 |
| `cooldown_inspector` | Cooldown inspector cache | `artipie-core/.../CachedCooldownInspector.java` | L1 |

**Cache Tiers:**
- `l1` - In-memory cache (Caffeine) - fast, non-persistent
- `l2` - Persistent cache (Valkey/Redis) - slower, survives restarts

**Cache Operations:** `get`, `put`, `evict`, `clear`

### Example Queries

**Cache hit rate by type:**
```promql
100 * sum(rate(artipie_cache_requests_total{result="hit"}[5m])) by (cache_type) /
  sum(rate(artipie_cache_requests_total[5m])) by (cache_type)
```

**Cache latency p95 by type and operation:**
```promql
histogram_quantile(0.95,
  sum(rate(artipie_cache_operation_duration_seconds_bucket[5m])) by (le, cache_type, operation)
)
```

**Cache eviction rate by type and reason:**
```promql
sum(rate(artipie_cache_evictions_total[5m])) by (cache_type, cache_tier, reason)
```

---

## Storage Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_storage_operations_total` | Counter | `operation`, `result` | Storage operations count | operations |
| `artipie_storage_operation_duration_seconds` | Timer/Histogram | `operation`, `result` | Storage operation duration | seconds |

**Storage Operations:** `read`, `write`, `delete`, `list`, `exists`, `move`  
**Results:** `success`, `failure`

---

## Proxy & Upstream Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_proxy_requests_total` | Counter | `repo_name`, `upstream`, `result` | Proxy upstream requests | requests |
| `artipie_proxy_request_duration_seconds` | Timer/Histogram | `repo_name`, `upstream`, `result` | Proxy upstream request duration | seconds |
| `artipie_upstream_latency_seconds` | Timer/Histogram | `upstream`, `result` | Upstream request latency (general) | seconds |
| `artipie_upstream_errors_total` | Counter | `repo_name`, `upstream`, `error_type` | Upstream errors | errors |
| `artipie_upstream_available` | Gauge | `upstream` | Upstream availability (1=available, 0=unavailable) | boolean |

**Proxy Results:** `success`, `not_found`, `error`
**Error Types:** `timeout`, `connection_refused`, `server_error`, `unknown`

---

## Group Repository Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `artipie_group_requests_total` | Counter | `group_name`, `result` | Group repository requests | requests |
| `artipie_group_member_requests_total` | Counter | `group_name`, `member_name`, `result` | Group member requests | requests |
| `artipie_group_member_latency_seconds` | Timer/Histogram | `group_name`, `member_name`, `result` | Group member request latency | seconds |
| `artipie_group_resolution_duration_seconds` | Timer/Histogram | `group_name` | Group resolution duration | seconds |

**Group Results:** `success`, `not_found`, `error`

---

## JVM & System Metrics

These metrics are automatically provided by Micrometer's JVM instrumentation:

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `jvm_memory_used_bytes` | Gauge | `area`, `id` | JVM memory used | bytes |
| `jvm_memory_max_bytes` | Gauge | `area`, `id` | JVM memory maximum | bytes |
| `jvm_memory_committed_bytes` | Gauge | `area`, `id` | JVM memory committed | bytes |
| `jvm_threads_states_threads` | Gauge | `state` | JVM thread count by state | threads |
| `jvm_threads_live_threads` | Gauge | - | Current live threads | threads |
| `jvm_threads_daemon_threads` | Gauge | - | Current daemon threads | threads |
| `jvm_threads_peak_threads` | Gauge | - | Peak thread count | threads |
| `jvm_gc_pause_seconds` | Timer/Histogram | `action`, `cause` | GC pause duration | seconds |
| `jvm_gc_memory_allocated_bytes_total` | Counter | - | Memory allocated in young generation | bytes |
| `jvm_gc_memory_promoted_bytes_total` | Counter | - | Memory promoted to old generation | bytes |
| `jvm_classes_loaded_classes` | Gauge | - | Currently loaded classes | classes |
| `process_cpu_usage` | Gauge | - | Process CPU usage (0-1 scale) | ratio |
| `system_cpu_usage` | Gauge | - | System CPU usage (0-1 scale) | ratio |
| `system_cpu_count` | Gauge | - | Number of CPU cores | cores |
| `system_load_average_1m` | Gauge | - | System load average (1 minute) | load |

**Memory Areas:** `heap`, `nonheap`  
**Memory IDs:** `PS Eden Space`, `PS Old Gen`, `PS Survivor Space`, `Metaspace`, `Code Cache`, `Compressed Class Space`  
**Thread States:** `runnable`, `blocked`, `waiting`, `timed-waiting`, `new`, `terminated`

---

## Vert.x Metrics

These metrics are automatically provided by Vert.x Micrometer integration:

### HTTP Server Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `vertx_http_server_requests_total` | Counter | `method`, `code`, `path` | HTTP server requests | requests |
| `vertx_http_server_response_time_seconds` | Timer/Histogram | `method`, `code`, `path` | HTTP server response time | seconds |
| `vertx_http_server_active_connections` | Gauge | - | Active HTTP server connections | connections |
| `vertx_http_server_active_requests` | Gauge | - | Active HTTP server requests | requests |
| `vertx_http_server_errors_total` | Counter | `method`, `code` | HTTP server errors | errors |
| `vertx_http_server_request_resets_total` | Counter | - | HTTP server request resets | resets |

### HTTP Client Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `vertx_http_client_requests_total` | Counter | `method`, `code`, `path` | HTTP client requests | requests |
| `vertx_http_client_response_time_seconds` | Timer/Histogram | `method`, `code`, `path` | HTTP client response time | seconds |
| `vertx_http_client_active_connections` | Gauge | - | Active HTTP client connections | connections |
| `vertx_http_client_active_requests` | Gauge | - | Active HTTP client requests | requests |
| `vertx_http_client_queue_pending` | Gauge | - | HTTP client queue pending | requests |
| `vertx_http_client_queue_time_seconds` | Timer/Histogram | - | HTTP client queue time | seconds |
| `vertx_http_client_errors_total` | Counter | `method`, `code` | HTTP client errors | errors |

### Pool Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `vertx_pool_in_use` | Gauge | `pool_type`, `pool_name` | Number of resources in use | resources |
| `vertx_pool_queue_pending` | Gauge | `pool_type`, `pool_name` | Number of pending elements in queue | elements |
| `vertx_pool_completed_total` | Counter | `pool_type`, `pool_name` | Number of elements done with resource | elements |
| `vertx_pool_queue_time_seconds` | Timer/Histogram | `pool_type`, `pool_name` | Time spent in queue before processing | seconds |
| `vertx_pool_usage` | Gauge | `pool_type`, `pool_name` | Pool max size | resources |
| `vertx_pool_ratio` | Gauge | `pool_type`, `pool_name` | Pool usage ratio (in_use/usage) | ratio |

### Event Bus Metrics

| Metric Name | Type | Labels | Description | Unit |
|-------------|------|--------|-------------|------|
| `vertx_eventbus_handlers` | Gauge | `address` | Number of event bus handlers | handlers |
| `vertx_eventbus_pending` | Gauge | `address`, `side` | Event bus pending messages | messages |
| `vertx_eventbus_processed_total` | Counter | `address`, `side` | Event bus processed messages | messages |
| `vertx_eventbus_published_total` | Counter | `address`, `side` | Event bus published messages | messages |
| `vertx_eventbus_sent_total` | Counter | `address`, `side` | Event bus sent messages | messages |
| `vertx_eventbus_received_total` | Counter | `address`, `side` | Event bus received messages | messages |
| `vertx_eventbus_delivered_total` | Counter | `address`, `side` | Event bus delivered messages | messages |
| `vertx_eventbus_discarded_total` | Counter | `address`, `side` | Event bus discarded messages | messages |
| `vertx_eventbus_reply_failures_total` | Counter | `address`, `failure` | Event bus reply failures | failures |

**HTTP Methods:** `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `OPTIONS`, `PATCH`
**HTTP Status Codes:** `200`, `201`, `204`, `301`, `302`, `304`, `400`, `401`, `403`, `404`, `500`, `502`, `503`
**Pool Types:** `worker`, `internal-blocking`
**Event Bus Sides:** `local`, `remote`
**Event Bus Failures:** `TIMEOUT`, `NO_HANDLERS`, `RECIPIENT_FAILURE`

---

## Metric Type Reference

- **Counter**: Monotonically increasing value (e.g., total requests)
  - Prometheus suffix: `_total`
  - Query with `rate()` or `increase()`
  
- **Gauge**: Current value that can go up or down (e.g., active requests, memory usage)
  - No suffix
  - Query directly or with aggregations
  
- **Timer/Histogram**: Distribution of durations
  - Prometheus suffixes: `_seconds_bucket`, `_seconds_count`, `_seconds_sum`, `_seconds_max`
  - Query with `histogram_quantile()` for percentiles
  
- **Distribution Summary**: Distribution of values (e.g., sizes)
  - Prometheus suffixes: `_bytes_bucket`, `_bytes_count`, `_bytes_sum`, `_bytes_max`
  - Query with `histogram_quantile()` for percentiles

---

## Example PromQL Queries

### HTTP Request Rate
```promql
# Total request rate per second
sum(rate(artipie_http_requests_total[5m]))

# Request rate by status code
sum(rate(artipie_http_requests_total[5m])) by (status_code)

# Request rate by repository
sum(rate(artipie_http_requests_total[5m])) by (repo_name)

# Request rate by repository type
sum(rate(artipie_http_requests_total[5m])) by (repo_type)

# Error rate (5xx responses)
sum(rate(artipie_http_requests_total{status_code=~"5.."}[5m]))

# Active repositories (count of repositories with traffic)
count(count by (repo_name) (artipie_http_requests_total))
```

### HTTP Latency Percentiles
```promql
# p95 latency
histogram_quantile(0.95, sum(rate(artipie_http_request_duration_seconds_bucket[5m])) by (le))

# p99 latency by method
histogram_quantile(0.99, sum(rate(artipie_http_request_duration_seconds_bucket[5m])) by (le, method))

# p95 latency by repository
histogram_quantile(0.95, sum(rate(artipie_http_request_duration_seconds_bucket[5m])) by (le, repo_name))
```

### Repository Traffic
```promql
# Upload rate by repository (bytes/sec)
sum(rate(artipie_repo_bytes_uploaded_total[5m])) by (repo_name)

# Download rate by repository (bytes/sec)
sum(rate(artipie_repo_bytes_downloaded_total[5m])) by (repo_name)

# Total traffic by repository (bytes/sec)
sum(rate(artipie_repo_bytes_uploaded_total[5m])) by (repo_name) +
sum(rate(artipie_repo_bytes_downloaded_total[5m])) by (repo_name)

# Traffic by repository type
sum(rate(artipie_repo_bytes_uploaded_total[5m])) by (repo_type) +
sum(rate(artipie_repo_bytes_downloaded_total[5m])) by (repo_type)
```

### Cache Hit Rate
```promql
# Overall cache hit rate
100 * sum(rate(artipie_cache_requests_total{result="hit"}[5m])) /
  (sum(rate(artipie_cache_requests_total{result="hit"}[5m])) +
   sum(rate(artipie_cache_requests_total{result="miss"}[5m])))

# Cache hit rate by type and tier
100 * sum(rate(artipie_cache_requests_total{result="hit"}[5m])) by (cache_type, cache_tier) /
  (sum(rate(artipie_cache_requests_total{result="hit"}[5m])) by (cache_type, cache_tier) +
   sum(rate(artipie_cache_requests_total{result="miss"}[5m])) by (cache_type, cache_tier))
```

### Proxy Success Rate
```promql
# Proxy success rate by upstream
100 * sum(rate(artipie_proxy_requests_total{result="success"}[5m])) by (upstream) /
  sum(rate(artipie_proxy_requests_total[5m])) by (upstream)
```

### Storage Operations
```promql
# Storage operation rate by type
sum(rate(artipie_storage_operations_total[5m])) by (operation)

# Storage error rate
sum(rate(artipie_storage_operations_total{result="failure"}[5m]))
```

### JVM Memory Usage
```promql
# Heap usage percentage
100 * (sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}))

# Memory used by pool
jvm_memory_used_bytes{area="heap"} / (1024*1024)  # Convert to MB
```

### CPU Usage
```promql
# Process CPU usage percentage
100 * process_cpu_usage

# System CPU usage percentage
100 * system_cpu_usage
```

### Vert.x Worker Pool
```promql
# Worker pool utilization
vertx_pool_in_use{pool_type="worker"}

# Worker pool queue depth
vertx_pool_queue_pending{pool_type="worker"}

# p95 queue time
histogram_quantile(0.95, sum(rate(vertx_pool_queue_time_seconds_bucket{pool_type="worker"}[5m])) by (le))
```

---

## Metrics Endpoint

**URL:** `http://localhost:8087/metrics/vertx`
**Format:** Prometheus text format
**Authentication:** None (internal endpoint)

### Example Response
```
# HELP artipie_http_requests_total Total HTTP requests
# TYPE artipie_http_requests_total counter
artipie_http_requests_total{job="artipie",method="GET",status_code="200"} 1234.0
artipie_http_requests_total{job="artipie",method="GET",status_code="404"} 56.0
artipie_http_requests_total{job="artipie",method="POST",status_code="201"} 78.0
artipie_http_requests_total{job="artipie",method="GET",status_code="200",repo_name="my-npm",repo_type="npm"} 456.0
artipie_http_requests_total{job="artipie",method="GET",status_code="200",repo_name="maven-central-proxy",repo_type="maven-proxy"} 789.0

# HELP artipie_repo_bytes_uploaded_total Total bytes uploaded to repository
# TYPE artipie_repo_bytes_uploaded_total counter
artipie_repo_bytes_uploaded_total{job="artipie",repo_name="my-npm",repo_type="npm"} 1048576.0

# HELP artipie_repo_bytes_downloaded_total Total bytes downloaded from repository
# TYPE artipie_repo_bytes_downloaded_total counter
artipie_repo_bytes_downloaded_total{job="artipie",repo_name="my-npm",repo_type="npm"} 5242880.0

# HELP artipie_cache_evictions_total Cache evictions
# TYPE artipie_cache_evictions_total counter
artipie_cache_evictions_total{job="artipie",cache_tier="l1",cache_type="auth",reason="size"} 42.0
artipie_cache_evictions_total{job="artipie",cache_tier="l1",cache_type="cooldown",reason="expired"} 15.0
```

---

## Grafana Dashboards

Artipie includes pre-built Grafana dashboards for visualizing these metrics:

1. **Main Overview** (`/d/artipie-main-overview`) - High-level health and performance
2. **Infrastructure** (`/d/artipie-infrastructure`) - JVM, CPU, GC, threads
3. **Proxy Metrics** (`/d/artipie-proxy`) - Upstream requests and errors
4. **Repository Metrics** (`/d/artipie-repository`) - Repository-specific operations
5. **Group Repository** (`/d/artipie-group`) - Group resolution and member requests
6. **Cache & Storage** (`/d/artipie-cache-storage`) - Cache performance and storage ops

Dashboard files are located in: `docker-compose/grafana/provisioning/dashboards/`

---

## Implementation Notes

### Metric Naming Convention
- Prefix: `artipie_` for all Artipie-specific metrics
- Format: `<namespace>_<subsystem>_<name>_<unit>`
- Example: `artipie_http_request_duration_seconds`

### Label Cardinality
Be cautious with high-cardinality labels (e.g., artifact names, user IDs). Current labels are designed to keep cardinality manageable:
- ✅ Low cardinality: `repo_type`, `operation`, `result`, `cache_tier`, `method`, `status_code`
- ⚠️ Medium cardinality: `repo_name`, `upstream`, `cache_type`
- ❌ Avoid: artifact paths, user IDs, timestamps, full request paths

**Important:** The `path` label was removed from `vertx_http_server_requests_total` to avoid high cardinality. Repository-level metrics use `repo_name` instead, which has much lower cardinality.

### Metric Registration
Metrics are registered lazily on first use via Micrometer's builder pattern. This ensures:
- No metrics are created until actually used
- Duplicate registrations are handled automatically
- Memory footprint is minimized

### Repository Context
HTTP metrics (`artipie_http_requests_total`, `artipie_http_request_duration_seconds`) include `repo_name` and `repo_type` labels when the request is processed in a repository context (i.e., routed through `RepoMetricsSlice`). Requests to non-repository endpoints (e.g., `/api/*`, `/health`, `/metrics`) will not have these labels.

### Performance Impact
Metrics collection has minimal performance impact:
- Counter increment: ~10-20ns
- Timer recording: ~50-100ns
- Histogram recording: ~100-200ns
- Gauge update: ~10-20ns

---

## Future Metrics (Planned)

The following metrics are referenced in dashboards but not yet fully implemented:

- `artipie_cache_hits_total` - Separate counter for cache hits (currently part of `artipie_cache_requests_total`)
- `artipie_cache_misses_total` - Separate counter for cache misses (currently part of `artipie_cache_requests_total`)
- Additional repository-type-specific metrics (Maven, NPM, Docker, etc.)

---

## References

- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Metric Types](https://prometheus.io/docs/concepts/metric_types/)
- [PromQL Documentation](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana Dashboard Guide](https://grafana.com/docs/grafana/latest/dashboards/)


