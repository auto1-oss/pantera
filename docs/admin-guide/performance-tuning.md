# Performance Tuning

> **Guide:** Admin Guide | **Section:** Performance Tuning

This page covers JVM settings, named worker pools, S3 storage tuning, connection pooling, and file descriptor limits for optimizing Pantera performance in production.

---

## JVM Settings

The default Docker image ships with the following JVM settings, optimized for container environments:

```
-XX:+UseG1GC -XX:MaxGCPauseMillis=300
-XX:G1HeapRegionSize=16m
-XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof
-Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m
-Djava.io.tmpdir=/var/pantera/cache/tmp
-Dvertx.cacheDirBase=/var/pantera/cache/tmp
-Dio.netty.allocator.maxOrder=11
-Dio.netty.leakDetection.level=simple
```

### Key JVM Flags

| Flag | Default | Description |
|------|---------|-------------|
| `-XX:+UseG1GC` | Enabled | G1 garbage collector (recommended for Pantera) |
| `-XX:MaxGCPauseMillis=300` | 300 ms | Target maximum GC pause time |
| `-XX:MaxRAMPercentage=75.0` | 75% | Use 75% of container memory for heap |
| `-XX:+UseContainerSupport` | Enabled | Respect container memory and CPU limits |
| `-XX:+ExitOnOutOfMemoryError` | Enabled | Exit on OOM (container orchestrator restarts) |
| `-XX:+HeapDumpOnOutOfMemoryError` | Enabled | Generate heap dump on OOM for analysis |
| `-Dio.netty.allocator.maxOrder=11` | 11 | Limits Netty direct memory chunks to 4 MB |
| `-Dio.netty.leakDetection.level=simple` | simple | Lightweight Netty buffer leak detection |
| `-Djava.io.tmpdir=/var/pantera/cache/tmp` | `/var/pantera/cache/tmp` | Staging directory for proxy cache fills. npm proxy packuments (up to tens of MB each) are streamed through two temp files here instead of heap, so size the volume for the concurrent fills you expect |

### Overriding JVM Settings

Override all JVM settings by setting the `JVM_ARGS` environment variable:

```yaml
environment:
  JVM_ARGS: >-
    -XX:+UseG1GC -XX:MaxGCPauseMillis=200
    -XX:MaxRAMPercentage=80.0
    -XX:+UseContainerSupport
    -XX:+ExitOnOutOfMemoryError
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof
    -Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m
```

When `JVM_ARGS` is set, it replaces the entire default JVM argument string. Include all required flags.

### Memory Sizing Guidelines

| Workload | Container Memory | Heap (MaxRAMPercentage) | Notes |
|----------|-----------------|------------------------|-------|
| Development | 2 GB | 75% (1.5 GB) | Minimal testing |
| Small team (< 50 users) | 4 GB | 75% (3 GB) | Basic production |
| Medium team (50-500 users) | 6 GB | 75% (4.5 GB) | Recommended production |
| Large team (500+ users) | 8-12 GB | 75% (6-9 GB) | High concurrency |

---

## Named Worker Pools

Pantera separates storage operations into three independent thread pools to prevent slow operations from starving fast ones.

| Pool | Environment Variable | Default Size | Purpose |
|------|---------------------|-------------|---------|
| READ | `PANTERA_IO_READ_THREADS` | CPU cores x 4 | Artifact reads, metadata fetches, exists checks |
| WRITE | `PANTERA_IO_WRITE_THREADS` | CPU cores x 2 | Artifact saves, deletes, moves |
| LIST | `PANTERA_IO_LIST_THREADS` | CPU cores x 1 | Directory listings |

### Monitoring Pool Utilization

Monitor pool metrics via Prometheus:

| Metric | Alert If |
|--------|----------|
| `pantera.pool.read.queue` | Consistently > 0 |
| `pantera.pool.write.queue` | Consistently > 0 |
| `pantera.pool.list.queue` | Consistently > 0 |
| `pantera.pool.read.active` | Consistently at max |

If queue depth is consistently above zero, the corresponding pool is saturated. Increase the pool size.

### Sizing Recommendations

| CPUs | READ | WRITE | LIST | Total Threads |
|------|------|-------|------|---------------|
| 2 | 8 | 4 | 2 | 14 |
| 4 | 16 | 8 | 4 | 28 |
| 8 | 32 | 16 | 8 | 56 |
| 16 | 64 | 32 | 16 | 112 |

For S3 storage, READ threads can be set higher because S3 reads are network-bound. For filesystem storage, be mindful of disk I/O saturation.

### Filesystem I/O Threads

