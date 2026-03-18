# Auto1 Fork Changelog

All notable changes to the Auto1 fork of Artipie are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/).

## v1.20.14 (February 2026)

### Added
- Comprehensive health checks with 5 probes: storage, database, Valkey, Quartz scheduler, HTTP client (`HealthSlice`)
- PostgreSQL full-text search with tsvector/GIN index for artifact search (`DbArtifactIndex`)
- HA clustering support: PostgreSQL-backed node registry with heartbeat-based liveness detection (`DbNodeRegistry`)
- Cross-instance event bus via Valkey pub/sub with topic-based dispatch (`ClusterEventBus`)
- Named worker pools by operation type: READ (CPU×4), WRITE (CPU×2), LIST (CPU) (`StorageExecutors`)
- Pool utilization metrics: `artipie.pool.{read,write,list}.{active,queue}` gauges
- Event queue depth metrics: `artipie.events.queue.size`, per-repo `artipie.proxy.queue.size`
- Bulk temp file cleanup Quartz job with 5 file patterns (`TempFileCleanupJob`)
- HA deployment reference configs: nginx-ha.conf, docker-compose-ha.yml, artipie-ha.yml
- Environment variable reference documentation (`docs/ENVIRONMENT_VARIABLES.md`)
- All remaining hardcoded values externalized to environment variables:
  - `ARTIPIE_DB_CONNECTION_TIMEOUT_MS`, `ARTIPIE_DB_IDLE_TIMEOUT_MS`, `ARTIPIE_DB_MAX_LIFETIME_MS`
  - `ARTIPIE_DEDUP_MAX_AGE_MS`, `ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS`, `ARTIPIE_NPM_INDEX_TTL_HOURS`
  - `ARTIPIE_IO_READ_THREADS`, `ARTIPIE_IO_WRITE_THREADS`, `ARTIPIE_IO_LIST_THREADS`

### Fixed
- 44 silent exception catch blocks now log with EcsLogger across 24 source files
- Request path double memory copy eliminated in VertxSliceServer (zero-copy via Netty ByteBuf)
- RPM adapter: 4 deprecated methods removed (`batchUpdateIncrementally(Key)`, `batchUpdate(String)`, `update(Key)`, `update(String)`)

### Changed
- Health endpoint returns 5-component JSON with severity logic (was 2-component)
- Search API uses tsvector with ts_rank() relevance ordering (was LIKE pattern matching)
- Health HTTP status: 200 for healthy/degraded, 503 for unhealthy
- Quartz scheduler shutdown protected by AtomicBoolean (prevents double-shutdown)

## v1.20.13 (February 2026)

### Added
- PostgreSQL-backed artifact search and resolution (`DbArtifactIndex`)
- Lucene fully removed (`LuceneArtifactIndex`, `IndexWarmupService`, `IndexConsumer` deleted)
- Enterprise technical assessment and gap analysis documentation
- Covering indexes (`idx_artifacts_locate`, `idx_artifacts_browse`)
- Backfill module for artifact metadata migration
- Webhook dispatcher for event notifications
- Search REST API (`/api/v1/search/*`)
- 6 planning documents in `docs/plans/`

## v1.20.12 (February 2026)

### Added
- Cooldown system for supply chain security
- Negative cache: two-tier Caffeine L1 + Valkey L2
- `DiskCacheStorage` with LRU/LFU eviction and striped locks
- `AutoBlockRegistry` with Fibonacci backoff circuit breaker
- `TimeoutSettings` unified configuration with hierarchical override
- Graceful shutdown drain (30s default, configurable)
- Resource cleanup on shutdown (HikariCP, S3, Valkey, Jetty)
- Dead-letter queue for failed DB events
- Valkey connection pool (`GenericObjectPool`)
- Quartz JDBC clustering
- Redis/Valkey pub/sub cache invalidation
- Lock TTL cleanup scheduler
- `asto-etcd` removed
- `asto-redis` (Redisson) removed, consolidated on Lettuce
- Temp file `deleteOnExit()` added to all creation sites
- Quartz double-shutdown protection
- Metrics cardinality control (percentiles opt-in, repo_name cap)
- Body buffer threshold configurable (`ARTIPIE_BODY_BUFFER_THRESHOLD`)
- Group drain permits configurable (`ARTIPIE_GROUP_DRAIN_PERMITS`)
- DB pool sizes configurable (`ARTIPIE_DB_POOL_MAX`, `ARTIPIE_DB_POOL_MIN`)
- Zero-copy response writing (`Unpooled.wrappedBuffer`)
- `RetrySlice` exponential backoff with jitter
- Compression filter for binary artifacts
- S3 paginated listing with continuation tokens
- Hierarchical list override in `FileStorage` and `VertxFileStorage`
- `StreamThroughCache` buffer upgraded to 64KB
- Request deduplication for proxy cache
- Bounded event queues (10,000 capacity)
- Import CLI tool
- 12 documentation files in `docs/`
- Wiki updates (Configuration-Metadata, Rest-api, Home, maven-proxy, npm-proxy)
