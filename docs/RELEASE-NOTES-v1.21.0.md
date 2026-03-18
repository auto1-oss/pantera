# Artipie v1.21.0 Release Notes

**Release Date:** March 2026
**Baseline Comparison:** v1.20.12

---

## Executive Summary

Artipie v1.21.0 is a major performance and reliability release. Benchmarks using **real client tools** (mvn, docker, npm) on resource-constrained containers (2 CPUs, 4 GB RAM) demonstrate significant improvements on the production read path, with **zero errors** across all concurrency levels up to 200 concurrent clients:

| Scenario | Improvement | Detail |
|----------|-------------|--------|
| **Docker proxy pull (warm cache)** | **+78% throughput** | 0.41 → 0.73 ops/sec at c=1; -44% mean latency (2337 → 1316 ms) |
| **NPM group install (local)** | **+14% throughput at c=20** | 9.62 → 10.97 ops/sec; -13% mean latency |
| **NPM mixed write (c=200+40)** | **-13% write latency** | 15214 → 13215 ms mean under heavy mixed read+write load |
| **NPM mixed read (c=200)** | **-9.4% read latency** | 13985 → 12670 ms mean during concurrent publish+install |
| **Maven group download 10MB (c=20)** | **+11% throughput** | 0.84 → 0.93 ops/sec; -8% p50 latency |
| **Maven mixed write (c=10)** | **-10% p50 latency** | 5758 → 5202 ms during concurrent upload+download |
| **Error rate** | **0% across all scenarios** | Including c=200 NPM (200 readers + 40 writers simultaneous) |

All downloads go through **group repositories** (`maven_group`, `npm_group`) — the production read path — exercising the `GroupSlice` parallel fan-out and `BaseCachedProxySlice` caching pipeline that were the primary optimization targets.

> Full benchmark report: `benchmark/results/BENCHMARK-REPORT.md`

---

## Highlights

Artipie v1.21.0 delivers enterprise-grade performance, high-availability clustering, and production resilience across **438 changed files** with **45,508 insertions** and **10,421 deletions**, introducing 28 new components, a new CLI module, and significant rearchitecting of the caching, proxy, and group resolution subsystems.

### Key Themes
- **Performance**: Separated I/O thread pools, parallel group fan-out with first-response CAS, request deduplication, DB query optimization
- **Reliability**: Circuit breakers, retry with jitter, graceful shutdown, dead-letter queues, Docker blob caching race fix
- **Scalability**: HA clustering via PostgreSQL node registry, cross-instance cache invalidation, bounded event queues
- **Observability**: 5-component health checks, ECS structured logging, pool utilization metrics
- **New Capabilities**: Artifact search API, backfill CLI tool, webhook notifications, OCI referrers API

---

## Breaking Changes

| Change | Impact | Migration |
|--------|--------|-----------|
| **gradle-adapter removed** | Gradle repository type no longer supported | Use `maven-proxy` type for Gradle dependencies (Gradle uses Maven repositories natively) |
| **asto-etcd removed** | Etcd storage backend no longer available | Migrate to `fs` or `s3` storage type |
| **asto-redis removed** | Redisson-based Redis storage removed | Valkey/Redis used only for caching (Lettuce client), not as primary storage |
| **Health endpoint simplified** | Returns `{"status":"ok"}` (was multi-component JSON); `HealthReportSlice` removed | Health checks now always return 200 with minimal JSON — simplify monitoring scripts accordingly |
| **GradleLayout removed** | `gradle` layout type no longer valid | Use `flat` or default layout |
| **RPM deprecated methods removed** | `batchUpdateIncrementally(Key)`, `batchUpdate(String)`, `update(Key)`, `update(String)` | Use current API methods |
| **TimeoutSlice removed from MainSlice** | Idle connection timeout now handled by Vert.x server config | Per-repo proxy timeouts still use `TimeoutSlice` in `RepositorySlices` — no repo config changes needed |

---

## Major Features

