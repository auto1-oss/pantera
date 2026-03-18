# Artipie Developer Guide

**Version:** 1.20.14
**Last Updated:** February 2026

---

## Table of Contents

1. [Introduction](#introduction)
2. [Architecture Overview](#architecture-overview)
3. [Database Layer](#database-layer)
4. [Cache Architecture](#cache-architecture)
5. [Cluster Architecture](#cluster-architecture)
6. [Thread Model](#thread-model)
7. [Shutdown Sequence](#shutdown-sequence)
8. [Health Check Architecture](#health-check-architecture)
9. [Development Environment Setup](#development-environment-setup)
10. [Build System](#build-system)
11. [Project Structure](#project-structure)
12. [Core Concepts](#core-concepts)
13. [Adding New Features](#adding-new-features)
14. [Testing](#testing)
15. [Code Style & Standards](#code-style--standards)
16. [Debugging](#debugging)
17. [Contributing](#contributing)
18. [Roadster (Rust) Development](#roadster-rust-development)

---

## Introduction

This guide is for developers who want to contribute to Artipie or understand its internal architecture. Artipie is a binary artifact repository manager supporting 16+ package formats.

### Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| **Language** | Java | 21+ |
| **Build** | Apache Maven | 3.2+ |
| **HTTP Framework** | Vert.x | 4.5.22 |
| **Async I/O** | CompletableFuture | Java 21 |
| **HTTP Client** | Jetty | 12.1.4 |
| **Serialization** | Jackson | 2.16.2 |
| **Caching** | Guava/Caffeine | 33.0.0 |
| **Database** | PostgreSQL + HikariCP | 42.x / 5.x |
| **Distributed Cache** | Valkey (Redis-compatible) | 7.x |
| **Scheduling** | Quartz | 2.3.x |
| **Metrics** | Micrometer | 1.12.1 |
| **Logging** | Log4j 2 | 2.22.1 |
| **Testing** | JUnit 5 | 5.10.0 |
| **Containers** | TestContainers | 2.0.2 |

---

## Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     HTTP Layer (Vert.x)                      │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────────────┐ │
│  │MainSlice│──│TimeoutSl│──│AuthSlice│──│RepositorySlices │ │
│  └─────────┘  └─────────┘  └─────────┘  └─────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                   Repository Adapters                        │
│  ┌──────┐ ┌──────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐        │
│  │Maven │ │Docker│ │ NPM │ │PyPI │ │Helm │ │ ... │        │
│  └──────┘ └──────┘ └─────┘ └─────┘ └─────┘ └─────┘        │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│               Cache Layer (Proxy Repositories)               │
│  ┌────────────┐  ┌────────────┐  ┌──────────────────┐       │
│  │ DiskCache  │  │NegativeCache│ │RequestDeduplicator│       │
│  │(LRU/LFU)  │  │(L1+L2 tier)│ │  (coalescing)    │       │
│  └────────────┘  └────────────┘  └──────────────────┘       │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                  Storage Layer (Asto)                        │
│  ┌────────────┐  ┌────────┐  ┌──────┐  ┌───────┐           │
│  │ FileSystem │  │   S3   │  │ etcd │  │ Redis │           │
│  └────────────┘  └────────┘  └──────┘  └───────┘           │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│              Database Layer (PostgreSQL)                      │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌────────────┐ │
│  │Artifacts │  │  Search  │  │   Node    │  │   Quartz   │ │
│  │ Metadata │  │(tsvector)│  │ Registry  │  │   JDBC     │ │
│  └──────────┘  └──────────┘  └───────────┘  └────────────┘ │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│             Cluster Layer (HA Coordination)                   │
│  ┌───────────┐  ┌───────────────┐  ┌────────────────────┐   │
│  │   Node    │  │ ClusterEvent  │  │ CacheInvalidation  │   │
│  │ Registry  │  │  Bus (Valkey) │  │   PubSub (Valkey)  │   │
│  └───────────┘  └───────────────┘  └────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Request Flow

```
HTTP Request (port 8080)
    |
[BaseSlice] - metrics, headers, observability
    |
[TimeoutSlice] - 120s timeout protection
    |
[MainSlice] - routing dispatcher
    |
+---> /.health -> HealthSlice (5-component probe)
+---> /.version -> VersionSlice
+---> /* (fallback) -> DockerRoutingSlice -> ApiRoutingSlice -> SliceByPath
                            |
                    RepositorySlices Cache lookup/create
                            |
                    [Adapter Slice] (e.g., NpmSlice, MavenSlice)
                            |
                    Local: read from Storage
                    Proxy: negative cache -> disk cache -> dedup -> upstream -> cache -> respond
                    Group: index.locate() -> targeted member query (or fan-out if index cold)
                            |
                    Response (async/reactive)
```

### Key Subsystems

1. **HTTP Layer**: Vert.x-based non-blocking HTTP server
2. **Repository Management**: Dynamic slice creation and caching
3. **Storage Abstraction (Asto)**: Pluggable storage backends
4. **Authentication**: Basic, JWT, OAuth/OIDC support
5. **Authorization**: Role-based access control (RBAC)
6. **REST API**: Management endpoints on separate port
7. **Database Layer**: PostgreSQL for artifact metadata, full-text search, node registry, and Quartz job scheduling
8. **Cache Layer**: Disk-based artifact cache with negative cache (L1 Caffeine + L2 Valkey) and request deduplication
9. **Cluster Layer**: PostgreSQL-backed node registry, Valkey pub/sub event bus, cross-instance cache invalidation
10. **Artifact Index**: Dual-path search with tsvector/GIN full-text primary and LIKE fallback; Lucene for in-memory O(1) group lookups

### Artifact Index Architecture

The artifact index provides efficient lookups for group repository resolution and search.

**Dual Implementation:**
- `LuceneArtifactIndex` -- In-memory Lucene index (MMapDirectory in production) for O(1) group lookups
- `DbArtifactIndex` -- PostgreSQL-backed index for persistent search with tsvector/GIN full-text search

**Event Flow (Write Path):**
```
Upload/Delete -> Event Queue -> IndexConsumer -> LuceneArtifactIndex
                             -> DbConsumer -> PostgreSQL (artifacts table)
```

**Request Flow (Read Path - Group Repos):**
```
Request -> GroupSlice -> index.locate(path)
  |-- Index hit -> query only matching member(s)
  |-- Index warm miss -> return 404 immediately
  +-- Index cold (warming up) -> fan-out to all members
```

**Key Classes:**
- `LuceneArtifactIndex` - Lucene index implementation (MMapDirectory in production)
- `DbArtifactIndex` - PostgreSQL index with tsvector full-text search and LIKE fallback
- `IndexConsumer` - Bridges ArtifactEvent to index writes
- `IndexWarmupService` - Scans storage on startup to populate index
- `GroupSlice` - Uses index for targeted member queries
- `SearchRest` - REST API for search and index stats

---

## Database Layer

PostgreSQL serves as the authoritative data store for artifact metadata, full-text search, node registry, cooldown tracking, and Quartz job scheduling. All database access goes through HikariCP connection pooling.

### Connection Pool (`ArtifactDbFactory`)

`ArtifactDbFactory` initializes the HikariCP connection pool and runs schema migrations on startup. It reads configuration from the `artifacts_database` section in `artipie.yaml` and supports `${ENV_VAR}` placeholder resolution for all connection parameters.

**Pool defaults (configurable via YAML or environment variables):**

| Parameter | Env Var | Default |
|-----------|---------|---------|
| Max pool size | `ARTIPIE_DB_POOL_MAX` | 50 |
| Min idle connections | `ARTIPIE_DB_POOL_MIN` | 10 |
| Connection timeout | `ARTIPIE_DB_CONNECTION_TIMEOUT_MS` | 5000 ms |
| Idle timeout | `ARTIPIE_DB_IDLE_TIMEOUT_MS` | 600000 ms (10 min) |
| Max lifetime | `ARTIPIE_DB_MAX_LIFETIME_MS` | 1800000 ms (30 min) |
| Leak detection threshold | `ARTIPIE_DB_LEAK_DETECTION_MS` | 120000 ms (2 min) |

Connection leak detection is enabled by default with a 120-second threshold. This was increased from 60 seconds to reduce false positives during batch processing, where `DbConsumer` batch operations can exceed 60 seconds under high load. JMX MBeans are registered for runtime pool monitoring.

**Schema migrations** run automatically during `initialize()`. The factory creates the `artifacts`, `artifact_cooldowns`, and `import_sessions` tables with all necessary indexes and constraints. Migrations are idempotent: columns and indexes use `IF NOT EXISTS`, and errors during migration steps (e.g., adding a column that already exists) are caught and logged at DEBUG level rather than failing startup.

### Batch Event Processing (`DbConsumer`)

`DbConsumer` implements `Consumer<ArtifactEvent>` and processes artifact metadata events in batches using RxJava's `PublishSubject` with `buffer()`. Events are collected into batches by time window and maximum count, then written to PostgreSQL in a single transaction.

**Batch configuration:**

| Parameter | Env Var | Default |
|-----------|---------|---------|
| Batch size | `ARTIPIE_DB_BATCH_SIZE` | 50 |
| Buffer time | `ARTIPIE_DB_BUFFER_SECONDS` | 2 seconds |

Both parameters are also configurable via the `buffer_size` and `buffer_time_seconds` keys in the `artifacts_database` YAML section.

**Write operations** within a batch are sorted by `(repo_name, name, version)` before execution. This consistent lock ordering prevents deadlocks when multiple consumer threads process overlapping artifacts concurrently. The `DbObserver` inner class handles three event types:
- `INSERT` -- Atomic UPSERT via `ON CONFLICT (repo_name, name, version) DO UPDATE`
- `DELETE_VERSION` -- Delete a specific version
- `DELETE_ALL` -- Delete all versions of an artifact

**Dead-letter queue:** When a batch commit fails, the events are re-queued with exponential backoff (1s, 2s, 4s, capped at 8s). After 3 consecutive batch failures, events are written to a dead-letter file at `{artipie.home}/.dead-letter/` and discarded from the processing pipeline. The dead-letter file includes the events, the exception, and the failure count for later analysis. Individual statement failures within a successful batch are re-queued only if there are 5 or fewer; larger numbers of individual failures are dropped with an error log to prevent unbounded retry loops.

### Full-Text Search (`DbArtifactIndex`)

`DbArtifactIndex` implements the `ArtifactIndex` interface using PostgreSQL as the backing store. Unlike `LuceneArtifactIndex`, it requires no warmup scan since the database is the authoritative source and is always consistent.

**Search uses a dual-path strategy:**

1. **Primary (tsvector/GIN):** Queries use `plainto_tsquery('simple', ?)` against the `search_tokens` tsvector column, with results ranked by `ts_rank()`. The `'simple'` text search configuration avoids language-specific stemming, which is inappropriate for artifact names and versions.

2. **Fallback (LIKE):** If the tsvector search returns zero results, a `LOWER(name) LIKE LOWER(?)` query runs automatically. This handles substring matches that tsvector misses. If the query contains SQL wildcards (`%` or `_`), LIKE is used directly without attempting tsvector first. If tsvector throws an error (e.g., column does not exist on a legacy schema), the error is caught and LIKE is used as graceful degradation.

**Schema details:**

```sql
-- tsvector column for full-text search
ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS search_tokens tsvector;

-- GIN index for fast full-text search
CREATE INDEX IF NOT EXISTS idx_artifacts_search ON artifacts USING gin(search_tokens);

-- Auto-update trigger populates search_tokens on INSERT/UPDATE
CREATE OR REPLACE FUNCTION artifacts_search_update() RETURNS trigger AS $$
BEGIN
  NEW.search_tokens := to_tsvector('simple',
    coalesce(NEW.name, '') || ' ' ||
    coalesce(NEW.owner, '') || ' ' ||
    coalesce(NEW.repo_name, '') || ' ' ||
    coalesce(NEW.repo_type, ''));
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_artifacts_search
  BEFORE INSERT OR UPDATE ON artifacts
  FOR EACH ROW EXECUTE FUNCTION artifacts_search_update();
```

On startup, `ArtifactDbFactory` also backfills `search_tokens` for any existing rows where the column is NULL, ensuring a seamless upgrade path from older schema versions.

**Additional performance indexes:**

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_artifacts_repo_lookup` | `(repo_name, name, version)` | Primary lookup |
| `idx_artifacts_locate` | `(name, repo_name) INCLUDE (repo_type)` | Index-only scan for `locate()` |
| `idx_artifacts_browse` | `(repo_name, name, version) INCLUDE (size, created_date, owner)` | Browse operations |
| `idx_artifacts_search` | `search_tokens` (GIN) | Full-text search |

---

## Cache Architecture

Proxy repositories use a multi-layer caching strategy to minimize upstream requests and provide offline resilience. The caching pipeline is implemented in `BaseCachedProxySlice`, which all adapter-specific proxy slices extend.

### Proxy Cache Pipeline (`BaseCachedProxySlice`)

`BaseCachedProxySlice` implements a 7-step pipeline for every proxy request:

```
1. Check negative cache      -- fast-fail on known 404s (Caffeine L1)
2. Check local disk cache    -- serve if fresh hit (offline-safe)
3. Evaluate cooldown         -- block if artifact is in cooldown period
4. Deduplicate request       -- coalesce concurrent fetches for same key
5. Fetch from upstream       -- HTTP GET to remote registry
6. Stream, digest, store     -- NIO temp file streaming, compute checksums, save to cache
7. Respond                   -- return content to caller, enqueue metadata event
```

On a 404 from upstream, the negative cache is updated. On a 5xx or exception, the error is tracked for circuit-breaking metrics. Adapters override only the hooks they need: `isCacheable(path)`, `buildCooldownRequest(path, headers)`, `digestAlgorithms()`, `buildArtifactEvent(key, headers, size)`, `postProcess(response, line)`, and `generateSidecars(path, digests)`.

Cache writes use NIO temp file streaming to avoid buffering full artifacts on the Java heap. Content is streamed from the upstream response directly to a temp file while computing digests incrementally. Once streaming completes, the temp file is read back and stored in the Asto cache. Temp files are registered with `deleteOnExit()` as a safety net.

### Request Deduplication (`RequestDeduplicator`)

When multiple clients request the same uncached artifact simultaneously, `RequestDeduplicator` ensures only one upstream fetch is performed. Other callers wait for the same `CompletableFuture` and receive a signal (`SUCCESS`, `NOT_FOUND`, or `ERROR`) when the fetch completes.

**How it works:**
- A `ConcurrentHashMap<Key, InFlightEntry>` tracks in-flight fetches.
- The first request for a key creates a new entry via `putIfAbsent()` and executes the fetch supplier.
- Subsequent requests for the same key get the existing entry's future and wait.
- When the fetch completes, the entry is removed and all waiting futures are completed with the same signal.
- After receiving a `SUCCESS` signal, waiting callers read the artifact from cache (populated by the winning fetch).

**Zombie protection:** A single daemon thread (`dedup-cleanup`) runs every 60 seconds and evicts entries that have been in-flight longer than `ARTIPIE_DEDUP_MAX_AGE_MS` (default: 300000 ms / 5 minutes). Evicted entries are completed with `ERROR`, allowing waiting callers to fail gracefully rather than hang indefinitely.

The deduplication strategy is configurable per repository via `ProxyCacheConfig.dedupStrategy()`:
- `SIGNAL` (default) -- Full deduplication as described above.
- `STORAGE` -- Deduplication delegated to the storage layer (no application-level coalescing).
- `NONE` -- Every request fetches independently from upstream.

### Negative Cache (`NegativeCache`)

`NegativeCache` prevents repeated upstream requests for artifacts that are known to not exist (404). This is critical for proxy repositories where clients frequently request optional dependencies, typos, or artifacts from the wrong registry.

**Two-tier architecture:**

| Tier | Technology | TTL (default) | Max Size | Purpose |
|------|-----------|---------------|----------|---------|
| L1 | Caffeine (in-memory) | 5 min (with Valkey) or 24h (standalone) | 5000 (with Valkey) or 50000 | Hot data, sub-microsecond lookups |
| L2 | Valkey (distributed) | 24 hours | Unbounded (TTL-evicted) | Warm data, shared across instances |

**L1 (Caffeine):** Checked synchronously on every request via `isNotFound(key)`. Caffeine provides Window TinyLFU eviction, automatic TTL expiry, and thread-safe access with no explicit locking. Cache statistics (hit rate, miss rate, eviction count) are recorded via `recordStats()`.

**L2 (Valkey):** Checked asynchronously via `isNotFoundAsync(key)` with a 100ms timeout to prevent Valkey latency from blocking the request path. On an L2 hit, the entry is promoted to L1 for subsequent fast lookups. L2 keys are namespaced by repository type and name: `negative:{repoType}:{repoName}:{key}`. Bulk invalidation uses `SCAN` instead of `KEYS` to avoid blocking the Valkey server.

When Valkey is not configured, the negative cache operates in single-tier mode with a larger L1 (50K entries, 24h TTL).

### Disk Cache Storage (`DiskCacheStorage`)

`DiskCacheStorage` is a read-through on-disk cache for downloads from the underlying S3 or remote storage. It validates cache entries against remote ETag/size before serving and runs scheduled cleanup with LRU/LFU eviction using high/low watermarks. Striped locks prevent contention when multiple threads access different cache entries. All cleanup tasks share a single bounded `ScheduledExecutorService` to prevent thread proliferation.

---

## Cluster Architecture

Artipie supports multi-instance HA deployments through PostgreSQL-backed coordination and Valkey pub/sub messaging.

### Node Registry (`DbNodeRegistry`)

`DbNodeRegistry` implements PostgreSQL-backed node registration with heartbeat-based liveness detection. Each Artipie instance is tracked in the `artipie_nodes` table.

**Lifecycle:**

1. **Registration (`register`):** On startup, each instance upserts its `node_id`, `hostname`, and `started_at` into the table. Status is set to `active`. The UPSERT pattern (`ON CONFLICT(node_id) DO UPDATE`) handles restarts cleanly.

2. **Heartbeat (`heartbeat`):** A Quartz-scheduled job periodically calls `heartbeat(nodeId)`, which updates `last_heartbeat` to the current time and ensures status is `active`. If the node is unknown (no row found), a warning is logged.

3. **Liveness detection (`liveNodes`):** Returns all nodes whose `last_heartbeat` is within a configurable timeout and whose status is `active`. This query is used by the cluster layer to determine which peers are alive.

4. **Deregistration (`deregister`):** On graceful shutdown, the node's status is set to `stopped`. The row is retained for audit purposes.

5. **Stale eviction (`evictStale`):** Physically deletes rows whose `last_heartbeat` is older than the timeout. This prevents the table from growing unboundedly from crashed instances that never deregistered.

**Schema:**
```sql
CREATE TABLE IF NOT EXISTS artipie_nodes(
   node_id VARCHAR(255) PRIMARY KEY,
   hostname VARCHAR(255) NOT NULL,
   port INT NOT NULL,
   started_at TIMESTAMP NOT NULL,
   last_heartbeat TIMESTAMP NOT NULL,
   status VARCHAR(32) NOT NULL
);
```

### Cluster Event Bus (`ClusterEventBus`)

`ClusterEventBus` provides cross-instance event notification using Valkey pub/sub. It enables instances to coordinate configuration changes, repository updates, and other cluster-wide events without polling.

**Design:**

- Each instance generates a `UUID` on startup as its unique instance identifier.
- Events are published on Valkey channels named `artipie:events:{topic}`.
- Message wire format: `{instanceId}|{payload}`. The sender's instance ID is prepended to every message.
- On receipt, the `Dispatcher` (a `RedisPubSubAdapter`) extracts the sender ID. If it matches the local instance ID, the message is ignored to prevent double-processing of events that were already handled locally.
- Topic handlers are stored in a `ConcurrentHashMap<String, List<Consumer<String>>>`. Each topic can have multiple handlers. Handler lists use `CopyOnWriteArrayList` for thread-safe iteration during dispatch.
- The first handler registered for a topic triggers the Valkey channel subscription. Pub/sub spec requires separate connections for subscribe and publish, so two `StatefulRedisPubSubConnection` instances are maintained.
- Handler exceptions are caught and logged individually; a failing handler does not prevent other handlers from executing.

### Cache Invalidation (`CacheInvalidationPubSub`)

`CacheInvalidationPubSub` solves the problem of stale in-memory Caffeine caches in multi-instance deployments. When one instance updates data (e.g., configuration changes, filter updates, policy changes), other instances must invalidate their local caches.

**How it works:**

1. Each cache type (e.g., `"auth"`, `"filters"`, `"policy"`) registers a `Cleanable<String>` implementation via `register(name, cache)`.
2. When a local cache is modified, the modifying code calls `publish(cacheType, key)` or `publishAll(cacheType)`.
3. The message `{instanceId}|{cacheType}|{key}` is published on the `artipie:cache:invalidate` channel.
4. Other instances receive the message, extract the cache type and key, look up the registered `Cleanable`, and call `invalidate(key)` or `invalidateAll()`.
5. The publishing instance ignores its own messages (self-message filtering via instance UUID).

### Quartz JDBC Clustering

`QuartzService` supports two modes:

- **RAM mode** (default, no-arg constructor): In-memory `RAMJobStore`. Suitable for single-instance deployments.
- **JDBC mode** (`QuartzService(DataSource)` constructor): Uses `JobStoreTX` with `PostgreSQLDelegate` and Quartz clustering enabled. Multiple Artipie instances coordinate job execution through the database, preventing duplicate scheduling.

The JDBC constructor:
1. Creates the `QRTZ_*` tables via `QuartzSchema` if they do not exist.
2. Registers an `ArtipieQuartzConnectionProvider` wrapping the HikariCP `DataSource` with Quartz's `DBConnectionManager`.
3. Configures Quartz with 10 worker threads, a shared scheduler name (`ArtipieScheduler`), 15-second cluster check-in interval, and 60-second misfire threshold.

---

## Thread Model

Artipie uses several named thread pools, each dedicated to a specific class of work. This separation prevents slow operations (e.g., large file writes) from starving fast operations (e.g., metadata reads).

### Storage I/O Pools (`StorageExecutors`)

Three fixed-size pools handle all storage operations. Pool sizes are configurable via environment variables and default to multiples of `Runtime.getRuntime().availableProcessors()`:

| Pool | Env Var | Default Size | Thread Name Pattern | Operations |
|------|---------|-------------|---------------------|------------|
| READ | `ARTIPIE_IO_READ_THREADS` | CPU x 4 | `artipie-io-read-%d` | `value()`, `exists()`, metadata reads |
| WRITE | `ARTIPIE_IO_WRITE_THREADS` | CPU x 2 | `artipie-io-write-%d` | `save()`, `move()`, `delete()` |
| LIST | `ARTIPIE_IO_LIST_THREADS` | CPU x 1 | `artipie-io-list-%d` | `list()` operations |

All threads are daemon threads. `DispatchedStorage` wraps any `Storage` implementation to route operations to the appropriate pool. Pool utilization metrics (active threads, queue depth) are registered as Micrometer gauges when metrics are enabled.

### Other Thread Pools

| Pool | Size | Thread Name | Purpose |
|------|------|------------|---------|
| **Vert.x event loop** | CPU x 2 (Vert.x default) | `vert.x-eventloop-thread-*` | HTTP request/response lifecycle, non-blocking I/O |
| **Vert.x worker pool** | 20 (Vert.x default) | `vert.x-worker-thread-*` | Blocking operations dispatched via `executeBlocking()` |
| **Quartz thread pool** | 10 | `ArtipieScheduler_Worker-*` | Scheduled jobs: metadata batch processing (`EventsProcessor`), temp file cleanup (`TempFileCleanupJob`), lock TTL cleanup |
| **Dedup cleanup** | 1 | `dedup-cleanup` | Daemon thread for evicting zombie `RequestDeduplicator` entries (runs every 60s) |
| **DB artifact index** | max(2, CPU) | `db-artifact-index-*` | Async JDBC operations for `DbArtifactIndex` search and indexing |
| **Disk cache cleanup** | max(2, CPU/4) | `artipie.asto.s3.cache.cleaner` | Shared scheduler for LRU/LFU disk cache eviction (daemon, min priority) |

### Thread Safety Patterns

- **ConcurrentHashMap**: Used for request deduplication (`RequestDeduplicator.inFlight`), event bus handlers (`ClusterEventBus.handlers`), cache invalidation registrations (`CacheInvalidationPubSub.caches`).
- **CopyOnWriteArrayList**: Used for event bus topic handlers to allow safe iteration during dispatch without locking.
- **AtomicBoolean**: Used in `QuartzService.stopped` to prevent double-shutdown.
- **AtomicInteger**: Used in `DbConsumer.DbObserver.consecutiveFailures` for retry tracking.
- **Striped locks**: Used in `DiskCacheStorage` to prevent contention when multiple threads access different cache entries.

---

## Shutdown Sequence

`VertxMain.stop()` performs a structured shutdown to ensure all in-flight work completes and resources are released cleanly. The sequence is ordered from outermost (user-facing) to innermost (infrastructure).

**Shutdown phases:**

1. **Stop HTTP/3 servers** -- Calls `Http3Server.stop()` for each HTTP/3 port. Stops accepting new connections.

2. **Stop HTTP/1.1+2 servers** -- Calls `VertxSliceServer.stop()` for each server. Drains in-flight requests.

3. **Stop Quartz scheduler** -- Calls `QuartzService.stop()`, which uses `AtomicBoolean` double-shutdown protection. `scheduler.shutdown(true)` waits for currently executing jobs to finish before returning. A JVM shutdown hook in `QuartzService` provides fallback scheduler shutdown if `stop()` is not called explicitly (e.g., if the application crashes before reaching this phase).

4. **Stop ConfigWatchService** -- Closes the file system watcher for configuration hot-reload.

5. **Shutdown BlockedThreadDiagnostics** -- Stops the blocked-thread detection background task.

6. **Close Settings** -- Calls `Settings.close()`, which releases storage resources (S3AsyncClient, database connections, Valkey connections, etc.). This flushes any remaining buffered metadata events to the database.

7. **Shutdown StorageExecutors** -- Calls `StorageExecutors.shutdown()`, which performs `shutdown()` followed by `awaitTermination(5, SECONDS)` for each of the three pools (READ, WRITE, LIST). If a pool does not terminate within 5 seconds, `shutdownNow()` is called to force-interrupt remaining tasks.

8. **Close Vert.x instance** -- Called last. Closes event loops and worker threads. This must be last because earlier shutdown phases may need the Vert.x event loop to complete async operations.

Each phase catches and logs exceptions independently so that a failure in one phase does not prevent subsequent phases from executing.

---

## Health Check Architecture

`HealthSlice` serves the `/.health` endpoint and probes 5 infrastructure components in parallel. It returns JSON with per-component status and latency, enabling load balancers and monitoring systems to make routing decisions.

### Components Probed

| Component | Probe Method | Timeout | Healthy Condition |
|-----------|-------------|---------|-------------------|
| **Storage** | `storage.list(Key.ROOT)` | 5 seconds | List completes without error |
| **Database** | `connection.isValid(5)` via HikariCP | 5 seconds | Connection is valid |
| **Valkey** | `ValkeyConnection.pingAsync()` via `Supplier<CompletableFuture<Boolean>>` | 5 seconds | Ping returns true |
| **Quartz** | `QuartzService.isRunning()` | Immediate | `isStarted() && !isShutdown() && !isInStandbyMode()` |
| **HTTP Client** | `JettyClientSlices.isOperational()` via `Supplier<Boolean>` | Immediate | Client reports operational |

### Status Logic

- **healthy** (HTTP 200): All 5 components report OK (or `not_configured` for optional components).
- **degraded** (HTTP 200): Exactly one non-storage component is down. The system is still operational.
- **unhealthy** (HTTP 503): Storage is down, or more than one component is down. Load balancers should stop routing traffic.

Components that are not configured (e.g., Valkey not enabled, Quartz not started) report `not_configured` and are not counted as down.

### Response Format

```json
{
  "status": "healthy",
  "components": {
    "storage": { "status": "ok", "latency_ms": 12 },
    "database": { "status": "ok", "latency_ms": 3 },
    "valkey": { "status": "ok", "latency_ms": 1 },
    "quartz": { "status": "ok", "latency_ms": 0 },
    "http_client": { "status": "ok", "latency_ms": 0 }
  }
}
```

### Dependency Injection

`HealthSlice` accepts probe dependencies through its constructor. The `withServices()` factory method converts concrete service objects into the functional interfaces:

- `ValkeyConnection` -> `Supplier<CompletableFuture<Boolean>>` (via `vc::pingAsync`)
- `QuartzService` -> `Supplier<Boolean>` (via `qs::isRunning`)
- `JettyClientSlices` -> `Supplier<Boolean>` (via `isOperational()`)

This design makes `HealthSlice` fully testable with mock suppliers.

---

## Development Environment Setup

### Prerequisites

- **JDK 21+** (OpenJDK recommended)
- **Maven 3.2+**
- **Docker** (for integration tests)
- **Git**

### Clone and Build

```bash
# Clone repository
git clone https://github.com/artipie/artipie.git
cd artipie

# Full build with tests
mvn clean verify

# Fast build (skip tests)
mvn install -DskipTests -Dpmd.skip=true

# Multi-threaded build
mvn clean install -U -DskipTests -T 1C
```

### IDE Setup

#### IntelliJ IDEA

1. Open project (`File -> Open -> pom.xml`)
2. Import as Maven project
3. Configure run configuration:
   - **Main class**: `com.artipie.VertxMain`
   - **VM options**: `--config-file=/path/to/artipie.yaml`
   - **Working directory**: `artipie-main`

#### VS Code

1. Install Java Extension Pack
2. Open project folder
3. Configure launch.json:

```json
{
  "type": "java",
  "name": "VertxMain",
  "request": "launch",
  "mainClass": "com.artipie.VertxMain",
  "args": "--config-file=example/artipie.yaml"
}
```

### Running Locally

**Option 1: Docker Compose (Recommended)**
```bash
cd artipie-main/docker-compose
docker-compose up -d
```

**Option 2: Direct Execution**
```bash
java -jar artipie-main/target/artipie.jar \
  --config-file=example/artipie.yaml \
  --port=8080 \
  --api-port=8086
```

---

## Build System

### Maven Profiles

| Profile | Description |
|---------|-------------|
| `docker-build` | Build Docker images (auto-enabled if Docker socket exists) |
| `sonatype` | Deploy to Maven Central |
| `gpg-sign` | GPG sign artifacts for release |
| `bench` | Run benchmarks |
| `itcase` | Integration test cases |

### Common Build Commands

```bash
# Full build with all tests
mvn clean verify

# Unit tests only
mvn test

# Integration tests
mvn verify -Pitcase

# Build specific module
mvn clean install -pl maven-adapter

# Skip tests and PMD
mvn install -DskipTests -Dpmd.skip=true

# Run specific test class
mvn test -Dtest=LargeArtifactPerformanceIT -DskipITs=false

# Package with dependencies
mvn package dependency:copy-dependencies

# Extract project version
mvn help:evaluate -Dexpression=project.version -q -DforceStdout
```

### Versioning

```bash
# Bump version across all modules
./bump-version.sh 1.21.0

# Build and deploy to local Docker
./build-and-deploy.sh

# Build and deploy with tests
./build-and-deploy.sh --with-tests
```

---

## Project Structure

### Module Overview

```
artipie/
+-- pom.xml                          # Parent POM
|
+-- artipie-main/                    # Main application
|   +-- src/main/java/com/artipie/
|   |   +-- VertxMain.java          # Entry point
|   |   +-- api/                    # REST API handlers
|   |   +-- auth/                   # Authentication
|   |   +-- cache/                  # Cache invalidation (CacheInvalidationPubSub)
|   |   +-- cluster/               # Cluster coordination (DbNodeRegistry)
|   |   +-- cooldown/              # Cooldown service
|   |   +-- db/                    # Database layer (ArtifactDbFactory, DbConsumer)
|   |   +-- index/                 # Artifact index (DbArtifactIndex)
|   |   +-- http/                  # HTTP handlers (HealthSlice)
|   |   +-- scheduling/            # Quartz scheduling (QuartzService)
|   |   +-- settings/              # Configuration
|   |   +-- diagnostics/           # Thread diagnostics
|   +-- docker-compose/             # Production deployment
|
+-- artipie-core/                    # Core types and HTTP layer
|   +-- src/main/java/com/artipie/
|       +-- cache/                 # Cache config (NegativeCacheConfig, CacheInvalidationPubSub)
|       +-- cluster/               # Cluster event bus (ClusterEventBus)
|       +-- http/                  # Slice pattern, HTTP utilities
|       |   +-- cache/             # Proxy cache (BaseCachedProxySlice, RequestDeduplicator, NegativeCache)
|       |   +-- misc/              # StorageExecutors, ConfigDefaults
|       +-- auth/                  # Auth abstractions
|       +-- settings/              # Settings interfaces
|
+-- vertx-server/                    # Vert.x HTTP server wrapper
+-- http-client/                     # HTTP client utilities
|
+-- asto/                            # Abstract storage
|   +-- asto-core/                  # Storage interfaces
|   +-- asto-s3/                    # S3 implementation (DiskCacheStorage)
|   +-- asto-vertx-file/            # Async filesystem
|   +-- asto-redis/                 # Redis implementation
|   +-- asto-etcd/                  # etcd implementation
|
+-- [16 Adapter Modules]
|   +-- maven-adapter/
|   +-- npm-adapter/
|   +-- docker-adapter/
|   +-- pypi-adapter/
|   +-- gradle-adapter/
|   +-- go-adapter/
|   +-- helm-adapter/
|   +-- composer-adapter/
|   +-- gem-adapter/
|   +-- nuget-adapter/
|   +-- debian-adapter/
|   +-- rpm-adapter/
|   +-- hexpm-adapter/
|   +-- conan-adapter/
|   +-- conda-adapter/
|   +-- files-adapter/
|
+-- roadster/                        # Rust rewrite (next-gen)
+-- artipie-import-cli/              # Rust import tool
|
+-- docs/                            # Documentation
```

### Key Files

| File | Description |
|------|-------------|
| `artipie-main/.../VertxMain.java` | Application entry point and shutdown sequence |
| `artipie-main/.../api/RestApi.java` | REST API verticle |
| `artipie-main/.../db/ArtifactDbFactory.java` | HikariCP pool creation and schema migrations |
| `artipie-main/.../db/DbConsumer.java` | Batch event processor with dead-letter queue |
| `artipie-main/.../index/DbArtifactIndex.java` | PostgreSQL full-text search index |
| `artipie-main/.../cluster/DbNodeRegistry.java` | PostgreSQL-backed node registry |
| `artipie-main/.../http/HealthSlice.java` | 5-component health check endpoint |
| `artipie-main/.../scheduling/QuartzService.java` | Quartz scheduler (RAM and JDBC modes) |
| `artipie-core/.../cluster/ClusterEventBus.java` | Valkey pub/sub event bus |
| `artipie-core/.../cache/CacheInvalidationPubSub.java` | Cross-instance cache invalidation |
| `artipie-core/.../http/cache/BaseCachedProxySlice.java` | 7-step proxy caching pipeline |
| `artipie-core/.../http/cache/RequestDeduplicator.java` | Concurrent request coalescing |
| `artipie-core/.../http/cache/NegativeCache.java` | Two-tier L1+L2 negative cache |
| `artipie-core/.../http/misc/StorageExecutors.java` | Named I/O thread pools |
| `artipie-core/.../http/Slice.java` | Core HTTP handler interface |
| `asto/asto-core/.../Storage.java` | Storage interface |
| `asto/asto-s3/.../DiskCacheStorage.java` | LRU/LFU disk cache for S3 proxy |

---

## Core Concepts

### 1. The Slice Pattern

The **Slice** is the fundamental HTTP handler abstraction:

```java
public interface Slice {
    /**
     * Process HTTP request and return response.
     *
     * @param line Request line (method, URI, version)
     * @param headers Request headers
     * @param body Request body as reactive stream
     * @return CompletableFuture of Response
     */
    CompletableFuture<Response> response(
        RequestLine line,
        Headers headers,
        Content body
    );
}
```

**Benefits:**
- Composable via decorators
- Async/non-blocking by design
- Easy to test in isolation

**Decorator Pattern:**
```java
// Wrap with timeout, logging, and metrics
Slice wrapped = new LoggingSlice(
    new TimeoutSlice(
        new MetricsSlice(
            new MySlice(storage)
        ),
        Duration.ofSeconds(120)
    )
);
```

### 2. Storage Abstraction (Asto)

All storage backends implement the `Storage` interface:

```java
public interface Storage {
    CompletableFuture<Boolean> exists(Key key);
    CompletableFuture<Collection<Key>> list(Key prefix);
    CompletableFuture<Void> save(Key key, Content content);
    CompletableFuture<Content> value(Key key);
    CompletableFuture<Void> move(Key source, Key destination);
    CompletableFuture<Void> delete(Key key);
    <T> CompletableFuture<T> exclusively(Key key, Function<Storage, CompletableFuture<T>> operation);
}
```

**Key Design Principles:**
- All operations return `CompletableFuture` for async execution
- `Key` represents path-like identifiers (e.g., `org/example/artifact/1.0.0/file.jar`)
- `Content` is a reactive byte stream with optional metadata
- `DispatchedStorage` wraps any `Storage` to route read/write/list operations to separate `StorageExecutors` pools

### 3. Repository Types

**Local Repository:**
- Hosts artifacts directly in storage
- Supports read and write operations
- Example: `MavenSlice`, `NpmSlice`

**Proxy Repository:**
- Caches artifacts from upstream registries
- Uses `BaseCachedProxySlice` pipeline (negative cache -> disk cache -> dedup -> upstream -> cache)
- Read-only (downloads from upstream)
- Example: `MavenProxySlice`, `NpmProxySlice`

**Group Repository:**
- Aggregates multiple repositories
- Uses artifact index for targeted member lookups (O(1) via Lucene or PostgreSQL)
- Falls back to parallel fan-out when index is cold
- Example: `GroupSlice`

### 4. Async/Reactive Programming

Artipie uses `CompletableFuture` for all async operations:

```java
// Chaining operations
storage.exists(key)
    .thenCompose(exists -> {
        if (exists) {
            return storage.value(key);
        }
        return CompletableFuture.completedFuture(null);
    })
    .thenApply(content -> processContent(content));

// Error handling
future.exceptionally(error -> {
    logger.error("Operation failed", error);
    return defaultValue;
});

// Parallel operations
CompletableFuture.allOf(
    storage.exists(key1),
    storage.exists(key2),
    storage.exists(key3)
).thenApply(v -> "All complete");
```

**Critical Rules:**
- Never block on Vert.x event loop threads
- Use `thenCompose()` for chaining async operations
- Use `thenApply()` for synchronous transformations
- Always handle exceptions with `exceptionally()` or `handle()`

### 5. Configuration System

Configuration is loaded from YAML files:

```java
// Settings interface
public interface Settings {
    Storage storage();
    Authentication authentication();
    Policy policy();
    Optional<MetricsConfig> metrics();
    // ...
}

// Repository configuration
public interface RepoConfig {
    String type();
    Storage storage();
    Optional<List<String>> remotes();
    // ...
}
```

**Hot Reload:**
- `ConfigWatchService` monitors configuration files
- Changes apply without restart
- Slice cache is invalidated on config change
- In HA deployments, `CacheInvalidationPubSub` propagates config changes across instances

---

## Adding New Features

### Adding a New Repository Adapter

1. **Create Maven Module**

```xml
<!-- Add to root pom.xml -->
<module>myformat-adapter</module>
```

```xml
<!-- myformat-adapter/pom.xml -->
<artifactId>myformat-adapter</artifactId>
<dependencies>
    <dependency>
        <groupId>com.artipie</groupId>
        <artifactId>artipie-core</artifactId>
    </dependency>
</dependencies>
```

2. **Implement Slice**

```java
public final class MyFormatSlice implements Slice {
    private final Storage storage;

    public MyFormatSlice(Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line,
        Headers headers,
        Content body
    ) {
        // Handle GET requests
        if (line.method().equals("GET")) {
            return handleGet(line.uri());
        }
        // Handle PUT requests
        if (line.method().equals("PUT")) {
            return handlePut(line.uri(), body);
        }
        return CompletableFuture.completedFuture(
            new RsWithStatus(RsStatus.METHOD_NOT_ALLOWED)
        );
    }

    private CompletableFuture<Response> handleGet(String uri) {
        Key key = new Key.From(uri);
        return storage.value(key)
            .thenApply(content -> new RsWithBody(content))
            .exceptionally(ex -> new RsWithStatus(RsStatus.NOT_FOUND));
    }
}
```

3. **Register in RepositorySlices**

```java
// In RepositorySlices.java slice() method
case "myformat":
    return new MyFormatSlice(storage);
case "myformat-proxy":
    return new MyFormatProxySlice(storage, remotes);
```

4. **Add Tests**

```java
class MyFormatSliceTest {
    @Test
    void shouldReturnArtifact() {
        Storage storage = new InMemoryStorage();
        storage.save(new Key.From("file.txt"), new Content.From("hello"));

        Slice slice = new MyFormatSlice(storage);

        Response response = slice.response(
            new RequestLine("GET", "/file.txt"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response.status(),
            new IsEquals<>(RsStatus.OK)
        );
    }
}
```

### Adding a New API Endpoint

1. **Create Handler Class**

```java
public final class MyNewRest {
    private final Settings settings;

    public MyNewRest(Settings settings) {
        this.settings = settings;
    }

    public void init(Router router, JWTAuth auth) {
        router.get("/api/v1/mynew/:id")
            .handler(JWTAuthHandler.create(auth))
            .handler(this::handleGet);

        router.post("/api/v1/mynew")
            .handler(JWTAuthHandler.create(auth))
            .handler(this::handlePost);
    }

    private void handleGet(RoutingContext ctx) {
        String id = ctx.pathParam("id");
        // Implementation
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject.mapFrom(result).encode());
    }
}
```

2. **Register in RestApi**

```java
// In RestApi.java start() method
new MyNewRest(settings).init(router, auth);
```

3. **Update OpenAPI Documentation**

Add endpoint specification to Swagger/OpenAPI resources.

### Adding a New Storage Backend

1. **Create Module in asto/**

```java
public final class MyStorage implements Storage {

    @Override
    public CompletableFuture<Boolean> exists(Key key) {
        return CompletableFuture.supplyAsync(() -> {
            // Check if key exists
            return myClient.exists(key.string());
        });
    }

    @Override
    public CompletableFuture<Content> value(Key key) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] data = myClient.get(key.string());
            return new Content.From(data);
        });
    }

    // Implement other methods...
}
```

2. **Create Factory**

```java
public final class MyStorageFactory implements StorageFactory {
    @Override
    public Storage create(Config config) {
        String endpoint = config.string("endpoint");
        return new MyStorage(endpoint);
    }
}
```

3. **Register in Storage Configuration**

Update `StorageFactory` to recognize new storage type.

---

## Testing

### Test Categories

Artipie tests fall into several categories with different requirements:

| Category | Framework | External Dependencies | Run By Default |
|----------|-----------|----------------------|----------------|
| Unit tests | JUnit 5, Hamcrest | None | Yes (`mvn test`) |
| Integration tests | JUnit 5, Testcontainers | Docker | No (`mvn verify -Pitcase`) |
| Database tests | JUnit 5, Testcontainers PostgreSQL | Docker | Yes (auto-skip if Docker unavailable) |
| Valkey tests | JUnit 5 | Running Valkey instance | No (gated by `VALKEY_HOST` env var) |
| Performance tests | JUnit 5 | Varies | No (`-DskipITs=false`) |

### Unit Tests

```java
class MySliceTest {

    @Test
    void shouldReturnNotFoundForMissingKey() {
        Storage storage = new InMemoryStorage();
        Slice slice = new MySlice(storage);

        Response response = slice.response(
            new RequestLine("GET", "/missing.txt"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            response.status(),
            new IsEquals<>(RsStatus.NOT_FOUND)
        );
    }
}
```

### Database Tests

Database integration tests use Testcontainers PostgreSQL to spin up a real database for each test class. Key test classes:

- **`DbNodeRegistryTest`** -- Tests node registration, heartbeat, liveness detection, stale eviction, and deregistration against a real PostgreSQL instance.
- **`DbArtifactIndexTest`** -- Tests full-text search (tsvector path and LIKE fallback), index/remove operations, batch indexing, locate queries, and stats reporting.
- **`ArtifactDbTest`** / **`ArtifactDbFactory` tests** -- Tests schema creation, migration idempotency, HikariCP pool configuration, and environment variable resolution.
- **`DbConsumerTest`** -- Tests batch processing, UPSERT semantics, dead-letter behavior on repeated failures, and event sorting for deadlock prevention.

```java
@Testcontainers
class DbNodeRegistryTest {

    @Container
    private static final PostgreSQLContainer<?> PG =
        new PostgreSQLContainer<>("postgres:15-alpine");

    @Test
    void registersAndFindsLiveNode() throws Exception {
        final DataSource ds = createDataSource(PG);
        final DbNodeRegistry registry = new DbNodeRegistry(ds);
        registry.createTable();
        registry.register(new NodeRegistry.NodeInfo("node-1", "host-1", Instant.now(), Instant.now()));
        registry.heartbeat("node-1");
        MatcherAssert.assertThat(
            registry.liveNodes(60_000).size(),
            new IsEquals<>(1)
        );
    }
}
```

### Valkey Integration Tests

Valkey-dependent tests are gated by the `VALKEY_HOST` environment variable. When the variable is not set, these tests skip gracefully without failure. This allows the CI pipeline to run a full build without requiring a Valkey instance.

- **`ClusterEventBusTest`** -- Tests pub/sub event publishing, topic subscription, self-message filtering, and multi-handler dispatch.
- **`CacheInvalidationPubSubTest`** -- Tests remote invalidation of registered caches, invalidateAll, and self-message filtering.
- **`NegativeCacheTest`** (L2 path) -- Tests L2 Valkey read/write, L2-to-L1 promotion, and SCAN-based bulk invalidation.

Pattern for environment-gated tests:

```java
@BeforeAll
static void checkValkey() {
    Assumptions.assumeTrue(
        System.getenv("VALKEY_HOST") != null,
        "VALKEY_HOST not set, skipping Valkey integration tests"
    );
}
```

### Health Check Tests

`HealthSliceTest` contains 21 test methods covering all component combinations and failure scenarios. Tests use mock `Supplier` instances and a `FakeConnection` inner class that implements `java.sql.Connection` for database probe mocking without requiring a real database.

Test scenarios include:
- All components healthy -> HTTP 200, `"status": "healthy"`
- Storage down -> HTTP 503, `"status": "unhealthy"` (storage is critical)
- Single non-storage component down -> HTTP 200, `"status": "degraded"`
- Multiple components down -> HTTP 503, `"status": "unhealthy"`
- Components not configured -> Reported as `"not_configured"`, not counted as down
- Probe timeout scenarios -> Component reported as unhealthy with latency

### Temp File Cleanup Tests

Temp file cleanup tests use JUnit 5's `@TempDir` annotation for filesystem isolation. Each test creates temp files within the injected directory, runs cleanup logic, and verifies the expected files remain or are deleted.

### Integration Tests

```java
@Testcontainers
class MyAdapterIT {

    @Container
    private static final GenericContainer<?> ARTIPIE =
        new GenericContainer<>("artipie/artipie:1.0-SNAPSHOT")
            .withExposedPorts(8080);

    @Test
    void shouldUploadAndDownload() {
        String url = String.format(
            "http://localhost:%d/myrepo/file.txt",
            ARTIPIE.getMappedPort(8080)
        );

        // Upload
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .PUT(HttpRequest.BodyPublishers.ofString("hello"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

        // Download
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

        MatcherAssert.assertThat(
            response.body(),
            new IsEquals<>("hello")
        );
    }
}
```

### Running Tests

```bash
# All unit tests
mvn test

# Specific test class
mvn test -Dtest=MySliceTest

# Integration tests
mvn verify -Pitcase

# Module-specific tests
mvn test -pl maven-adapter

# Database tests with explicit PostgreSQL (Testcontainers handles this automatically)
mvn test -pl artipie-main -Dtest=DbNodeRegistryTest

# Valkey tests (requires running Valkey)
VALKEY_HOST=localhost mvn test -pl artipie-core -Dtest=ClusterEventBusTest
```

---

## Code Style & Standards

### PMD Enforcement

Code style is enforced by PMD Maven plugin. Build fails on violations.

```bash
# Check PMD rules
mvn pmd:check

# Skip PMD
mvn install -Dpmd.skip=true
```

### Hamcrest Matchers

Prefer matcher objects over static methods:

```java
// Good
MatcherAssert.assertThat(target, new IsEquals<>(expected));

// Bad
MatcherAssert.assertThat(target, Matchers.equalTo(expected));
```

### Test Assertions

Single assertion - no reason needed:
```java
MatcherAssert.assertThat(result, new IsEquals<>(expected));
```

Multiple assertions - add reasons:
```java
MatcherAssert.assertThat("Check status", response.status(), new IsEquals<>(200));
MatcherAssert.assertThat("Check body", response.body(), new IsEquals<>("hello"));
```

### Commit Messages

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

**Types:**
- `feat` - New feature
- `fix` - Bug fix
- `test` - Tests
- `refactor` - Code refactoring
- `docs` - Documentation
- `chore` - Maintenance
- `perf` - Performance
- `ci` - CI/CD changes
- `build` - Build system

**Example:**
```
feat(npm): add support for scoped packages

Implemented @scope/package-name handling in NPM adapter.
Added unit tests for scoped package resolution.

Close: #123
```

### Pull Request Format

**Title:** `<type>[scope]: <description>`

**Description:**
- Explain HOW the problem was solved
- Not just a copy of the title
- Include technical details

**Footer:**
- `Close: #123` - Closes issue
- `Fix: #123` - Fixes issue
- `Ref: #123` - References issue

---

## Debugging

### Enable Debug Logging

Edit `log4j2.xml`:
```xml
<Logger name="com.artipie" level="DEBUG"/>
<Logger name="com.artipie.maven" level="DEBUG"/>
<Logger name="software.amazon.awssdk" level="DEBUG"/>
```

### JVM Debug Flags

```bash
# Remote debugging
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 \
  -jar artipie.jar --config-file=artipie.yaml

# Heap dumps on OOM
-XX:+HeapDumpOnOutOfMemoryError \
-XX:HeapDumpPath=/var/artipie/logs/heapdump.hprof

# GC logging
-Xlog:gc*:file=/var/artipie/logs/gc.log:time,uptime:filecount=5,filesize=100m
```

### Common Issues

**Thread Blocking:**
- Symptom: Requests hang, CPU low
- Cause: Blocking call on event loop
- Fix: Use `executeBlocking()` or dispatch to `StorageExecutors` pools

**Memory Leaks:**
- Symptom: Heap grows continuously
- Cause: Unclosed Content streams, leaked CompletableFutures
- Fix: Always close Content, add timeouts

**Connection Pool Exhaustion:**
- Symptom: "Failed to acquire connection" (HikariCP or S3)
- Cause: Connections not returned to pool, or pool too small for load
- Fix: Check `ARTIPIE_DB_POOL_MAX` / `connection-max-idle-millis`, enable leak detection

**Dedup Zombie Entries:**
- Symptom: Requests for same artifact hang indefinitely
- Cause: Upstream fetch completed with exception but entry not cleaned up
- Fix: Check `ARTIPIE_DEDUP_MAX_AGE_MS` (default 5 min). Zombie entries are evicted automatically.

**Negative Cache Staleness:**
- Symptom: Artifact exists upstream but Artipie returns 404
- Cause: Artifact was previously 404 and is cached in negative cache
- Fix: Wait for TTL expiry, or call negative cache `invalidate()` / `clear()`

### Tools

```bash
# Thread dump
jstack <PID>

# Heap dump
jmap -dump:live,format=b,file=heap.hprof <PID>

# Monitor GC
jstat -gc <PID> 1000

# JFR recording
java -XX:StartFlightRecording=filename=recording.jfr ...

# VisualVM
visualvm --openjmx localhost:9010
```

---

## Contributing

### Workflow

1. Fork the repository
2. Create feature branch: `git checkout -b feat/my-feature`
3. Make changes
4. Run full build: `mvn clean verify`
5. Commit with conventional message
6. Push and create PR

### PR Checklist

- [ ] Code compiles without errors
- [ ] All tests pass
- [ ] PMD checks pass
- [ ] New code has tests
- [ ] Commit messages follow convention
- [ ] PR description explains changes
- [ ] Issue reference in footer

### Review Process

1. Author creates PR
2. CI checks run automatically
3. Reviewer is assigned
4. Review comments addressed
5. Maintainer approves and merges

---

## Roadster (Rust) Development

Roadster is the next-generation Artipie rewrite in Rust.

### Why Rust?

- **Zero GC pauses** (critical production issue)
- **< 100ms startup** (vs ~5s for Java)
- **< 100MB memory** (vs ~500MB for Java)
- **< 50MB Docker image** (vs ~500MB for Java)

### Project Structure

```
roadster/
+-- crates/
|   +-- roadster-core/         # Core types
|   +-- roadster-http/         # HTTP layer
|   +-- roadster-storage/      # Storage backends
|   +-- roadster-auth/         # Authentication
|   +-- roadster-config/       # Configuration
|   +-- roadster-telemetry/    # Observability
|   +-- adapters/              # 16 repository adapters
+-- bins/
|   +-- roadster-server/       # Main binary
|   +-- roadster-cli/          # CLI tool
+-- docs/
```

### Build Commands

```bash
cd roadster

# Check compilation
cargo check --workspace

# Build
cargo build --workspace

# Release build
cargo build --release

# Run tests
cargo test --workspace

# Format
cargo fmt --all

# Lint
cargo clippy --workspace --all-targets -- -D warnings

# Documentation
cargo doc --no-deps --workspace --open
```

### Core Patterns

**Slice Pattern (Rust):**
```rust
#[async_trait]
pub trait Slice: Send + Sync {
    async fn response(
        &self,
        line: RequestLine,
        headers: Headers,
        body: Body,
        ctx: &RequestContext,
    ) -> Response;
}
```

**Storage Trait:**
```rust
#[async_trait]
pub trait Storage: Send + Sync {
    async fn exists(&self, key: &Key) -> StorageResult<bool>;
    async fn value(&self, key: &Key) -> StorageResult<Content>;
    async fn save(&self, key: &Key, content: Content) -> StorageResult<()>;
    async fn delete(&self, key: &Key) -> StorageResult<()>;
}
```

### Development Setup

```bash
# Install Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Install tools
rustup component add rustfmt clippy
cargo install cargo-watch cargo-audit

# Setup git hooks
git config core.hooksPath roadster/.githooks

# Auto-rebuild on changes
cargo watch -x check -x test
```

### Documentation

- [QUICKSTART.md](../roadster/QUICKSTART.md) - Quick reference
- [CODING_STANDARDS.md](../roadster/CODING_STANDARDS.md) - Code style
- [CONTRIBUTING.md](../roadster/CONTRIBUTING.md) - Contribution guide
- [AGENTS-ROADSTER.md](../roadster/AGENTS-ROADSTER.md) - Architecture guide

---

## Appendix A: Key Classes Reference

| Class | Location | Description |
|-------|----------|-------------|
| `VertxMain` | artipie-main | Application entry point and shutdown sequence |
| `RestApi` | artipie-main/api | REST API verticle |
| `MainSlice` | artipie-core/http | Main request router |
| `RepositorySlices` | artipie-main | Repository slice factory |
| `Slice` | artipie-core/http | Core HTTP handler interface |
| `Storage` | asto-core | Storage interface |
| `S3Storage` | asto-s3 | S3 storage implementation |
| `DiskCacheStorage` | asto-s3 | LRU/LFU disk cache for S3 |
| `Settings` | artipie-core/settings | Configuration interface |
| `Authentication` | artipie-core/auth | Auth interface |
| `Policy` | artipie-core/auth | Authorization interface |
| `ArtifactDbFactory` | artipie-main/db | HikariCP pool + schema migrations |
| `DbConsumer` | artipie-main/db | Batch metadata event processor |
| `DbArtifactIndex` | artipie-main/index | PostgreSQL full-text search |
| `LuceneArtifactIndex` | artipie-main/index | In-memory Lucene index |
| `DbNodeRegistry` | artipie-main/cluster | PostgreSQL node registry |
| `ClusterEventBus` | artipie-core/cluster | Valkey pub/sub event bus |
| `CacheInvalidationPubSub` | artipie-core/cache | Cross-instance cache invalidation |
| `BaseCachedProxySlice` | artipie-core/http/cache | 7-step proxy cache pipeline |
| `RequestDeduplicator` | artipie-core/http/cache | Request coalescing |
| `NegativeCache` | artipie-core/http/cache | Two-tier 404 cache |
| `StorageExecutors` | artipie-core/http/misc | Named I/O thread pools |
| `QuartzService` | artipie-main/scheduling | Quartz scheduler (RAM + JDBC) |
| `HealthSlice` | artipie-main/http | 5-component health check |

---

## Appendix B: Maven Module Dependencies

```
artipie-main
+-- artipie-core
+-- vertx-server
+-- http-client
+-- asto-core
+-- asto-s3
+-- asto-vertx-file
+-- maven-adapter
+-- npm-adapter
+-- docker-adapter
+-- ... (other adapters)

artipie-core
+-- asto-core
+-- http-client

asto-s3
+-- asto-core

*-adapter
+-- artipie-core
+-- asto-core
```

---

## Appendix C: Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `ARTIPIE_IO_READ_THREADS` | CPU x 4 | StorageExecutors READ pool size |
| `ARTIPIE_IO_WRITE_THREADS` | CPU x 2 | StorageExecutors WRITE pool size |
| `ARTIPIE_IO_LIST_THREADS` | CPU x 1 | StorageExecutors LIST pool size |
| `ARTIPIE_DB_POOL_MAX` | 50 | HikariCP maximum pool size |
| `ARTIPIE_DB_POOL_MIN` | 10 | HikariCP minimum idle connections |
| `ARTIPIE_DB_CONNECTION_TIMEOUT_MS` | 5000 | HikariCP connection acquisition timeout |
| `ARTIPIE_DB_IDLE_TIMEOUT_MS` | 600000 | HikariCP idle connection timeout |
| `ARTIPIE_DB_MAX_LIFETIME_MS` | 1800000 | HikariCP maximum connection lifetime |
| `ARTIPIE_DB_LEAK_DETECTION_MS` | 120000 | HikariCP leak detection threshold |
| `ARTIPIE_DB_BATCH_SIZE` | 50 | DbConsumer maximum events per batch |
| `ARTIPIE_DB_BUFFER_SECONDS` | 2 | DbConsumer buffer time window |
| `ARTIPIE_DEDUP_MAX_AGE_MS` | 300000 | RequestDeduplicator zombie entry eviction age |
| `VALKEY_HOST` | (none) | Enables Valkey integration tests when set |

---

## Appendix D: Useful Links

- **Repository**: https://github.com/artipie/artipie
- **Issues**: https://github.com/artipie/artipie/issues
- **Discussions**: https://github.com/artipie/artipie/discussions
- **Wiki**: https://github.com/artipie/artipie/wiki
- **Vert.x Docs**: https://vertx.io/docs/
- **Rust Book**: https://doc.rust-lang.org/book/

---

*This guide covers Artipie development for version 1.20.14. For the latest updates, see the repository.*