A separate pool handles low-level filesystem I/O:

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_FILESYSTEM_IO_THREADS` | `max(8, CPU cores x 2)` | Filesystem I/O thread pool (min: 4, max: 256) |

---

## S3 Tuning

### Connection Settings

| Setting | Config Key | Default | Recommendation |
|---------|-----------|---------|----------------|
| Max concurrent connections | `http.max-concurrency` | 1024 | Increase for >500 concurrent users |
| Max pending acquires | `http.max-pending-acquires` | 2048 | Scale with max-concurrency |
| Read timeout | `http.read-timeout-millis` | 120000 | Increase for large files |
| Connection idle timeout | `http.connection-max-idle-millis` | 30000 | Lower if connections are stale |

### Upload Settings

| Setting | Config Key | Default | Recommendation |
|---------|-----------|---------|----------------|
| Multipart enabled | `multipart` | `true` | Keep enabled for large files |
| Part size | `part-size` | `8MB` | Increase to 16-32 MB for very large artifacts |
| Upload concurrency | `multipart-concurrency` | 16 | Increase for higher upload throughput |
| Min size for multipart | `multipart-min-size` | `32MB` | Lower to 8 MB if many medium-sized files |

### Download Settings

| Setting | Config Key | Default | Recommendation |
|---------|-----------|---------|----------------|
| Parallel download enabled | `parallel-download` | `false` | Enable for large file workloads |
| Download concurrency | `parallel-download-concurrency` | 8 | Increase for large files |
| Chunk size | `parallel-download-chunk-size` | `8MB` | Match network bandwidth |

### Disk Cache Tuning

| Setting | Config Key | Default | Recommendation |
|---------|-----------|---------|----------------|
| Cache size | `cache.max-bytes` | 10 GB | Size to fit hot working set |
| Eviction policy | `cache.eviction-policy` | `LRU` | Use `LFU` for read-heavy workloads with stable access patterns |
| Cleanup interval | `cache.cleanup-interval-millis` | 300000 (5 min) | Lower for faster disk recovery |
| High watermark | `cache.high-watermark-percent` | 90 | Leave headroom for burst |
| Low watermark | `cache.low-watermark-percent` | 80 | Gap between high/low determines eviction batch size |

---

## Connection Pooling

### Database (HikariCP)

| Setting | Environment Variable | Default | Recommendation |
|---------|---------------------|---------|----------------|
| Max pool size | `PANTERA_DB_POOL_MAX` | 50 | Scale with concurrent users; 50-100 for production |
| Min idle | `PANTERA_DB_POOL_MIN` | 10 | Set to 20-30% of max for warm pool |
| Connection timeout | `PANTERA_DB_CONNECTION_TIMEOUT_MS` | 5000 | Increase to 10000 if DB is remote |
| Idle timeout | `PANTERA_DB_IDLE_TIMEOUT_MS` | 600000 | 10 minutes; reduce for cloud DBs with idle connection limits |
| Max lifetime | `PANTERA_DB_MAX_LIFETIME_MS` | 1800000 | 30 minutes; keep below DB-side timeout |
| Leak detection | `PANTERA_DB_LEAK_DETECTION_MS` | 300000 | Lower to 30000 during debugging |

### HTTP Client (Jetty, for Proxy Repos)

Configure in `meta.http_client` section of `pantera.yml`:

| Setting | Config Key | Default | Recommendation |
|---------|-----------|---------|----------------|
| Upstream timeout | `proxy_timeout` | 120 s | Increase for slow upstreams (e.g., 300 s) |
| Max connections per host | `max_connections_per_destination` | 512 | Increase for high-throughput proxying |
| Max queued requests | `max_requests_queued_per_destination` | 2048 | Scale with max connections |
| Idle timeout | `idle_timeout` | 30000 ms | Close idle connections promptly |
| Connect timeout | `connection_timeout` | 15000 ms | Lower for faster failover |
| Connection acquire timeout | `connection_acquire_timeout` | 30000 ms | Time waiting for a pooled connection (acquisition only; never caps the transfer) |

### Jetty Memory

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_JETTY_BUCKET_SIZE` | 1024 | Buffer pool max bucket size |
| `PANTERA_JETTY_DIRECT_MEMORY` | 2 GiB | Max direct memory for Jetty buffers |
| `PANTERA_JETTY_HEAP_MEMORY` | 1 GiB | Max heap memory for Jetty buffers |

For high-throughput proxy workloads, increase `PANTERA_JETTY_DIRECT_MEMORY` and ensure the container has sufficient memory beyond the JVM heap.

---

## File Descriptor Limits