### 1. Lightweight Health Check (`HealthSlice`)

`HealthSlice` was simplified to a zero-I/O, zero-blocking probe for NLB/load-balancer health checks. The previous multi-component probe system (`HealthReportSlice`) was removed — it added latency and complexity to a path that must be fast and reliable above all else.

- Returns `200 OK` with `{"status":"ok"}` immediately
- No storage probes, no database connections, no Valkey pings
- **Fast-path in `VertxSliceServer.proxyHandler()`**: `/.health` requests are handled directly in the Vert.x event loop, bypassing all middleware (metrics, logging, routing, futures) for sub-millisecond response with zero connection leak risk

### 2. PostgreSQL Full-Text Artifact Search (`DbArtifactIndex`)

Replaces Lucene-based search with PostgreSQL `tsvector` and GIN indexes:

- Full-text search with `ts_rank()` relevance ordering
- Graceful fallback to `LIKE` pattern matching
- Locate operation: find all repositories containing an artifact
- Batch indexing with transaction support
- Always consistent (database is authoritative)

### 3. Search REST API (`SearchRest`)

New API endpoints for artifact discovery:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/search?q={query}&size={20}&from={0}` | GET | Full-text search with pagination |
| `/api/v1/search/locate?path={path}` | GET | Find repositories containing an artifact |
| `/api/v1/search/reindex` | POST | Trigger full reindex (202 Accepted) |
| `/api/v1/search/stats` | GET | Index statistics |

### 4. HA Clustering (`DbNodeRegistry`, `ClusterEventBus`)

PostgreSQL-backed cluster coordination:

- **Node Registry**: Automatic node registration, heartbeat-based liveness detection, stale node eviction
- **Event Bus**: Valkey pub/sub with topic-based dispatch (`artipie:events:{topic}`), self-message filtering via instance UUID
- **Cache Invalidation**: Cross-instance Caffeine cache invalidation via `artipie:cache:invalidate` channel

### 5. Backfill CLI Tool (`artipie-backfill` module)

New standalone module for populating the `artifacts` database table from existing disk storage:

- Supports 11 repository types: Maven, Docker, NPM, PyPI, Go, Helm, Composer, Debian, Ruby Gems, Files, and generic
- Two modes: single-repo and bulk (automatic multi-repo scanning)
- Batch insert with upsert pattern (idempotent)
- Lazy streaming for constant memory usage on arbitrarily large repos
- Dry-run mode, configurable progress logging, HikariCP connection pooling
- Exit codes for scripting integration

```bash
java -jar artipie-backfill.jar \
  --jdbc-url jdbc:postgresql://db:5432/artifacts \
  --storage-path /var/artipie/data \
  --repo-name maven_proxy --repo-type maven-proxy \
  --batch-size 500
