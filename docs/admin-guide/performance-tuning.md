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
| Connection acquire timeout | `connection_acquire_timeout` | 120000 ms | Time waiting for a pooled connection |

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
| `PANTERA_NPM_INDEX_TTL_HOURS` | 24 | npm search index cache TTL |
| `PANTERA_BODY_BUFFER_THRESHOLD` | 1048576 (1 MB) | Body buffer threshold before spilling to disk |
| `PANTERA_GROUP_DRAIN_PERMITS` | 20 | Concurrent response body drains in group repos |
| `PANTERA_BUF_ACCUMULATOR_MAX_BYTES` | 104857600 (100 MB) | Max buffer for HTTP header parsing (OOM safety) |

---

## Related Pages

- [Installation](installation.md) -- Resource recommendations for Docker
- [Environment Variables](environment-variables.md) -- All tunable variables
- [Monitoring](monitoring.md) -- Metrics for identifying bottlenecks
- [Storage Backends](storage-backends.md) -- S3 configuration details
- [Troubleshooting](troubleshooting.md) -- Diagnosing performance issues