For production deployments with many concurrent connections, set high file descriptor limits. Each HTTP connection, S3 connection, and database connection consumes a file descriptor.

### Docker Compose

```yaml
services:
  pantera:
    ulimits:
      nofile:
        soft: 1048576
        hard: 1048576
      nproc:
        soft: 65536
        hard: 65536
```

### Docker Run

```bash
docker run --ulimit nofile=1048576:1048576 --ulimit nproc=65536:65536 ...
```

### JAR Deployment

Set limits in `/etc/security/limits.conf` or the systemd service unit:

```ini
# /etc/security/limits.conf
pantera  soft  nofile  1048576
pantera  hard  nofile  1048576
pantera  soft  nproc   65536
pantera  hard  nproc   65536
```

The container entrypoint automatically raises soft limits to hard limits (or the configured `ULIMIT_NOFILE` / `ULIMIT_NPROC` values).

### Estimating File Descriptor Requirements

| Component | FDs per Connection | Typical Count |
|-----------|--------------------|---------------|
| HTTP client connections | 1 | max_connections_per_destination x upstream count |
| HTTP server connections | 1 | concurrent users x 2 (keep-alive) |
| Database connections | 1 | pool_max_size (50-100) |
| S3 connections | 1 | http.max-concurrency (1024) |
| Filesystem handles | 1 per open file | varies |

A conservative estimate: `(concurrent_users * 4) + db_pool + s3_concurrency + 1000` for overhead.

---

## Other Tuning Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PANTERA_DEDUP_MAX_AGE_MS` | 300000 (5 min) | Max age for in-flight request deduplication entries |
| `PANTERA_DOCKER_CACHE_EXPIRY_HOURS` | 24 | Docker proxy cache TTL |
| `PANTERA_BODY_BUFFER_THRESHOLD` | 1048576 (1 MB) | Body buffer threshold before spilling to disk |
| `PANTERA_GROUP_DRAIN_PERMITS` | 20 | Concurrent response body drains in group repos |
| `PANTERA_BUF_ACCUMULATOR_MAX_BYTES` | 104857600 (100 MB) | Max buffer for HTTP header parsing (OOM safety) |

---

## Per-Repo Bulkhead (v2.2.0+)

Every `*-proxy` repository has its own limit on the requests it runs at the same time (its *permits*), so a burst on one repository cannot take the capacity of another. Maven proxies are the repositories gated by it.

A request that finds every permit in use **waits** for one and is served when a permit comes free, oldest request first (v2.2.12+). A burst of cold downloads is thereby spread over the permits. A request is refused only when no permit came free within 20 seconds, or when 1000 requests of the repository are already waiting. The refusal is `503` with `Retry-After: 1` and the header `X-Pantera-Bulkhead-Overflow: true`; it increments `pantera_bulkhead_overflow_total{repo}`. Maven's HTTP transport repeats a request answered `503` and obeys `Retry-After` (`aether.connector.http.retryHandler.*`, three attempts by default).

The refusal keeps its `503` and its `Retry-After` on the way out: a proxy with several remotes relays it instead of answering `502 All upstream remotes failed`, and a group skips the member without counting a failure against it and, when no other member serves the path, answers `503` with `Retry-After`.

| Setting | Default | Description |
|---------|---------|-------------|
| `http_client.bulkhead.initial_permits` | 40 | Permits of a repository when its bulkhead is created. |
| `http_client.bulkhead.max_permits` | 100 | Upper bound of the permits. |
| `http_client.bulkhead.adaptive` | `true` | When on, the permits grow from the initial value to the maximum: by `ramp_up_step` (4) after each `window_seconds` (5 s) window in which the repository served requests and none ended in an exception. When off, they stay at the initial value. |
| Longest wait for a permit | 20 s | Fixed. Shorter than the 30 s Gradle waits for a response by default. |
| Waiting requests per repository | 1000 | Fixed. A request beyond it is refused at once. |
| `Retry-After` (seconds) | 1 | Fixed. Header value returned on refusal. |