```

### 6. Webhook Notifications (`WebhookDispatcher`)

Event-driven HTTP webhook delivery:

- Dispatches artifact lifecycle events (published, deleted) to configured endpoints
- HMAC-SHA256 request signing with configurable secret
- Exponential backoff retry (up to 3 attempts)
- Event type and repository name filtering
- Async delivery via Vert.x WebClient

### 7. OCI Referrers API (`ReferrersSlice`)

Implements OCI Distribution Specification v1.1 referrers endpoint:

- `GET /v2/{name}/referrers/{digest}` returns manifests referencing a given digest
- Returns valid OCI Image Index format
- Always returns `200 OK` per spec (never 404)

---

## Performance Enhancements

### DB 99% CPU Fix (`ArtifactNameParser`, `locateByName`)

**Critical production fix** — the `artifacts` table query for group repository resolution used `LIKE '%/artifact-name'` (reverse LIKE), which forced PostgreSQL to perform full sequential scans on tables with 1.1M+ rows, pinning the database at 99% CPU.

Fix: New `ArtifactNameParser` extracts the artifact name from the request path using adapter-aware parsing rules (Maven groupId/artifactId, NPM scoped packages, Docker image names, etc.). The new `locateByName()` query uses an indexed B-tree lookup (`WHERE name = ?`) instead of reverse LIKE, reducing query cost from O(N) sequential scan to O(log N) index lookup.

Files: `ArtifactNameParser`, `DbArtifactIndex.locateByName()`, `GroupSlice`

### HTTP/2 Flow Control Tuning (`VertxSliceServer`)

| Parameter | v1.20.12 | v1.21.0 | Impact |
|-----------|----------|---------|--------|
| Stream INITIAL_WINDOW_SIZE | 64 KB (RFC default) | 16 MB | Removes ~1 MB/s throughput cap on 55ms LAN |
| Connection window | 64 KB | 128 MB | Enables full bandwidth utilization |

### Zero-Copy Response Writing (`VertxSliceServer`)

- `Unpooled.wrappedBuffer()` eliminates double memory copy on request path
- Request path buffer read directly from Netty ByteBuf without allocation

### Separated Thread Pools (`StorageExecutors`)

| Pool | Size | Purpose |
|------|------|---------|
| READ | 4 x CPU cores | `value()`, `exists()`, metadata queries |
| WRITE | 2 x CPU cores | `save()`, `move()`, `delete()` |
| LIST | 1 x CPU cores | Directory listings |

Configurable via `ARTIPIE_IO_READ_THREADS`, `ARTIPIE_IO_WRITE_THREADS`, `ARTIPIE_IO_LIST_THREADS`.
Metrics: `artipie.pool.{read,write,list}.{active,queue}` gauges.

### Request Deduplication (`RequestDeduplicator`)

Concurrent proxy requests for the same artifact coalesce to a single upstream fetch. Waiting callers receive the same signal (SUCCESS, NOT_FOUND, ERROR). Zombie protection evicts stale entries after 5 minutes (configurable via `ARTIPIE_DEDUP_MAX_AGE_MS`).

### Streaming Cache Architecture (`BaseCachedProxySlice`, `StreamThroughCache`)

Unified proxy caching flow across all adapter types:

1. Negative cache fast-fail on known 404s
2. Local cache check (offline-safe)
3. Cooldown evaluation
4. Request deduplication
5. NIO streaming to temp file (8KB chunks, avoids heap buffering)
6. Incremental digest computation (SHA-256, MD5) during cache write
7. Sidecar generation (Maven checksums, npm package.json)

`StreamThroughCache` uses tee-content pattern: bytes forwarded to caller immediately while written to temp file for async storage save. Buffer upgraded to 64KB.

### Group Resolution Optimization (`GroupSlice`)

- Full parallel fan-out to all members (not sequential/batched)
- First successful response wins via `AtomicBoolean` CAS; remaining members cancelled
- Semaphore-limited concurrent body drains (default 20 permits, configurable via `ARTIPIE_GROUP_DRAIN_PERMITS`)
- Index-first O(1) lookup before parallel fan-out
- Routing rules for path-prefix matching to member subsets

### Circuit Breakers (`AutoBlockRegistry`, `CircuitBreakerSlice`, `MemberSlice`)

- Fibonacci backoff: `[1,1,2,3,5,8,13,21,34,55,89] x base_duration`
- Three states: ONLINE -> BLOCKED (after N failures) -> PROBING (block expired, testing recovery)
- BLOCKED state returns 503 immediately (0ms response cost)
- Per-member circuit breaker in group repositories

### Retry with Jitter (`RetrySlice`)

- Exponential backoff: 100ms initial, 2x multiplier
- Random 0-50% jitter prevents thundering herd
- Only retries 5xx and exceptions; passes through 4xx immediately

### Negative Cache (`NegativeCache`, `NegativeCacheRegistry`)

- Two-tier: Caffeine L1 (in-process) + Valkey L2 (shared)
- Global registry for cross-adapter invalidation on artifact publish
- Instant 404 response for known-missing artifacts

### Docker Blob Caching Fix (`CachingBlob`)

- **Race condition fixed**: `AtomicBoolean` CAS guard prevents double execution when `doOnCancel` and `doOnComplete` fire on different threads simultaneously
- **OOM fix**: `Files.readAllBytes()` replaced with `Flowable.using()` streaming in 8KB chunks
- **Verified**: 1.917 GB Docker blob cached successfully on first pull through proxy

### Disk Cache Storage (`DiskCacheStorage`)

- LRU/LFU eviction with configurable high/low watermarks
- 256-stripe lock array for metadata updates
- Orphan cleanup on startup (.part files, stale metadata)
- Namespace isolation via SHA-1 hash of storage identifier
- Atomic file moves via NIO `ATOMIC_MOVE`

### Bounded Event Queues (`EventQueue`)

- Default capacity: 10,000 items (configurable)
- `ConcurrentLinkedQueue` + `AtomicInteger` size tracker
- Overflow silently drops with warning log (prevents OOM)

### S3 Storage Improvements

- Paginated listing with continuation tokens
- Configurable connection pool sizes
- Improved multipart upload handling

### HTTP Compression

- Enabled gzip/deflate at compression level 6
- 20+ binary content types detected and skipped (Docker layers, tar.gz, JAR, etc.)
- `Content-Encoding: identity` header for incompressible content

---

## Bug Fixes

### Critical

- **DB 99% CPU on group resolution**: Reverse `LIKE '%/name'` query forced full sequential scan on 1.1M+ row `artifacts` table. Fix: `ArtifactNameParser` + adapter-aware `locateByName()` with indexed B-tree lookup. See Performance Enhancements section.

- **Docker proxy UNKNOWN owner in artifact events**: MDC `user.name` lost when `body.asBytesFuture().thenCompose()` runs on Vert.x event loop thread. Fix: capture `Login` header before async boundary, re-set MDC inside `thenCompose`. Also filter out `"UNKNOWN"` from `inspector.ownerFor()`.

- **Docker blob caching race condition**: VertxSliceServer cancels RxJava subscription after sending all Content-Length bytes to client, racing with upstream Jetty HTTP client's `onComplete` signal. `doOnCancel` closes FileChannel and deletes temp file, then `doOnComplete` tries `ch.force(true)` -> `ClosedChannelException`. Fix: `AtomicBoolean finished` with `compareAndSet` guard.

- **Z_DATA_ERROR on gzip Content-Encoding**: Jetty HTTP client auto-decodes gzip responses but leaves `Content-Encoding` and original `Content-Length` headers. Clients attempt double decompression. Fix: strip `Content-Encoding` and `Content-Length` after Jetty decoding in `JettyClientSlice.toHeaders()`.

- **Docker blob OOM on large layers**: `Files.readAllBytes(tmp)` loads entire blob into heap (1.9GB+). Fix: replaced with `Flowable.using()` streaming from temp file in 8KB chunks.

### Important

- **44 silent exception catch blocks** now log with ECS structured logging across 24 source files
- **Quartz double-shutdown** protected by `AtomicBoolean` (prevents `SchedulerException` on second shutdown)
- **Graceful shutdown drain**: In-flight requests complete within configurable timeout (default 30s) before server stops
- **Maven metadata timestamp parsing**: New `MavenTimestamp` class handles edge cases in `lastUpdated` field parsing
- **NPM metadata ETag handling**: Improved conditional request support
- **Composer proxy metadata URL rewriting**: Fixed URL transformation for proxied packages
- **File descriptor leak prevention**: `deleteOnExit()` added to all temp file creation sites
- **HTTP/2 forbidden headers**: Connection-specific headers (connection, keep-alive, transfer-encoding, upgrade) stripped before sending to HTTP/2 clients

---

## New Components

| Component | Module | Purpose |
|-----------|--------|---------|
| `BaseCachedProxySlice` | artipie-core | Unified proxy cache template for all adapters |
| `RequestDeduplicator` | artipie-core | Concurrent request coalescing |
| `StorageExecutors` | artipie-core | Named thread pools by I/O type |
| `RetrySlice` | artipie-core | Exponential backoff with jitter |
| `AutoBlockRegistry` | artipie-core | Fibonacci backoff circuit breaker |
| `NegativeCacheRegistry` | artipie-core | Cross-adapter 404 cache invalidation |
| `ConfigDefaults` | artipie-core | Centralized env var configuration |
| `ClusterEventBus` | artipie-core | Cross-instance Valkey pub/sub |
| `CacheInvalidationPubSub` | artipie-core | Multi-instance Caffeine cache sync |
| `ContentAddressableStorage` | artipie-core | Blob deduplication by SHA-256 |
| `StreamThroughCache` | asto-core | Streaming cache with tee-content pattern |
| `CachingBlob` | docker-adapter | Docker layer stream-to-cache with race protection |
| `DbArtifactIndex` | artipie-main | PostgreSQL full-text search |
| `DbNodeRegistry` | artipie-main | HA cluster node registry |
| `SearchRest` | artipie-main | Artifact search REST API |
| `WebhookDispatcher` | artipie-main | Event webhook delivery |
| `TempFileCleanupJob` | artipie-main | Scheduled temp file deletion |
| `QuartzSchema` | artipie-main | Quartz JDBC job store DDL |
| `OfflineAwareSlice` | artipie-main | Offline mode for proxy repos |
| `DeadLetterWriter` | artipie-main | Failed event archival to JSON |
| `ReferrersSlice` | docker-adapter | OCI v1.1 referrers endpoint |
| `RoutingRule` | artipie-main | Path-prefix routing for group members |
| `WritableGroupSlice` | artipie-main | Write-through for group repos |
| `GroupMetadataCache` | artipie-main | Group-level metadata caching |
| `DigestComputer` | artipie-core | Incremental digest computation |
| `SidecarFile` | artipie-core | Maven checksum sidecar generation |
| `RepoNameMeterFilter` | artipie-core | Metrics cardinality control |
| `DispatchedStorage` | artipie-core | Storage with separated executors |
| `DiskCacheStorage` | asto-s3 | S3 read-through disk cache with LRU/LFU |
| `LockCleanupScheduler` | asto-core | Storage lock TTL cleanup |
| `ArtifactNameParser` | artipie-main | Adapter-aware artifact name extraction for indexed DB lookup |

---

## Removed Components

| Component | Module | Reason |
|-----------|--------|--------|
| `gradle-adapter` (entire module) | gradle-adapter | Gradle uses Maven repositories natively; dedicated adapter unnecessary |
| `asto-etcd` (entire module) | asto | Etcd not used in production deployments |
| `asto-redis` (entire module) | asto | Consolidated on Lettuce client; Redisson removed |
| `GradleLayout` | artipie-core | Removed with gradle-adapter |
| `GradleProxy` | artipie-main | Removed with gradle-adapter |
| `GroupNegativeCache` | artipie-main | Replaced by `NegativeCacheRegistry` |
| `CacheRest` | artipie-main | Replaced by `SearchRest` |
| `ApiCachePermission` | artipie-main | Replaced by `ApiSearchPermission` |
| `LuceneArtifactIndex` | artipie-main | Replaced by `DbArtifactIndex` |
| `IndexWarmupService` | artipie-main | No longer needed (DB always consistent) |
| `IndexConsumer` | artipie-main | Replaced by DB-backed indexing |
| `HealthReportSlice` | artipie-main | Merged into simplified `HealthSlice` |

---

## Configuration Changes

### New Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ARTIPIE_IO_READ_THREADS` | 4 x CPU | Read thread pool size |
| `ARTIPIE_IO_WRITE_THREADS` | 2 x CPU | Write thread pool size |
| `ARTIPIE_IO_LIST_THREADS` | 1 x CPU | List thread pool size |
| `ARTIPIE_DEDUP_MAX_AGE_MS` | 300000 (5m) | Request dedup zombie timeout |
| `ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS` | 24 | Docker cache entry TTL |
| `ARTIPIE_NPM_INDEX_TTL_HOURS` | 24 | NPM search index TTL |
| `ARTIPIE_DB_CONNECTION_TIMEOUT_MS` | 30000 | DB connection acquisition timeout |
| `ARTIPIE_DB_IDLE_TIMEOUT_MS` | 600000 | DB idle connection timeout |
| `ARTIPIE_DB_MAX_LIFETIME_MS` | 1800000 | DB connection max lifetime |
| `ARTIPIE_DB_POOL_MAX` | 20 | HikariCP max pool size |
| `ARTIPIE_DB_POOL_MIN` | 5 | HikariCP min idle connections |
| `ARTIPIE_BODY_BUFFER_THRESHOLD` | 1048576 (1MB) | Small/large request body boundary |
| `ARTIPIE_GROUP_DRAIN_PERMITS` | 20 | Max concurrent group response drains |

