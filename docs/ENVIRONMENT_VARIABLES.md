# Artipie Environment Variables Reference

Complete reference of all `ARTIPIE_*` environment variables for configuring Artipie.

These variables control runtime behavior such as database connection pooling, I/O thread
allocation, cache lifetimes, metrics collection, HTTP client tuning, and concurrency limits.
They are read at startup and cannot be changed without restarting the process.

All variables are optional. When omitted, sensible defaults are used. Variables marked with
a "CPU x N" default are computed relative to the number of available processors at startup.

---

## Database (HikariCP)

Artipie uses HikariCP to manage the PostgreSQL connection pool. These variables control pool
sizing, timeouts, and the event batching pipeline that writes audit events to the database.

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_DB_POOL_MAX` | 50 | Maximum connection pool size | v1.20.12 |
| `ARTIPIE_DB_POOL_MIN` | 10 | Minimum idle connections | v1.20.12 |
| `ARTIPIE_DB_CONNECTION_TIMEOUT_MS` | 5000 | Connection acquisition timeout (ms) | v1.20.14 |
| `ARTIPIE_DB_IDLE_TIMEOUT_MS` | 600000 | Idle connection timeout (ms) | v1.20.14 |
| `ARTIPIE_DB_MAX_LIFETIME_MS` | 1800000 | Maximum connection lifetime (ms) | v1.20.14 |
| `ARTIPIE_DB_LEAK_DETECTION_MS` | 120000 | Connection leak detection threshold (ms) | v1.20.12 |
| `ARTIPIE_DB_BUFFER_SECONDS` | 2 | Event batch buffer time (s) | v1.20.12 |
| `ARTIPIE_DB_BATCH_SIZE` | 50 | Events per batch | v1.20.12 |

**Guidance:**

- `ARTIPIE_DB_POOL_MAX` should be sized based on the expected number of concurrent storage
  operations. Each proxy request, upload, and search query may acquire a connection. A good
  starting point is 2x the number of Vert.x worker threads.
- `ARTIPIE_DB_LEAK_DETECTION_MS` logs a warning when a connection is held longer than the
  threshold. Set it to slightly above your longest expected query time.
- `ARTIPIE_DB_BUFFER_SECONDS` and `ARTIPIE_DB_BATCH_SIZE` control how audit events are
  batched before writing to the database. Larger values reduce database write frequency at
  the cost of slightly delayed event visibility.

---

## I/O Thread Pools

Storage operations are dispatched to three named thread pools, each dedicated to a specific
operation type. This isolation prevents slow directory listings from blocking artifact
downloads and prevents large uploads from starving metadata reads.

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_IO_READ_THREADS` | CPU x 4 | Read pool thread count | v1.20.14 |
| `ARTIPIE_IO_WRITE_THREADS` | CPU x 2 | Write pool thread count | v1.20.14 |
| `ARTIPIE_IO_LIST_THREADS` | CPU | List pool thread count | v1.20.14 |

**Guidance:**

- The read pool handles metadata reads and artifact content fetches. It has the highest
  default multiplier because read operations are the most frequent and typically the most
  latency-sensitive.
- The write pool handles artifact saves, deletes, and moves. These are less frequent but
  can involve large data transfers.
- The list pool handles directory and prefix listings. On S3 backends with many keys,
  listings can be slow, so isolating them prevents impact on reads and writes.
- Monitor `artipie.pool.{read,write,list}.queue` metrics. If queue depth is consistently
  above zero, increase the corresponding pool size.

---

## Cache & Deduplication

These variables control cache lifetimes and the deduplication system that prevents redundant
concurrent fetches of the same artifact from upstream.

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_DEDUP_MAX_AGE_MS` | 300000 | Maximum age of in-flight dedup entries before zombie eviction (ms) | v1.20.14 |
| `ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS` | 24 | Docker proxy cache TTL (hours) | v1.20.14 |
| `ARTIPIE_NPM_INDEX_TTL_HOURS` | 24 | NPM package index cache TTL (hours) | v1.20.14 |
| `ARTIPIE_BODY_BUFFER_THRESHOLD` | 1048576 | Body buffer threshold before spilling to disk (bytes) | v1.20.12 |

**Guidance:**

- `ARTIPIE_DEDUP_MAX_AGE_MS` controls how long a dedup entry is kept before it is
  considered a zombie (stale in-flight request) and evicted. If proxy requests to
  slow upstreams frequently timeout, increase this value.
- `ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS` determines how long Docker manifest and blob
  metadata is cached before re-validating with the upstream registry.
- `ARTIPIE_NPM_INDEX_TTL_HOURS` controls how long the NPM package index (the JSON
  document listing all versions of a package) is cached.
- `ARTIPIE_BODY_BUFFER_THRESHOLD` sets the size at which request bodies are spilled
  from memory to a temporary file on disk. Increase this for environments with ample
  memory to reduce disk I/O; decrease it for memory-constrained deployments.

---

## Metrics

These variables control Prometheus metrics collection behavior.

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_METRICS_MAX_REPOS` | 50 | Maximum distinct repository names in metrics labels | v1.20.12 |
| `ARTIPIE_METRICS_PERCENTILES_HISTOGRAM` | false | Enable percentile histograms (increases cardinality) | v1.20.12 |

