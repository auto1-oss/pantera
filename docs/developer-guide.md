# Pantera Artifact Registry -- Developer Guide

**Version:** 2.0.0
**Maintained by:** Auto1 Group DevOps Team
**Repository:** [auto1-oss/pantera](https://github.com/auto1-oss/pantera)

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architecture Overview](#2-architecture-overview)
3. [Module Map](#3-module-map)
4. [Core Concepts](#4-core-concepts)
5. [Database Layer](#5-database-layer)
6. [Cache Architecture](#6-cache-architecture)
7. [Cluster Architecture](#7-cluster-architecture)
8. [Thread Model](#8-thread-model)
9. [Shutdown Sequence](#9-shutdown-sequence)
10. [Health Check Architecture](#10-health-check-architecture)
11. [Development Setup](#11-development-setup)
12. [Build System](#12-build-system)
13. [Adding Features](#13-adding-features)
14. [Testing](#14-testing)
15. [Debugging](#15-debugging)

---

## 1. Introduction

Pantera is a universal binary artifact registry supporting 15+ package formats. It serves as a local registry, a caching proxy, or a group that merges multiple upstream sources into a single endpoint.

### Tech Stack

| Component         | Technology              | Version   |
|-------------------|-------------------------|-----------|
| Language          | Java (Temurin JDK)      | 21+       |
| Build Tool        | Apache Maven            | 3.4+      |
| HTTP Server       | Eclipse Vert.x          | 4.5.22    |
| HTTP Client       | Eclipse Jetty           | 12.1.4    |
| JSON Processing   | Jackson                 | 2.17.3    |
| Database          | PostgreSQL + HikariCP   | 16+ / 5.x |
| Distributed Cache | Valkey (via Lettuce)     | 8.x       |
| Scheduling        | Quartz Scheduler        | 2.3.2     |
| Metrics           | Micrometer              | 1.12.13   |
| Logging           | Log4j 2                 | 2.24.3    |
| Testing           | JUnit 5                 | 5.10.0    |
| Test Containers   | TestContainers          | 2.0.2     |

### Supported Repository Types

Maven, Gradle (Maven-layout), Docker, NPM, PyPI, Composer (PHP), Helm, Go, Gem (Ruby), NuGet, Debian, RPM, Conda, Conan, Hex (Erlang/Elixir), and generic files.

---

## 2. Architecture Overview

### Layered Architecture

```
+-----------------------------------------------------------+
|                    HTTP Layer (Vert.x)                     |
|  VertxSliceServer  |  AsyncApiVerticle  |  MetricsVerticle |
+-----------------------------------------------------------+
                            |
+-----------------------------------------------------------+
|                  Routing & Auth Layer                      |
|  MainSlice -> DockerRoutingSlice -> AuthzSlice -> Filters  |
+-----------------------------------------------------------+
                            |
+-----------------------------------------------------------+
|                  Repository Adapters                       |
|  MavenSlice | DockerSlice | NpmSlice | PySlice | ...       |
|  (local)    | (proxy)     | (group)  |                    |
+-----------------------------------------------------------+
                            |
+-----------------------------------------------------------+
|                     Cache Layer                            |
|  BaseCachedProxySlice | NegativeCache | RequestDeduplicator|
|  DiskCacheStorage     | Caffeine L1   | Valkey L2          |
+-----------------------------------------------------------+
                            |
+-----------------------------------------------------------+
|                    Storage Layer                           |
|  DispatchedStorage -> FileStorage | S3Storage              |
|  StorageExecutors (READ / WRITE / LIST pools)              |
+-----------------------------------------------------------+
                            |
+-----------------------------------------------------------+
|                    Database Layer                          |
|  HikariCP -> PostgreSQL                                   |
|  DbConsumer (RxJava batch)  |  DbArtifactIndex (FTS)      |
|  Flyway migrations          |  Quartz JDBC tables          |
+-----------------------------------------------------------+
                            |
+-----------------------------------------------------------+
|                    Cluster Layer                           |
|  DbNodeRegistry  | ClusterEventBus (Valkey pub/sub)        |
|  CacheInvalidationPubSub | QuartzService (JDBC clustering) |
+-----------------------------------------------------------+
```

### Request Flow

1. **Vert.x event loop** receives the TCP connection and parses HTTP.
2. `VertxSliceServer` wraps the request into `RequestLine`, `Headers`, and `Content` (a reactive `Publisher<ByteBuffer>`).
3. `MainSlice` routes by path prefix to the correct repository `Slice`.
4. `RepositorySlices` lazily creates and caches per-repository slices. Each slice is wrapped with authentication, authorization, content-length limits, timeouts, and logging.
5. The adapter slice processes the request (e.g., `MavenSlice` for `PUT /com/example/...`).
6. Storage operations are dispatched to `StorageExecutors` thread pools via `DispatchedStorage`.
7. The `Response` flows back through the same chain; Vert.x writes the HTTP response.

---

## 3. Module Map

### Core Modules

| Module | Purpose |
|--------|---------|
| `pantera-main` | Application entry point (`VertxMain`), REST API (`AsyncApiVerticle`), database layer (`ArtifactDbFactory`, `DbConsumer`, `DbManager`), Quartz scheduling, repository wiring (`RepositorySlices`), Flyway migrations, health checks, metrics verticle. |
| `pantera-core` | Core types: `Slice` interface, `Storage` interface, `Key`, `Content`, `Headers`, `Response`, cache infrastructure (`BaseCachedProxySlice`, `NegativeCache`, `RequestDeduplicator`), `StorageExecutors`, `DispatchedStorage`, cluster event bus, security/auth framework. |
| `pantera-storage` | Parent module for storage implementations. Contains three sub-modules. |
| `pantera-storage-core` | `Storage` interface definition, `Key`, `Content`, `Meta`, `InMemoryStorage`, `BlockingStorage`, storage test verification harness. |
| `pantera-storage-vertx-file` | Filesystem storage using Vert.x NIO. |
| `pantera-storage-s3` | AWS S3 storage with `DiskCacheStorage` (LRU/LFU on-disk read-through cache with watermark eviction). |
| `vertx-server` | `VertxSliceServer` -- adapts a `Slice` into a Vert.x HTTP server handler. |
| `http-client` | Jetty-based HTTP client (`JettyClientSlices`) used by proxy adapters to fetch from upstream registries. |
| `pantera-backfill` | Standalone CLI tool (`BackfillCli`) for bulk re-indexing the `artifacts` database table from storage. |
| `pantera-import-cli` | Migration tool for importing artifacts from external registries into Pantera. |

### Repository Adapters

| Module | Format | Supports |
|--------|--------|----------|
| `maven-adapter` | Maven / Gradle | local, proxy, group |
| `docker-adapter` | Docker (OCI) | local, proxy, group |
| `npm-adapter` | NPM | local, proxy |
| `pypi-adapter` | PyPI | local, proxy |
| `composer-adapter` | Composer (PHP) | local, proxy, group |
| `helm-adapter` | Helm Charts | local |
| `go-adapter` | Go Modules | local, proxy |
| `gem-adapter` | RubyGems | local |
| `nuget-adapter` | NuGet (.NET) | local |
| `debian-adapter` | Debian (APT) | local |
| `rpm-adapter` | RPM (YUM/DNF) | local |
| `conda-adapter` | Conda | local |
| `conan-adapter` | Conan (C/C++) | local |
| `hexpm-adapter` | Hex (Elixir) | local |
| `files-adapter` | Generic Files | local, proxy |

### Support Modules

| Module | Purpose |
|--------|---------|
| `build-tools` | Shared Checkstyle configuration and build rules. |
| `benchmark` | JMH performance benchmarks for critical paths. |

---

## 4. Core Concepts

### 4.1 The Slice Pattern

Every repository adapter, middleware, and HTTP handler implements the `Slice` interface:

```java
// pantera-core: com.auto1.pantera.http.Slice
public interface Slice {
    CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    );
}
```

- **`RequestLine`** -- HTTP method, URI, and version.
- **`Headers`** -- Iterable of key-value header pairs.
- **`Content`** -- A reactive `Publisher<ByteBuffer>` representing the request body (zero-copy streaming).
- **`Response`** -- Status code, response headers, and body `Content`.

Slices compose via the decorator pattern. `Slice.Wrap` is a convenience base class:

```java
public abstract class Wrap implements Slice {
    private final Slice slice;
    protected Wrap(final Slice slice) { this.slice = slice; }

    @Override
    public final CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        return this.slice.response(line, headers, body);
    }
}
```

Common decorators: `LoggingSlice`, `TimeoutSlice`, `ContentLengthRestriction`, `FilterSlice`, `CombinedAuthzSliceWrap`, `PathPrefixStripSlice`.

### 4.2 Storage Interface

```java
// pantera-storage-core: com.auto1.pantera.asto.Storage
public interface Storage {
    CompletableFuture<Boolean> exists(Key key);
    CompletableFuture<Collection<Key>> list(Key prefix);
    CompletableFuture<ListResult> list(Key prefix, String delimiter); // hierarchical
    CompletableFuture<Void> save(Key key, Content content);
    CompletableFuture<Void> move(Key source, Key destination);
    CompletableFuture<Content> value(Key key);
    CompletableFuture<Void> delete(Key key);
    CompletableFuture<Void> deleteAll(Key prefix);
    CompletableFuture<? extends Meta> metadata(Key key);
    <T> CompletionStage<T> exclusively(Key key, Function<Storage, CompletionStage<T>> op);
}
```

Key implementations:
- `FileStorage` -- Vert.x NIO-backed filesystem.
- `S3Storage` -- AWS SDK v2 async S3 client.
- `InMemoryStorage` -- ConcurrentHashMap-backed, used in tests.
- `SubStorage` -- Scopes a storage to a key prefix.

### 4.3 DispatchedStorage

Wraps any `Storage` and dispatches completion continuations to dedicated thread pools:

```java
public final class DispatchedStorage implements Storage {
    // exists(), value(), metadata() -> StorageExecutors.READ
    // save(), move(), delete()      -> StorageExecutors.WRITE
    // list()                        -> StorageExecutors.LIST
    // exclusively()                 -> delegates directly (no dispatch)
}
```

This prevents slow write operations from starving fast reads.

### 4.4 Repository Types

Each repository in Pantera is one of three types:

- **local** -- Pantera is the authoritative source. Artifacts are uploaded directly and stored in the configured storage backend.
- **proxy** -- Pantera acts as a caching reverse proxy. Requests are served from cache when possible; cache misses are fetched from the upstream registry and cached.
- **group** -- Pantera merges multiple local and/or proxy repositories into a single logical endpoint. Requests are resolved by trying member repositories in order.

### 4.5 Async/Reactive Model

All I/O in Pantera is non-blocking. The codebase uses `CompletableFuture<T>` as the primary async primitive. Reactive streams (`Publisher<ByteBuffer>`) are used for streaming request and response bodies without buffering entire artifacts on the heap.

Adapter-internal code may use RxJava (`Flowable`) for stream transformation, but the public API boundary is always `CompletableFuture` and `Publisher`.

### 4.6 Configuration System

**Entry point:** `pantera.yml` (typically at `/etc/pantera/pantera.yml`).

```yaml
meta:
  storage:
    type: fs
    path: /var/pantera/data
  policy:
    type: artipie
    storage:
      type: fs
      path: /var/pantera/security
  artifacts_database:
    postgres_host: ${POSTGRES_HOST}
    postgres_port: 5432
    postgres_database: pantera
    postgres_user: ${POSTGRES_USER}
    postgres_password: ${POSTGRES_PASSWORD}
```

Key interfaces:

- **`Settings`** (`com.auto1.pantera.settings.Settings`) -- Top-level application settings. Provides config storage, authentication, caches, metrics context, JWT settings, HTTP client settings, and more. Implements `AutoCloseable`.
- **`RepoConfig`** (`com.auto1.pantera.settings.repo.RepoConfig`) -- Per-repository configuration parsed from YAML. Exposes repository type, port, upstream URL, storage settings, and adapter-specific options.
- **`SettingsFromPath`** -- Loads `Settings` from a YAML file path.

**Hot reload:** `ConfigWatchService` uses the Java NIO `WatchService` to detect changes to the configuration directory. When YAML files change, repository configurations are refreshed without restarting the server.

**Environment variable substitution:** Configuration values support `${VAR_NAME}` placeholders that are resolved from environment variables at load time.

---

## 5. Database Layer

### 5.1 ArtifactDbFactory

Location: `pantera-main/src/main/java/com/auto1/pantera/db/ArtifactDbFactory.java`

Creates and initializes the PostgreSQL artifacts database:

1. Reads connection parameters from the `artifacts_database` YAML section (with `${ENV_VAR}` substitution).
2. Creates a HikariCP connection pool with configurable sizing:
   - `pool_max_size` (default: 50, env: `PANTERA_DB_POOL_MAX`)
   - `pool_min_idle` (default: 10, env: `PANTERA_DB_POOL_MIN`)
   - Connection timeout: 5s (env: `PANTERA_DB_CONNECTION_TIMEOUT_MS`)
   - Leak detection: 300s (env: `PANTERA_DB_LEAK_DETECTION_MS`)
3. Creates database tables and indexes via DDL statements.
4. Integrates HikariCP metrics with Micrometer/Prometheus.

### 5.2 DbConsumer

Location: `pantera-main/src/main/java/com/auto1/pantera/db/DbConsumer.java`

Asynchronous batch event processor for artifact metadata:

- Uses RxJava `PublishSubject` with `.buffer(timeSeconds, TimeUnit.SECONDS, maxSize)` to batch events.
- Default: 2-second windows, 200 events per batch (env: `PANTERA_DB_BUFFER_SECONDS`, `PANTERA_DB_BATCH_SIZE`).
- Events are sorted by `(repo_name, name, version)` before processing to ensure consistent lock ordering and prevent deadlocks.
- Uses atomic `INSERT ... ON CONFLICT DO UPDATE` (UPSERT) for idempotent writes.
- **Dead-letter queue:** After 3 consecutive batch failures, events are written to `.dead-letter` files under `/var/pantera/.dead-letter/` with exponential backoff (1s, 2s, 4s, max 8s).

### 5.3 DbArtifactIndex

Location: `pantera-main/src/main/java/com/auto1/pantera/index/DbArtifactIndex.java`

PostgreSQL-backed full-text search for artifacts:

- Primary search uses `tsvector` column with `plainto_tsquery('simple', ?)` and GIN index.
- Prefix matching uses `to_tsquery('simple', ?)` with `:*` suffix.
- **LIKE fallback:** If tsvector returns zero results, falls back to `ILIKE '%term%'` for substring matching.
- Search tokens are auto-populated by the `trg_artifacts_search` trigger, which uses `translate()` to split dots, slashes, dashes, and underscores into separate searchable tokens.

### 5.4 Flyway Migrations

Location: `pantera-main/src/main/resources/db/migration/`

| Migration | Description |
|-----------|-------------|
| `V100__create_settings_tables.sql` | Creates `repositories`, `users`, `roles`, `user_roles`, `storage_aliases`, `auth_providers` tables. |
| `V101__create_user_tokens_table.sql` | Creates `user_tokens` table for API token management. |
| `V102__rename_artipie_auth_provider_to_local.sql` | Renames legacy auth provider values. |
| `V103__rename_artipie_nodes_to_pantera_nodes.sql` | Renames node registry table. |
| `V104__performance_indexes.sql` | Adds performance indexes identified by audit. |

Migrations are applied automatically by `DbManager.migrate(dataSource)` at startup.

### 5.5 Tables

| Table | Purpose |
|-------|---------|
| `artifacts` | Artifact metadata (repo_type, repo_name, name, version, size, dates, owner, search_tokens tsvector). |
| `artifact_cooldowns` | Cooldown/quarantine records for blocked artifacts. |
| `import_sessions` | Tracks bulk import progress with idempotency keys. |
| `pantera_nodes` | Cluster node registry with heartbeats and status. |
| `repositories` | Repository configurations (JSONB). |
| `users` | User accounts with auth provider references. |
| `roles` | RBAC role definitions (JSONB permissions). |
| `user_roles` | User-to-role mappings. |
| `user_tokens` | API tokens per user. |
| `storage_aliases` | Named storage configuration aliases. |
| `auth_providers` | Authentication provider configurations. |
| `QRTZ_*` | Quartz scheduler tables (12 tables for JDBC clustering). |

### 5.6 Key Indexes

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_artifacts_repo_lookup` | `(repo_name, name, version)` | Fast exact-match lookups. |
| `idx_artifacts_locate` | `(name, repo_name) INCLUDE (repo_type)` | Covering index for `locate()` (index-only scan). |
| `idx_artifacts_browse` | `(repo_name, name, version) INCLUDE (size, created_date, owner)` | Covering index for browse pagination. |
| `idx_artifacts_search` | `GIN(search_tokens)` | Full-text search via tsvector. |
| `idx_artifacts_name_trgm` | `GIN(name gin_trgm_ops)` | Trigram fuzzy search (requires `pg_trgm`). |
| `idx_artifacts_path_prefix` | `(path_prefix, repo_name) WHERE path_prefix IS NOT NULL` | Group repository resolution. |

---

## 6. Cache Architecture

### 6.1 BaseCachedProxySlice

Location: `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java`

Abstract base class implementing the shared proxy caching pipeline via template method pattern. All proxy adapters extend this class.

**7-step pipeline:**

1. **Negative cache check** -- Fast-fail on known 404s (L1 Caffeine lookup, sub-microsecond).
2. **Pre-process hook** -- Adapter-specific short-circuit (e.g., Maven metadata cache).
3. **Cacheability check** -- `isCacheable(path)` determines if the path should be cached.
4. **Cache-first lookup** -- Check local storage cache for a fresh hit (offline-safe).
5. **Cooldown evaluation** -- Block downloads of quarantined artifacts.
6. **Deduplicated upstream fetch** -- Only one in-flight request per artifact key.
7. **Cache storage with digest computation** -- Stream body to a temp file, compute digests (SHA-256, MD5), generate sidecar checksum files, enqueue artifact event, save to cache.

Adapters override hooks: `isCacheable()`, `buildCooldownRequest()`, `digestAlgorithms()`, `buildArtifactEvent()`, `postProcess()`, `generateSidecars()`.

### 6.2 RequestDeduplicator

Location: `pantera-core/src/main/java/com/auto1/pantera/http/cache/RequestDeduplicator.java`

Prevents thundering-herd problems when multiple clients request the same artifact simultaneously.

- Uses `ConcurrentHashMap<Key, InFlightEntry>` with `putIfAbsent` for lock-free coalescing.
- First request executes the upstream fetch; subsequent requests receive the same `CompletableFuture`.
- **Zombie protection:** A daemon thread (`dedup-cleanup`) runs every 60 seconds and evicts entries older than 5 minutes (env: `PANTERA_DEDUP_MAX_AGE_MS`). Evicted entries complete with `FetchSignal.ERROR`.
- Supports three strategies: `SIGNAL` (default, coalesce at future level), `STORAGE` (coalesce at storage level), `NONE` (no deduplication).
- `FetchSignal` enum: `SUCCESS`, `NOT_FOUND`, `ERROR`.

### 6.3 NegativeCache

Location: `pantera-core/src/main/java/com/auto1/pantera/http/cache/NegativeCache.java`

Two-tier cache for 404 (Not Found) responses:

**L1 -- Caffeine (in-process):**
- Default max size: 50,000 entries (~7.5 MB).
- Default TTL: 24 hours (configurable).
- Window TinyLFU eviction policy.
- `isNotFound()` checks only L1 for non-blocking fast path.

**L2 -- Valkey (shared across instances):**
- Enabled when a Valkey connection is configured.
- Keys namespaced by repo type and name: `negative:{repoType}:{repoName}:{key}`.
- `SETEX` with configurable TTL.
- L2 lookups are async with 100ms timeout.
- On L2 hit, entry is promoted to L1.
- Bulk invalidation via `SCAN` + `DEL` (avoids blocking `KEYS`).

### 6.4 DiskCacheStorage

Location: `pantera-storage/pantera-storage-s3/src/main/java/com/auto1/pantera/asto/s3/DiskCacheStorage.java`

Read-through on-disk cache for S3 storage:

- **Streams data to caller while persisting to disk** -- avoids full buffering.
- **ETag/size validation** -- configurable cache entry validation against remote metadata.
- **Eviction policies:** LRU (least recently used) or LFU (least frequently used).
- **Watermark-based cleanup:** High watermark (default 90%) triggers eviction down to low watermark (default 70%).
- **Striped locks** (256 stripes) for concurrent metadata updates without `String.intern()`.
- **Shared cleanup executor** -- bounded `ScheduledExecutorService` prevents thread proliferation across multiple cache instances.

---

## 7. Cluster Architecture

### 7.1 DbNodeRegistry

Location: `pantera-main/src/main/java/com/auto1/pantera/cluster/DbNodeRegistry.java`

PostgreSQL-backed node registry for HA clustering:

- Each node registers on startup with a unique `node_id`, hostname, port, and timestamp.
- Periodic heartbeats update `last_heartbeat`.
- Nodes missing heartbeats are marked as dead.
- Schema: `pantera_nodes(node_id, hostname, port, started_at, last_heartbeat, status)`.

### 7.2 ClusterEventBus

Location: `pantera-core/src/main/java/com/auto1/pantera/cluster/ClusterEventBus.java`

Cross-instance event bus using Valkey pub/sub:

- Channel naming: `pantera:events:{topic}`.
- Message format: `{instanceId}|{payload}`.
- **Self-message filtering:** Each instance generates a UUID on startup. Messages from the local instance are ignored to avoid double-processing.
- Uses separate Lettuce connections for subscribe and publish (required by Redis pub/sub spec).
- Handler registration: `ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<String>>>`.

### 7.3 CacheInvalidationPubSub

Location: `pantera-core/src/main/java/com/auto1/pantera/cache/CacheInvalidationPubSub.java`

Cross-instance Caffeine cache invalidation:

- Channel: `pantera:cache:invalidate`.
- Message format: `{instanceId}|{cacheType}|{key}` (or `*` for invalidateAll).
- When instance A modifies data, it publishes an invalidation message. All other instances invalidate their local Caffeine caches for that key.
- Registered caches implement `Cleanable<String>` interface.
- Self-published messages are filtered by instanceId comparison.

### 7.4 QuartzService

Location: `pantera-main/src/main/java/com/auto1/pantera/scheduling/QuartzService.java`

**Two modes:**

| Mode | Constructor | Job Store | Use Case |
|------|------------|-----------|----------|
| **RAM** | `QuartzService()` | `RAMJobStore` (in-memory) | Single-instance deployments. |
| **JDBC** | `QuartzService(DataSource)` | `JobStoreTX` (PostgreSQL) | Multi-instance HA deployments. |

JDBC mode:
- Creates `QRTZ_*` schema tables if they do not exist.
- Registers a `PanteraQuartzConnectionProvider` wrapping the HikariCP `DataSource`.
- Uses PostgreSQL delegate with clustering enabled.
- Scheduler name: `PanteraScheduler` (shared across all clustered nodes).
- Stale jobs from previous runs are cleared on startup (job data is in-memory only).

---

## 8. Thread Model

### Named Thread Pools

| Pool | Thread Name Pattern | Size | Purpose |
|------|-------------------|------|---------|
| **StorageExecutors.READ** | `pantera-io-read-%d` | CPU x 4 (env: `PANTERA_IO_READ_THREADS`) | Storage reads: `exists()`, `value()`, `metadata()`. |
| **StorageExecutors.WRITE** | `pantera-io-write-%d` | CPU x 2 (env: `PANTERA_IO_WRITE_THREADS`) | Storage writes: `save()`, `move()`, `delete()`. |
| **StorageExecutors.LIST** | `pantera-io-list-%d` | CPU x 1 (env: `PANTERA_IO_LIST_THREADS`) | Storage listings: `list()`. |
| **Vert.x event loop** | `vert.x-eventloop-thread-*` | CPU x 2 | HTTP request parsing, routing, response writing. Non-blocking only. |
| **Vert.x worker pool** | `vert.x-worker-thread-*` | max(20, CPU x 4) | Blocking operations via `executeBlocking()`. |
| **Quartz** | `PanteraScheduler_Worker-*` | 10 (Quartz default) | Scheduled job execution (cleanup, backfill, cron scripts). |
| **Dedup cleanup** | `dedup-cleanup` | 1 (daemon) | Periodic eviction of zombie dedup entries (every 60s). |
| **DiskCache cleaner** | `pantera.asto.s3.cache.cleaner` | max(2, CPU / 4) (daemon) | Watermark-based disk cache eviction. |
| **Metrics scraper** | `metrics-scraper` | 2 (Vert.x worker) | Prometheus metrics scraping (off event loop). |
| **DB artifact index** | Internal to `DbArtifactIndex` | Dedicated `ExecutorService` | Async database queries for artifact search. |

### Event Loop Safety

The Vert.x event loop must never be blocked. Operations that perform I/O (storage, database, upstream HTTP) are dispatched to dedicated pools. The event loop handles only:
- HTTP request/response framing
- Routing decisions
- Future composition (`.thenCompose()`, `.thenApply()`)

Blocked thread detection: `BlockedThreadDiagnostics` is initialized at startup. Vert.x warns if the event loop is blocked for more than 5 seconds or a worker thread for more than 120 seconds.

---

## 9. Shutdown Sequence

`VertxMain.stop()` performs an ordered shutdown to prevent resource leaks and ensure in-flight requests complete:

| Phase | Action | Why |
|-------|--------|-----|
| 1 | Stop HTTP/3 servers (Jetty) | Stop accepting new connections. |
| 2 | Stop HTTP/1.1+2 servers (Vert.x) | Drain in-flight HTTP requests. |
| 3 | Stop QuartzService | Halt scheduled jobs; prevent new job execution. |
| 4 | Close ConfigWatchService | Stop filesystem watchers. |
| 5 | Shutdown BlockedThreadDiagnostics | Clean up diagnostic monitoring threads. |
| 6 | Close Settings | Releases storage resources (S3AsyncClient connections, file handles). |
| 7 | Shutdown StorageExecutors | Graceful shutdown of READ/WRITE/LIST pools with 5s timeout; `shutdownNow()` on timeout. |
| 8 | Close Vert.x instance (**last**) | Closes event loops and worker threads. Must be last because other components may still use event bus. |

A JVM shutdown hook (`pantera-shutdown-hook` thread) triggers `stop()` on `SIGTERM`/`SIGINT`.

---

## 10. Health Check Architecture

### HealthSlice

Location: `pantera-main/src/main/java/com/auto1/pantera/http/HealthSlice.java`

Lightweight health check endpoint for NLB/load-balancer probes:

```
GET /.health  ->  200 OK  {"status":"ok"}
```

Key design decisions:
- **No I/O, no probes, no blocking** -- returns immediately from the event loop.
- Returns `200 OK` as long as the JVM is running and the Vert.x event loop is responsive.
- This ensures load balancers can quickly detect unresponsive instances without adding latency.

### REST API Health (AsyncApiVerticle)

The REST API verticle at `/api/v1/` provides deeper health checks including:

1. **Storage connectivity** -- Can the configured storage be reached?
2. **Database connectivity** -- Is the PostgreSQL connection pool healthy?
3. **Valkey connectivity** -- Is the Valkey/Redis connection alive?
4. **Upstream reachability** -- Can proxy repositories reach their upstreams?
5. **Scheduler status** -- Is the Quartz scheduler running?

### HTTP Status Codes

| Status | Meaning |
|--------|---------|
| `200 OK` | All probed components are healthy. |
| `503 Service Unavailable` | One or more critical components are down (database, storage). |
| `500 Internal Server Error` | Health check itself failed (unexpected exception). |

### Severity Logic

- **Critical** (returns 503): Database down, primary storage unreachable.
- **Degraded** (returns 200 with warning): Valkey unavailable (caches work without it), upstream timeout (proxy serves from cache).
- **Healthy** (returns 200): All components operational.

---

## 11. Development Setup

### Prerequisites

- **JDK 21+** (Eclipse Temurin recommended)
- **Apache Maven 3.4+**
- **Docker** and **Docker Compose** (for integration tests and local runtime)
- **Git**

### Clone and Build

```bash
git clone https://github.com/auto1-oss/pantera.git
cd pantera

# Full build (skip tests for speed)
mvn clean install -DskipTests

# Build with unit tests
mvn clean install

# Build with integration tests
mvn clean install -Pitcase
```

### IDE Setup

**IntelliJ IDEA:**
1. Open the root `pom.xml` as a Maven project.
2. Set Project SDK to JDK 21.
3. Enable annotation processing (Settings -> Build -> Compiler -> Annotation Processors).
4. Import code style from `build-tools/` (Checkstyle configuration).
5. Mark `src/main/java` and `src/test/java` as source/test roots in each module.

**VS Code:**
1. Install the "Extension Pack for Java" extension.
2. Open the root directory.
3. VS Code will auto-detect the Maven project via `pom.xml`.
4. Configure `java.configuration.runtimes` in settings to point to JDK 21.

### Running Locally

**Via Docker Compose (recommended):**

```bash
# Build the Docker image
cd pantera-main
mvn clean package -DskipTests
docker build -t pantera:2.0.0 --build-arg JAR_FILE=pantera-main-2.0.0.jar .

# Start all services (Pantera, PostgreSQL, Valkey, Keycloak)
cd docker-compose
cp .env.example .env   # Edit .env with your settings
docker compose up -d

# Pantera available at:
#   Repository endpoint: http://localhost:8088
#   REST API: http://localhost:8086
#   Metrics: http://localhost:8087
```

**Direct execution:**

```bash
# Build the fat JAR
cd pantera-main
mvn clean package -DskipTests

# Run with minimal config
java -cp target/pantera-main-2.0.0.jar:target/dependency/* \
  com.auto1.pantera.VertxMain \
  --config-file=/path/to/pantera.yml \
  --port=8080 \
  --api-port=8086
```

---

## 12. Build System

### Maven Profiles

| Profile | Activation | Purpose |
|---------|------------|---------|
| `itcase` | `-Pitcase` | Runs integration tests (`*IT.java`, `*ITCase.java`). Uses Maven Failsafe plugin. |
| `sonatype` | `-Psonatype` | Configures Sonatype OSSRH deployment (Nexus staging). |
| `gpg-sign` | Automatic when `gpg.keyname` property is set | Signs artifacts with GPG for Maven Central publishing. |

### Common Build Commands

```bash
# Clean build, skip tests
mvn clean install -DskipTests

# Unit tests only
mvn clean test

# Unit + integration tests
mvn clean verify -Pitcase

# Build a single module
mvn clean install -pl pantera-core -DskipTests

# Build a module and its dependencies
mvn clean install -pl pantera-main -am -DskipTests

# Run Checkstyle
mvn checkstyle:check

# Generate site/reports
mvn site -DskipTests
```

### Version Bumping

**`bump-version.sh`** -- Updates all 33+ Maven modules, Docker Compose, `.env`, and Dockerfile:

```bash
./bump-version.sh 1.23.0
```

This script:
1. Runs `mvn versions:set -DnewVersion=<version>`.
2. Updates the standalone `build-tools` module (no Maven parent).
3. Updates Docker Compose image tags.
4. Updates `.env` and `.env.example` `PANTERA_VERSION`.
5. Updates Dockerfile `ENV PANTERA_VERSION`.

**`build-and-deploy.sh`** -- Builds, packages, creates Docker image, and deploys to Docker Compose:

```bash
# Fast build (skip tests)
./build-and-deploy.sh

# Full build with tests
./build-and-deploy.sh --with-tests
```

---

## 13. Adding Features

### 13.1 Adding a New Repository Adapter

1. **Create the Maven module:**

```bash
mkdir -p myformat-adapter/src/main/java/com/auto1/pantera/myformat/http
```

Add to the root `pom.xml` `<modules>` section:

```xml
<module>myformat-adapter</module>
```

Create `myformat-adapter/pom.xml` with parent `com.auto1.pantera:pantera:2.0.0` and dependencies on `pantera-core` and `pantera-storage-core`.

2. **Implement the Slice:**

```java
package com.auto1.pantera.myformat.http;

import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import java.util.concurrent.CompletableFuture;

public final class MyFormatSlice implements Slice {
    private final Storage storage;

    public MyFormatSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Route by HTTP method and path
        // Handle PUT (upload), GET (download), DELETE, etc.
    }
}
```

For proxy support, extend `BaseCachedProxySlice`:

```java
public final class MyFormatCachedProxy extends BaseCachedProxySlice {
    @Override
    protected boolean isCacheable(final String path) {
        return path.endsWith(".pkg");
    }
}
```

3. **Register in RepositorySlices:**

Edit `pantera-main/src/main/java/com/auto1/pantera/RepositorySlices.java`. Add a case to the repository type switch:

```java
case "myformat":
    slice = new MyFormatSlice(storage);
    break;
```

4. **Add tests** following the patterns in existing adapters (unit tests with `InMemoryStorage`, integration tests with `*IT.java` suffix).

### 13.2 Adding a New API Endpoint

1. **Create a handler class** in `pantera-main/src/main/java/com/auto1/pantera/api/v1/`:

```java
package com.auto1.pantera.api.v1;

import io.vertx.ext.web.RoutingContext;

public final class MyFeatureHandler {
    public void handle(final RoutingContext ctx) {
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end("{\"result\": \"ok\"}");
    }
}
```

2. **Register the route** in `AsyncApiVerticle`:

In the `start()` method of `AsyncApiVerticle`, add the route to the Vert.x `Router`:

```java
router.get("/api/v1/my-feature")
    .handler(JWTAuthHandler.create(jwt))
    .handler(new MyFeatureHandler()::handle);
```

### 13.3 Adding a New Storage Backend

1. Create a new sub-module under `pantera-storage/` (e.g., `pantera-storage-gcs`).
2. Implement the `Storage` interface.
3. Implement `StorageFactory` to create instances from YAML configuration.
4. Register the factory in the storage factory chain.
5. Run the `StorageWhiteboxVerification` test harness against your implementation to ensure compliance with the `Storage` contract.

---

## 14. Testing

### Test Categories

| Category | Pattern | Runs With | Description |
|----------|---------|-----------|-------------|
| Unit tests | `*Test.java` | `mvn test` | Fast, no external dependencies. Use `InMemoryStorage`, mocks. |
| Integration tests | `*IT.java`, `*ITCase.java` | `mvn verify -Pitcase` | Require Docker (TestContainers). Test full adapter flows. |
| Database tests | Extend TestContainers PostgreSQL | `mvn verify -Pitcase` | Use `@Container` annotation with PostgreSQL TestContainer. |
| Valkey tests | Gated by `VALKEY_HOST` env var | Manual | Require a running Valkey instance. Skipped when env var is absent. |

### Test Patterns

**InMemoryStorage for unit tests:**

```java
final Storage storage = new InMemoryStorage();
storage.save(new Key.From("my/key"), new Content.From("data".getBytes()))
    .join();
```

**Hamcrest matchers:**

The codebase uses Hamcrest extensively for readable assertions:

```java
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

assertThat(response.status(), equalTo(RsStatus.OK));
assertThat(keys, hasSize(3));
assertThat(body, containsString("artifact"));
```

**TestContainers for PostgreSQL:**

```java
@Container
static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16")
    .withDatabaseName("pantera_test");

@BeforeEach
void setUp() {
    final DataSource ds = /* create HikariCP from PG.getJdbcUrl() */;
    ArtifactDbFactory.createStructure(ds);
}
```

### Running Tests

```bash
# Unit tests only
mvn test

# Unit tests for a specific module
mvn test -pl maven-adapter

# Integration tests (requires Docker)
mvn verify -Pitcase

# A specific test class
mvn test -pl pantera-core -Dtest=NegativeCacheTest

# Valkey-dependent tests
VALKEY_HOST=localhost mvn test -pl pantera-core -Dtest=ClusterEventBusTest

# Skip tests entirely
mvn install -DskipTests
```

---

## 15. Debugging

### Common Issues

**1. Event loop thread blocking**

**Symptom:** Vert.x logs "Thread blocked" warnings. All HTTP requests stall.

**Root cause:** A blocking operation (JDBC, `BlockingStorage`, synchronous file I/O) is running on the Vert.x event loop instead of a worker pool.

**Fix:** Wrap blocking calls with `DispatchedStorage` or move to `StorageExecutors.READ/WRITE`. Never call `.join()` or `.get()` on a `CompletableFuture` from the event loop.

**2. Memory leaks (ByteBuffer accumulation)**

**Symptom:** Heap grows steadily; GC cannot reclaim buffers.

**Root cause:** Response `Content` (a `Publisher<ByteBuffer>`) is not consumed. When a proxy response body is ignored (e.g., on 404 handling), the publisher holds references to byte buffers.

**Fix:** Always consume the response body, even on error paths: `resp.body().asBytesFuture().thenAccept(bytes -> { /* discard */ })`.

**3. Connection pool exhaustion**

**Symptom:** `SQLTransientConnectionException: HikariPool - Connection is not available, request timed out after 5000ms`.

**Root cause:** Too many concurrent database operations. Connection leak or pool too small.

**Fix:**
- Increase pool size: set env `PANTERA_DB_POOL_MAX=100`.
- Check for connection leaks: lower `PANTERA_DB_LEAK_DETECTION_MS=30000` to surface leaks faster.
- Monitor `hikaricp_connections_active` and `hikaricp_connections_pending` Prometheus metrics.

**4. RequestDeduplicator zombies**

**Symptom:** Certain artifacts return 503 even though upstream is healthy. `dedup-cleanup` thread logs evictions.

**Root cause:** An upstream fetch future was never completed (e.g., exception swallowed, Jetty client timeout not propagated).

**Fix:**
- Lower zombie threshold: set env `PANTERA_DEDUP_MAX_AGE_MS=60000` (1 minute).
- Monitor `deduplicator.inFlightCount()` via JMX or custom metric.
- Check Jetty client timeout settings match Vert.x request timeout.

**5. Negative cache staleness**

**Symptom:** A newly published artifact returns 404 from a proxy repository.

**Root cause:** The artifact was previously cached as "not found" in the negative cache.

**Fix:**
- Call the negative cache invalidation API endpoint.
- Invalidate programmatically: `NegativeCache.invalidate(key)` or `NegativeCache.clear()`.
- Reduce TTL: configure `PANTERA_NEGATIVE_CACHE_TTL_HOURS=1`.
- Cross-instance invalidation happens automatically if Valkey is configured.

### Debug Logging

Enable verbose logging for specific packages via `log4j2.xml`:

```xml
<Logger name="com.auto1.pantera.http.cache" level="DEBUG" />
<Logger name="com.auto1.pantera.db" level="DEBUG" />
<Logger name="com.auto1.pantera.asto.s3" level="DEBUG" />
```

Or at runtime via environment variable:

```bash
-Dlog4j2.configurationFile=/etc/pantera/log4j2.xml
```

### JVM Debug Flags

```bash
# Remote debugging
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005

# Flight Recorder (continuous profiling)
-XX:StartFlightRecording=dumponexit=true,filename=/var/pantera/logs/pantera.jfr,maxsize=500m

# Heap dump on OOM (enabled by default in Dockerfile)
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof

# GC logging (enabled by default in Dockerfile)
-Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m
```

### Diagnostic Tools

**Thread dump (from inside container):**

```bash
# Using jattach (installed in Docker image)
jattach 1 threaddump

# Using jstack (if JDK present)
jstack <pid> > /var/pantera/logs/threaddump.txt
```

**Heap dump:**

```bash
jattach 1 dumpheap /var/pantera/logs/dumps/heap.hprof
```

**Java Flight Recorder:**

```bash
# Start recording
jattach 1 jcmd "JFR.start name=pantera duration=60s filename=/var/pantera/logs/pantera.jfr"

# Dump recording
jattach 1 jcmd "JFR.dump name=pantera filename=/var/pantera/logs/pantera.jfr"
```

**Key metrics to watch:**

| Metric | Alert Threshold | Description |
|--------|----------------|-------------|
| `pantera.pool.read.queue` | > 100 | READ pool is saturated. |
| `pantera.pool.write.queue` | > 50 | WRITE pool is saturated. |
| `hikaricp_connections_pending` | > 10 | DB pool exhaustion imminent. |
| `pantera.events.queue.size` | > 1000 | Event processing falling behind. |
| `vertx_http_server_active_connections` | -- | Current connection count. |
| `pantera_http_requests_total` | -- | Request throughput by repo. |

---

*This document covers Pantera version 2.0.0. For questions, contact the Auto1 DevOps Team.*