### New artipie.yml Options

```yaml
meta:
  # Webhook configuration
  webhooks:
    - url: "https://hooks.example.com/artipie"
      secret: "hmac-secret"
      events: ["artifact.published"]
      repos: ["maven_proxy"]

  # Search configuration (automatic with artifacts_database)
  search:
    engine: postgres  # Only option currently

  # Cooldown system
  cooldown:
    enabled: false
    minimum_allowed_age: 7d
    repo_types:
      npm-proxy:
        enabled: true
```

---

## Documentation

### New Documents
- `docs/ENVIRONMENT_VARIABLES.md` -- Complete environment variable reference
- `docs/ha-deployment/` -- HA deployment configs (nginx-ha.conf, docker-compose-ha.yml, artipie-ha.yml)
- `.wiki/Configuration-HA.md` -- HA clustering wiki page
- `.wiki/Configuration-Health.md` -- Health check wiki page
- `.wiki/Configuration-Metadata.md` -- Metadata configuration wiki
- `.wiki/Configuration-Metrics.md` -- Metrics configuration wiki
- `.wiki/Rest-api.md` -- REST API reference
- `artipie-backfill/README.md` -- Backfill tool documentation
- `docs/DEVELOPER_GUIDE.md` -- Updated developer guide (significantly expanded)
- `docs/USER_GUIDE.md` -- Updated user guide (significantly expanded)