The keys are runtime settings: see [Configuration Reference](../configuration-reference.md#runtime-tunable-http-client-keys-db-backed-hot-reloaded). A change replaces the bulkhead of every proxy repository at once: requests in flight finish, requests waiting for a permit are let through, and the new bulkhead starts at `initial_permits`.

The permits **never fall** (v2.2.12+). Up to v2.2.11 one request slower than twice `target_p99_ms` lowered them by 30 %, and one failed request halved them, down to `min_permits`; a burst of slow cold downloads through a healthy upstream thereby reduced a repository to 5 permits and every request beyond them was refused at once. `target_p99_ms` and `ramp_down_factor` are still accepted and have no effect; `min_permits` is only the lower bound of `initial_permits`.

**Tuning guidance:**

- `pantera_bulkhead_waiting{repo}` above zero means the repository gets more concurrent requests than it has permits. The requests are served, later. If it stays above zero, raise `max_permits` and `initial_permits`.
- `pantera_bulkhead_overflow_total{repo}` growing means requests were refused. Follow the [Bulkhead Overflow runbook](../runbooks/bulkhead-overflow.md).
- A permit is held until the response headers are ready, which for a cold download is after the artifact has been fetched from the upstream. The permits therefore bound the concurrent upstream fetches of the repository. Keep `max_permits` below `max_connections_per_destination` + `max_requests_queued_per_destination` of `meta.http_client` (64 + 256 by default): the HTTP client rejects a request to an upstream host beyond them (`event.reason: request_rejected`), and no waiting helps that request.

---

## Upstream Circuit Breaker (v2.2.0+)

Per-host state-machine circuit breaker in front of the Jetty client. Trips closed → open when at least half of the calls in the window (minimum 10 in 30 s by default) ended in a 5xx or a non-rejection exception; a 4xx never counts (429 is owned by the rate limiter). Fibonacci backoff with daemon HEAD probe at expiry. The probe asks for the path of the last failed request, not for `/`, and requests are refused until it is answered. The backoff returns to its seed once the breaker has stayed closed for ten windows; an upstream that fails again sooner is blocked longer each time. While open the client sees a synthesised `502` (`X-Pantera-Fault: circuit-breaker-open`) and the broken upstream is left alone.

| Setting | Default | Description |
|---------|---------|-------------|
| Seed backoff | 30 s | First open-window duration after a trip. |
| Max backoff | 60 min | Cap on the Fibonacci envelope. |
| Sequence (with defaults) | 30, 30, 60, 90, 150, 240, 390, 630, 1020, 1650, 2670, 3600... | F(n) = F(n-1) + F(n-2), clamped at cap. |
| Trip on status | 5xx | A 4xx is the upstream answering; 429 belongs to the rate limiter. |
| Trip on exception | every exception except `RejectedExecutionException` | Local backpressure ≠ upstream brokenness. |

**Observability:** `pantera_circuit_breaker_state{upstream_host}` (1=open), `pantera_circuit_breaker_trips_total{upstream_host}`, `pantera_circuit_breaker_fastfail_total{upstream_host}`. Open the **Upstream Circuit Breaker** Grafana dashboard or the [Upstream Circuit Breaker Open runbook](../runbooks/upstream-circuit-breaker-open.md) when the matching alert fires.

**Tuning guidance:** The 30 s seed is conservative — for upstreams with known slow recovery (Maven Central during a CDN incident, npm registry under a regional outage) you can bump the seed up but the cap should stay at 60 min. There is no operator knob to disable the breaker; the kill-switch is "set the seed and cap to small numbers and accept the additional upstream load".

---

## Upstream Connection Retry (v2.2.12+)

Registries and their load balancers close kept-alive connections: on an idle timeout, after a number of requests, on a restart. A close can cross a request that Pantera has already sent on that connection, and the request then fails although the upstream is healthy. Pantera sends such a request again on another connection.

| Rule | Value |
|------|-------|
| Requests repeated | `GET` and `HEAD` without a body. A `POST`, `PUT` or `DELETE` is never repeated: the upstream may already have acted on it. |
| Failure that triggers it | The upstream closed or reset the connection before any part of an answer arrived (`event.reason: connection_closed`). |
| Never repeated | Timeouts, refused connections, DNS and TLS failures, and any answer the upstream did send, whatever its status. |
| Attempts | The first one and up to two more, sent at once. |
| Position | Inside the circuit breaker: a close that the next attempt recovers from is not counted against the upstream. |

A connection lost in the middle of a response body is not covered: the bytes are already on their way to the client.

**Observability:** every repeat writes one `INFO` record with `event.action: http_request_retry`, `event.reason: connection_closed`, `destination.address`, `http.request.method` and `url.path`. Each failed attempt is still counted in `pantera_upstream_requests_total` and logged as `http_request_send`, so a registry that drops connections often stays visible even when no client notices. There is no setting: the rule is fixed.

---

## Related Pages

- [Installation](installation.md) -- Resource recommendations for Docker
- [Environment Variables](environment-variables.md) -- All tunable variables
- [Monitoring](monitoring.md) -- Metrics for identifying bottlenecks
- [Storage Backends](storage-backends.md) -- S3 configuration details
- [Troubleshooting](troubleshooting.md) -- Diagnosing performance issues