**Guidance:**

- `ARTIPIE_METRICS_MAX_REPOS` caps the number of distinct repository names tracked as
  metric label values. When exceeded, additional repositories are aggregated under an
  `_other` label. This prevents unbounded cardinality in Prometheus.
- `ARTIPIE_METRICS_PERCENTILES_HISTOGRAM` enables client-side percentile computation
  in Micrometer histograms. This significantly increases the number of time series
  exported to Prometheus. Only enable if your monitoring stack does not support
  server-side histogram quantile computation.

---

## HTTP Client (Jetty)

Artipie uses a Jetty HTTP client for outbound proxy requests to upstream registries. These
variables control memory allocation for the Jetty byte buffer pool.

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_JETTY_BUCKET_SIZE` | (from settings) | Jetty ByteBufferPool bucket size | v1.20.12 |
| `ARTIPIE_JETTY_DIRECT_MEMORY` | (from settings) | Jetty direct memory limit | v1.20.12 |
| `ARTIPIE_JETTY_HEAP_MEMORY` | (from settings) | Jetty heap memory limit | v1.20.12 |

**Guidance:**

- These variables override values set in the Artipie configuration file. They are useful
  when you need to tune Jetty memory independently of the main configuration.
- `ARTIPIE_JETTY_DIRECT_MEMORY` limits the amount of off-heap memory Jetty uses for
  network buffers. Increase this for high-throughput proxy scenarios with many concurrent
  upstream connections.
- `ARTIPIE_JETTY_HEAP_MEMORY` limits on-heap memory used by Jetty for request/response
  processing.

---

## Concurrency & Scheduling

These variables control concurrency limits for specific subsystems.

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_GROUP_DRAIN_PERMITS` | 20 | Group repository drain semaphore permits | v1.20.12 |
| `ARTIPIE_FILESYSTEM_IO_THREADS` | (auto) | Filesystem I/O thread pool size | v1.20.12 |

**Guidance:**

- `ARTIPIE_GROUP_DRAIN_PERMITS` controls how many concurrent drain operations can run
  on a group repository. A drain operation iterates over all members to build the
  aggregated response. Limiting concurrency prevents excessive fan-out under load.
- `ARTIPIE_FILESYSTEM_IO_THREADS` sets the thread pool size for filesystem-specific I/O
  operations. When set to `(auto)`, the size is determined by the JVM based on available
  processors. Override this when running on systems where the JVM underestimates available
  I/O parallelism (for example, NVMe storage with high queue depth).

---

## Miscellaneous

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_DIAGNOSTICS_DISABLED` | false | Disable blocked thread diagnostics | v1.20.12 |
| `ARTIPIE_INIT` | false | Initialize with example configuration on first start | v1.20.12 |

**Guidance:**

- `ARTIPIE_DIAGNOSTICS_DISABLED` suppresses the periodic blocked-thread detector that
  logs warnings when Vert.x event loop threads are blocked for too long. Disable this
  only if the diagnostics themselves cause performance issues (rare).
- `ARTIPIE_INIT` causes Artipie to generate a default configuration with example
  repositories on first startup. This is useful for quick evaluation but should be
  set to `false` (or omitted) in production.

---

## Quick Reference

All variables at a glance, sorted alphabetically:

```
ARTIPIE_BODY_BUFFER_THRESHOLD=1048576
ARTIPIE_DB_BATCH_SIZE=50
ARTIPIE_DB_BUFFER_SECONDS=2
ARTIPIE_DB_CONNECTION_TIMEOUT_MS=5000
ARTIPIE_DB_IDLE_TIMEOUT_MS=600000
ARTIPIE_DB_LEAK_DETECTION_MS=120000
ARTIPIE_DB_MAX_LIFETIME_MS=1800000
ARTIPIE_DB_POOL_MAX=50
ARTIPIE_DB_POOL_MIN=10
ARTIPIE_DEDUP_MAX_AGE_MS=300000
ARTIPIE_DIAGNOSTICS_DISABLED=false
ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS=24
ARTIPIE_FILESYSTEM_IO_THREADS=(auto)
ARTIPIE_GROUP_DRAIN_PERMITS=20
ARTIPIE_INIT=false
ARTIPIE_IO_LIST_THREADS=(CPU)
ARTIPIE_IO_READ_THREADS=(CPU x 4)
ARTIPIE_IO_WRITE_THREADS=(CPU x 2)
ARTIPIE_JETTY_BUCKET_SIZE=(from settings)
ARTIPIE_JETTY_DIRECT_MEMORY=(from settings)
ARTIPIE_JETTY_HEAP_MEMORY=(from settings)
ARTIPIE_METRICS_MAX_REPOS=50
ARTIPIE_METRICS_PERCENTILES_HISTOGRAM=false
ARTIPIE_NPM_INDEX_TTL_HOURS=24
```