### Updated Swagger UI
- Removed: `cache.yaml` (replaced by search)
- Added: `search.yaml` (search API)
- Updated: `repo.yaml`, `roles.yaml`, `settings.yaml`, `token-gen.yaml`, `users.yaml`

---

## Dependency Changes

| Dependency | v1.20.12 | v1.21.0 | Notes |
|------------|----------|---------|-------|
| Vert.x | 4.5.x | 4.5.22 | Patch update (not a major upgrade) |
| Jetty | (prev) | 12.1.4 | HTTP client |
| Jackson BOM | - | 2.17.3 | Consistent Jackson versions across all modules |
| Micrometer | (prev) | 1.12.13 | Metrics |
| PostgreSQL driver | (prev) | 42.7.1 | |
| commons-compress | (prev) | 1.27.1 | Security fix |
| AWS STS | - | Added | AWS STS credential support for S3 storage (`asto-s3`) |
| asto-etcd / jetcd-core | 0.7.1 | **Removed** | Entire `asto-etcd` module removed |
| asto-redis / Redisson | present | **Removed** | Entire `asto-redis` module removed; Valkey/Redis via Lettuce only |

---

## Test Coverage

| Module | Tests | Status |
|--------|-------|--------|
| artipie-core | 663+ | All passing |
| artipie-main | 800+ | All passing |
| docker-adapter | 436+ | All passing |
| http-client | 102+ | All passing |
| vertx-server | 85+ (new) | All passing |
| artipie-backfill | 200+ (new) | All passing |
| asto-core | 145+ (new/updated) | All passing |
| asto-s3 | 170+ (new/updated) | All passing |

### New Test Classes
- `VertxSliceServerTest`, `VertxSliceServerRobustnessTest`
- `CachingBlobTest` (6 tests)
- `GetManifestSliceMdcTest`, `HeadManifestSliceMdcTest`
- `JettyClientSliceGzipTest`
- `BaseCachedProxySliceContentEncodingTest`
- `RequestDeduplicatorTest`, `DigestComputerTest`, `RetrySliceTest`
- `CircuitBreakerSliceTest`, `AutoBlockRegistryTest`, `TimeoutSettingsTest`
- `StorageExecutorsTest`, `ConfigDefaultsTest`, `RepoNameMeterFilterTest`
- `DispatchedStorageTest`, `DiskCacheStorageTest`
- `ClusterEventBusTest`, `DbNodeRegistryTest`, `NodeRegistryTest`
- `CacheInvalidationPubSubTest`, `PublishingFiltersCacheTest`
- `DbArtifactIndexTest`, `HealthSliceTest` (expanded)
- `QuartzServiceJdbcTest`, `TempFileCleanupJobTest`
- `WebhookConfigTest`, `WebhookDispatcherTest`
- `OfflineAwareSliceTest`, `DeadLetterWriterTest`
- `GroupMetadataCacheTest`, `MemberSliceTest`, `RoutingRuleTest`, `WritableGroupSliceTest`
- `StreamThroughCacheTest`, `EventQueueTest`, `LockCleanupSchedulerTest`
- `ArtifactNameParserTest`, `LocateHitRateTest`
- All backfill module tests (11 scanner tests + integration tests)

---

## Upgrade Guide

### From v1.20.12 to v1.21.0

1. **Remove gradle-adapter references**: If using `type: gradle` or `type: gradle-proxy`, switch to `type: maven` or `type: maven-proxy`. Gradle resolves from Maven repositories natively.

2. **Remove etcd/redis storage configs**: If using `type: etcd` or `type: redis` in storage configuration, migrate to `type: fs` or `type: s3`.

3. **Update health check consumers**: The `/health` endpoint now returns a simple `{"status":"ok"}` response (was multi-component JSON). `HealthReportSlice` has been removed. The health check is now a zero-I/O fast-path in the Vert.x handler — update monitoring scripts accordingly.

4. **Review environment variables**: v1.21.0 introduces many new environment variables with sensible defaults. Review `docs/ENVIRONMENT_VARIABLES.md` for tuning options.

5. **Database migration**: If using `artifacts_database`, run the backfill tool to populate any missing artifact records:
   ```bash
   java -jar artipie-backfill.jar --jdbc-url jdbc:postgresql://db:5432/artifacts \
     --storage-path /var/artipie/data --bulk --artipie-config /etc/artipie/artipie.yml
   ```

---

## Third-Party Contributions

| Contributor | PR(s) | Changes |
|-------------|-------|---------|
| **Sentinel-One** (Tzahi Ferester) | #1478 | AWS STS dependency for S3 credential support |
| **Sentinel-One** (Tzahi Ferester) | #1477 | PyPI adapter: delete artifacts implementation |
| **Sentinel-One** (Tzahi Ferester) | #1469 | Debian adapter: fix invalid date format in Release file |
| **Sentinel-One** (Tzahi Ferester) | #1463 | Debian adapter: list + delete artifacts implementation |
| **mikra01** (MIKRAU) | #1454 | Fix missing httpcore5/httpcore5-h2 dependencies |
| **mikra01** (MIKRAU) | #1452 | JDK 17 build compatibility fixes |

---

## Contributors

- Auto1 Group Engineering Team
- Sentinel-One (Tzahi Ferester)
- mikra01 (MIKRAU)
- Artipie Open Source Community

---

## Stats

- **438 files changed**, 45,508 insertions(+), 10,421 deletions(-)
- **28 new components**, 12 removed components
- **0% error rate** across all benchmark scenarios up to 200 concurrent clients
