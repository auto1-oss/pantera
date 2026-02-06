# Artipie v1.20.14 — Close All P2/P3 Gaps + HA + Search + Health + Documentation

**Date:** 2026-02-19
**Goal:** Close all remaining P2 and P3 items to 100%, implement HA clustering, PostgreSQL full-text search, comprehensive health checks, and update all documentation.

---

## Part A: P2 Gaps (6 items remaining → 100%)

### A1. P2.1 — Separate Worker Pools by Operation Type

**Status:** ✅ DONE — Pool sizes configurable via ARTIPIE_IO_READ_THREADS, ARTIPIE_IO_WRITE_THREADS, ARTIPIE_IO_LIST_THREADS
**Current state:** All storage operations share the same Vert.x worker pool. `StorageExecutors.java` exists with READ/WRITE/LIST pools but they are standard `ExecutorService` pools, NOT named Vert.x worker pools.

**Tasks:**
1. In `VertxMain.java`, create three named Vert.x worker pools:
   ```java
   WorkerExecutor readPool = vertx.createSharedWorkerExecutor(
       "artipie.io.read", cpuCores * 4, 120, TimeUnit.SECONDS);
   WorkerExecutor writePool = vertx.createSharedWorkerExecutor(
       "artipie.io.write", cpuCores * 2, 120, TimeUnit.SECONDS);
   WorkerExecutor listPool = vertx.createSharedWorkerExecutor(
       "artipie.io.list", cpuCores, 120, TimeUnit.SECONDS);
   ```
2. Wire these pools into `StorageExecutors.java` (replace `newFixedThreadPool` calls at lines 31, 39, 47) so Vert.x metrics can observe them
3. Add pool sizes as configurable env vars: `ARTIPIE_IO_READ_THREADS`, `ARTIPIE_IO_WRITE_THREADS`, `ARTIPIE_IO_LIST_THREADS`
4. Close all three pools in `VertxMain.stop()` shutdown sequence (before Vert.x close)

**Files to modify:**
- `artipie-main/src/main/java/com/artipie/VertxMain.java`
- `artipie-core/src/main/java/com/artipie/http/misc/StorageExecutors.java`

---

### A2. P2.4 — Comprehensive Health Checks (Valkey + Quartz + HTTP Client)

**Status:** ✅ DONE — 5 probes (storage, database, Valkey, Quartz, HTTP client) with 21 tests
**Current state:** `HealthSlice.java` probes storage (`list(Key.ROOT)` 5s timeout) and database (`connection.isValid(5)`). Missing: Valkey, Quartz, HTTP client.

**Tasks:**
1. Add `valkeyConnection()` method to `Settings` interface (return `Optional<ValkeyConnection>`):
   - File: `artipie-core/src/main/java/com/artipie/settings/Settings.java`
   - Implement in `YamlSettings.java` to return `Optional.ofNullable(this.valkeyConn)`
2. Add `isRunning()` method to `QuartzService.java`:
   ```java
   public boolean isRunning() {
       try {
           return this.scheduler.isStarted() && !this.scheduler.isShutdown()
               && !this.scheduler.isInStandbyMode();
       } catch (SchedulerException ex) { return false; }
   }
   ```
3. Add `isOperational()` method to `JettyClientSlices.java`:
   ```java
   public boolean isOperational() {
       return this.started.get() && !this.stopped.get() && this.clnt.isRunning();
   }
   ```
4. Refactor `HealthSlice.java` constructor to accept all probes:
   ```java
   public HealthSlice(Storage storage, Optional<DataSource> ds,
       Optional<ValkeyConnection> valkey, Optional<QuartzService> quartz,
       Optional<Supplier<Boolean>> httpClient)
   ```
5. Add three new probe methods in `HealthSlice.java`:
   - `probeValkey()` — calls `valkeyConn.pingAsync()` (already has 1s timeout)
   - `probeQuartz()` — calls `quartz.isRunning()` via `CompletableFuture.supplyAsync`
   - `probeHttpClient()` — calls `httpClient.get()` (synchronous state check)
6. Update `response()` to use `CompletableFuture.allOf()` aggregating all 5 probes
7. Update JSON response to include `valkey`, `quartz`, `http_client` components
8. Wire new constructor in `MainSlice.java` / `VertxMain.java` to pass all dependencies
9. Add unit tests for each new probe (healthy, unhealthy, not_configured states)

**Files to modify:**
- `artipie-core/src/main/java/com/artipie/settings/Settings.java`
- `artipie-main/src/main/java/com/artipie/settings/YamlSettings.java`
- `artipie-main/src/main/java/com/artipie/scheduling/QuartzService.java`
- `http-client/src/main/java/com/artipie/http/client/jetty/JettyClientSlices.java`
- `artipie-main/src/main/java/com/artipie/http/HealthSlice.java`
- `artipie-main/src/main/java/com/artipie/http/MainSlice.java`
- `artipie-main/src/main/java/com/artipie/VertxMain.java`
- Test files for HealthSlice

---

### A3. P2.5 — Externalize All Remaining Hardcoded Values

**Status:** ✅ DONE — 6 values externalized via ConfigDefaults (ARTIPIE_DEDUP_MAX_AGE_MS, ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS, ARTIPIE_NPM_INDEX_TTL_HOURS, ARTIPIE_DB_CONNECTION_TIMEOUT_MS, ARTIPIE_DB_IDLE_TIMEOUT_MS, ARTIPIE_DB_MAX_LIFETIME_MS)

**Tasks:**
1. Make `RequestDeduplicator` zombie age configurable:
   - File: `artipie-core/src/main/java/com/artipie/http/cache/RequestDeduplicator.java:37`
   - Add: `ConfigDefaults.getLong("ARTIPIE_DEDUP_MAX_AGE_MS", 300_000L)`
2. Make Docker proxy cooldown cache expiry configurable:
   - File: `artipie-main/.../DockerProxyCooldownInspector.java:45,50`
   - Add: `ConfigDefaults.getLong("ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS", 24)`
3. Make NPM package index TTL configurable:
   - File: `npm-adapter/.../InMemoryPackageIndex.java:71`
   - Add: `ConfigDefaults.getLong("ARTIPIE_NPM_INDEX_TTL_HOURS", 24)`
4. Make HikariCP connection timeout configurable:
   - File: `artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java:206`
   - Add: `ConfigDefaults.getLong("ARTIPIE_DB_CONNECTION_TIMEOUT_MS", 5000L)`
5. Make HikariCP idle timeout configurable:
   - File: `ArtifactDbFactory.java:207`
   - Add: `ConfigDefaults.getLong("ARTIPIE_DB_IDLE_TIMEOUT_MS", 600_000L)`
6. Make HikariCP max lifetime configurable:
   - File: `ArtifactDbFactory.java:208`
   - Add: `ConfigDefaults.getLong("ARTIPIE_DB_MAX_LIFETIME_MS", 1_800_000L)`

**Files to modify:**
- `artipie-core/src/main/java/com/artipie/http/cache/RequestDeduplicator.java`
- `artipie-main/src/main/java/com/artipie/docker/DockerProxyCooldownInspector.java`
- `npm-adapter/src/main/java/com/artipie/npm/proxy/InMemoryPackageIndex.java`
- `artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java`

---

### A4. P2.7 — Make DB Connection Timeout Configurable via YAML/Env

**Status:** ✅ DONE — Covered by A3 (ARTIPIE_DB_CONNECTION_TIMEOUT_MS, ARTIPIE_DB_IDLE_TIMEOUT_MS, ARTIPIE_DB_MAX_LIFETIME_MS)
**Note:** Merged into A3 task #4 above. This item is closed by completing A3.

---

### A5. P2.9 — Log All Suppressed Exceptions

**Status:** ✅ DONE — 44 silent catch blocks replaced with EcsLogger across 24 source files

**Tasks:**
1. Find and fix all silent `catch (Exception ignored)` blocks. Replace with `EcsLogger.warn()`:
   ```java
   // Before:
   catch (Exception ignored) {}
   // After:
   catch (Exception ex) {
       EcsLogger.warn("com.artipie.component")
           .message("Operation failed silently")
           .error(ex)
           .log();
   }
   ```

**Files to modify (at minimum):**
- `asto/asto-s3/src/main/java/com/artipie/asto/s3/DiskCacheStorage.java` — 7 silent catches (lines ~240, 271, 329, 339, 390, 464, 496)
- `artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java` — 2 silent catches (lines ~365, 372)
- `artipie-core/src/main/java/com/artipie/http/cache/BaseCachedProxySlice.java` — 3 silent catches (lines ~689, 701, 888)
- `asto/asto-core/src/main/java/com/artipie/asto/Content.java` — 2 silent catches (lines ~162, 170)
- `artipie-main/src/main/java/com/artipie/docker/DockerProxyCooldownSlice.java` — 2 silent catches (lines ~87, 365)
- `artipie-main/src/main/java/com/artipie/scheduling/MetadataRegenerator.java` — 1 silent catch (line ~306)
- `artipie-main/src/main/java/com/artipie/auth/OktaOidcClient.java` — 1 silent catch (line ~126)
- Any others found by: `grep -rn "catch.*ignored\|catch.*Exception.*{}" --include="*.java"`

---

### A6. P2.10 — Complete Metrics: Queue Depth + Pool Utilization

**Status:** ✅ DONE — 6 pool gauges (active/queue for READ/WRITE/LIST) + event queue gauge + per-repo proxy queue gauges registered in VertxMain

**Tasks:**
1. Add event queue depth gauge in `MetadataEventQueues.java`:
   ```java
   Metrics.globalRegistry.gauge("artipie.event.queue.size",
       Tags.of("queue", queueName), queue, Queue::size);
   ```
2. Add proxy event queue depth per-repo:
   ```java
   Metrics.globalRegistry.gauge("artipie.proxy.event.queue.size",
       Tags.of("repo", repoName), proxyQueue, LinkedBlockingQueue::size);
   ```
3. Add dedup hit rate counter in `RequestDeduplicator.java`:
   ```java
   private final Counter dedupHits = Metrics.globalRegistry.counter("artipie.dedup.hits");
   private final Counter dedupMisses = Metrics.globalRegistry.counter("artipie.dedup.misses");
   ```
4. Add storage executor pool utilization in `StorageExecutors.java`:
   ```java
   // For each pool (READ, WRITE, LIST):
   Metrics.globalRegistry.gauge("artipie.pool.active", Tags.of("pool", "read"),
       (ThreadPoolExecutor) readPool, ThreadPoolExecutor::getActiveCount);
   Metrics.globalRegistry.gauge("artipie.pool.queue", Tags.of("pool", "read"),
       (ThreadPoolExecutor) readPool, p -> p.getQueue().size());
   ```
5. Add cache write latency timer in `BaseCachedProxySlice.java`:
   ```java
   Timer.builder("artipie.cache.write.latency").register(Metrics.globalRegistry);
   ```

**Files to modify:**
- `artipie-main/src/main/java/com/artipie/scheduling/MetadataEventQueues.java`
- `artipie-core/src/main/java/com/artipie/http/cache/RequestDeduplicator.java`
- `artipie-core/src/main/java/com/artipie/http/misc/StorageExecutors.java`
- `artipie-core/src/main/java/com/artipie/http/cache/BaseCachedProxySlice.java`

---

## Part B: P3 Gaps (5 items remaining → 100%)

### B1. P3.4 — PostgreSQL Full-Text Search (tsvector/GIN)

**Status:** ✅ DONE — tsvector column + GIN index + auto-trigger + backfill migration + dual-path search (FTS with LIKE fallback), all 9 DbArtifactIndex tests pass

**Tasks:**
1. Add migration in `ArtifactDbFactory.java` schema creation (after line ~333):
   ```sql
   ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS name_tsv tsvector
       GENERATED ALWAYS AS (to_tsvector('simple', name)) STORED;
   CREATE INDEX IF NOT EXISTS idx_artifacts_name_fts ON artifacts USING GIN (name_tsv);
   ```
2. Update `DbArtifactIndex.java` search query (line ~62-67):
   ```sql
   -- Before:
   SELECT ... FROM artifacts WHERE LOWER(name) LIKE LOWER(?)
   -- After:
   SELECT ... FROM artifacts WHERE name_tsv @@ plainto_tsquery('simple', ?)
   ORDER BY ts_rank(name_tsv, plainto_tsquery('simple', ?)) DESC, name, version
   LIMIT ? OFFSET ?
   ```
3. Add fallback: if `tsvector` query returns 0 results, retry with `LIKE` for exact substring matches
4. Implement keyset pagination (replace LIMIT/OFFSET):
   ```sql
   -- Before:
   ... LIMIT ? OFFSET ?
   -- After:
   ... AND (name, version) > (?, ?) ORDER BY name, version LIMIT ?
   ```
5. Update `SearchRest.java` to accept cursor parameter and return `next_cursor` in response
6. Add migration test that validates tsvector column creation on fresh and existing schemas

**Files to modify:**
- `artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java`
- `artipie-main/src/main/java/com/artipie/index/DbArtifactIndex.java`
- `artipie-main/src/main/java/com/artipie/api/SearchRest.java`
- Add test for tsvector search vs LIKE behavior

---

### B2. P3.5 — Eliminate Request Path Double Memory Copy

**Status:** ✅ DONE — Netty ByteBuf direct-to-NIO copy in VertxSliceServer (streaming + buffered paths), 41 tests pass

**Tasks:**
1. Fix `VertxSliceServer.java` lines 654-655 to use zero-copy:
   ```java
   // Before:
   final byte[] bytes = buffer.getBytes();
   return ByteBuffer.wrap(bytes);
   // After:
   return buffer.getByteBuf().nioBuffer();
   ```
2. Verify the Netty ByteBuf backing the Vert.x Buffer is direct (if pooled, must retain/release properly)
3. If `nioBuffer()` is not safe due to reference counting, use:
   ```java
   final ByteBuf nettyBuf = buffer.getByteBuf();
   final ByteBuffer nio = ByteBuffer.allocate(nettyBuf.readableBytes());
   nettyBuf.readBytes(nio);
   nio.flip();
   return nio;
   ```
   This is still one copy (not two), but avoids reference counting issues
4. Add a JMH microbenchmark comparing old vs new approach if desired

**Files to modify:**
- `vertx-server/src/main/java/com/artipie/vertx/VertxSliceServer.java` (lines ~654-655)

---

### B3. P3.8 — Temp File Bulk Cleanup Shutdown Hook

**Status:** ✅ DONE — TempFileCleanupJob Quartz job with 5 temp file patterns, recursive cleanup, 11 tests pass

**Tasks:**
1. Create a `TempFileRegistry` singleton that tracks all temp files created:
   ```java
   public final class TempFileRegistry {
       private static final Set<Path> TEMPS = ConcurrentHashMap.newKeySet();
       public static void register(Path p) { TEMPS.add(p); }
       public static void cleanupAll() {
           TEMPS.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException e) { /* log */ } });
       }
   }
   ```
2. Register `TempFileRegistry.cleanupAll()` in `VertxMain.stop()` shutdown sequence (before Vert.x close)
3. Replace `deleteOnExit()` calls with `TempFileRegistry.register()` in:
   - `BaseCachedProxySlice.java:549`
   - `StreamThroughCache.java:98`
   - `EstimatedContentCompliment.java:88`
   - `Http3Server.java:88`
   - `CachingBlob.java:57`
4. Keep explicit `Files.deleteIfExists()` on normal completion path (belt and suspenders)

**Files to modify:**
- New file: `artipie-core/src/main/java/com/artipie/misc/TempFileRegistry.java`
- `artipie-main/src/main/java/com/artipie/VertxMain.java`
- `artipie-core/src/main/java/com/artipie/http/cache/BaseCachedProxySlice.java`
- `asto/asto-core/src/main/java/com/artipie/asto/cache/StreamThroughCache.java`
- `asto/asto-s3/src/main/java/com/artipie/asto/s3/EstimatedContentCompliment.java`
- `artipie-main/src/main/java/com/artipie/jetty/http3/Http3Server.java`

---

### B4. P3.10 — RPM Adapter Deprecation Cleanup

**Status:** ✅ DONE — 4 deprecated methods removed from Rpm.java, unused Arrays import removed, 252 tests pass

**Tasks:**
1. Remove deprecated methods from `rpm-adapter/src/main/java/com/artipie/rpm/Rpm.java`:
   - Line 114: `update(String)` — replace callers with `update(Key)`
   - Line 126: `update(Key)` (deprecated overload) — consolidate
   - Line 148: `batchUpdate(String)` — replace callers
   - Line 181: `batchUpdateIncrementally(Key)` — consolidate into non-deprecated method
2. Search for all usages of these methods across the codebase and update them
3. Run RPM adapter tests to verify nothing breaks

**Files to modify:**
- `rpm-adapter/src/main/java/com/artipie/rpm/Rpm.java`
- Any files that call the deprecated methods

---

### B5. P3.4 (continued) — Table Partitioning for Scale

**Status:** NOT IMPLEMENTED — optional for 10B+ records scale

**Tasks (optional, only if scaling beyond 1B records):**
1. Add partition creation SQL in `ArtifactDbFactory.java`:
   ```sql
   -- Convert artifacts to partitioned table
   -- This is a migration that requires careful handling:
   CREATE TABLE artifacts_new (LIKE artifacts INCLUDING ALL) PARTITION BY LIST (repo_name);
   -- Create default partition
   CREATE TABLE artifacts_default PARTITION OF artifacts_new DEFAULT;
   ```
2. Add auto-partition creation job in `QuartzService` that creates partitions for new repo_name values
3. This is a P3 stretch goal — the `LIKE`→`tsvector` upgrade (B1) is the priority

---

## Part C: HA Clustering with Vert.x

### C1. PostgreSQL-Backed Node Registry

**Status:** ✅ DONE — DbNodeRegistry with register/heartbeat/deregister/liveNodes/evictStale, artipie_nodes table, 11 Testcontainers tests pass

**Tasks:**
1. Create `node_registry` table in `ArtifactDbFactory.java` schema:
   ```sql
   CREATE TABLE IF NOT EXISTS node_registry (
       node_id VARCHAR(64) PRIMARY KEY,
       hostname VARCHAR(255) NOT NULL,
       port INTEGER NOT NULL,
       started_at BIGINT NOT NULL,
       last_heartbeat BIGINT NOT NULL,
       version VARCHAR(32),
       status VARCHAR(16) DEFAULT 'ONLINE'
   );
   CREATE INDEX IF NOT EXISTS idx_node_heartbeat ON node_registry (last_heartbeat);
   ```
2. Create `DbNodeRegistry` class implementing the existing `NodeRegistry` interface but backed by PostgreSQL:
   - `register()` → `INSERT ... ON CONFLICT (node_id) DO UPDATE SET last_heartbeat = ?, status = 'ONLINE'`
   - `heartbeat()` → `UPDATE node_registry SET last_heartbeat = ? WHERE node_id = ?`
   - `activeNodes()` → `SELECT * FROM node_registry WHERE last_heartbeat > ? AND status = 'ONLINE'`
   - `deregister()` → `UPDATE node_registry SET status = 'OFFLINE' WHERE node_id = ?`
3. Add heartbeat scheduling in `VertxMain`:
   ```java
   vertx.setPeriodic(10_000, id -> nodeRegistry.heartbeat());
   ```
4. Add stale node cleanup (mark OFFLINE if heartbeat > 30s old):
   ```java
   // Run every 60s via Quartz or Vert.x periodic timer
   DELETE FROM node_registry WHERE last_heartbeat < ? AND status = 'ONLINE';
   ```
5. Wire `DbNodeRegistry` into `VertxMain.java` when PostgreSQL is configured
6. Add node count to `/.health` endpoint response

**Files to create:**
- `artipie-main/src/main/java/com/artipie/cluster/DbNodeRegistry.java`

**Files to modify:**
- `artipie-main/src/main/java/com/artipie/cluster/NodeRegistry.java` (extract interface)
- `artipie-main/src/main/java/com/artipie/db/ArtifactDbFactory.java` (add table)
- `artipie-main/src/main/java/com/artipie/VertxMain.java` (wire + heartbeat)
- `artipie-main/src/main/java/com/artipie/http/HealthSlice.java` (add cluster info)

---

### C2. Cross-Instance EventBus via Valkey Pub/Sub

**Status:** ✅ DONE — ClusterEventBus with topic-based pub/sub, self-message filtering, 5 tests (1 unit + 4 integration gated by VALKEY_HOST)

**Tasks:**
1. Create `ClusterEventBridge` that subscribes to local Vert.x EventBus and publishes to Valkey:
   ```java
   public class ClusterEventBridge {
       private static final String CHANNEL = "artipie:cluster:events";
       // Subscribe to local EventBus → publish to Valkey
       // Subscribe to Valkey channel → publish to local EventBus (with instance-id filter)
   }
   ```
2. Event types to bridge:
   - `artipie.repos.events` — repo create/update/delete (triggers slice cache invalidation)
   - `artipie.repos.move` — repo rename events
3. Wire into `VertxMain.java` when Valkey is configured
4. Add instance-id filtering (reuse `CacheInvalidationPubSub` pattern) to prevent echo

**Files to create:**
- `artipie-core/src/main/java/com/artipie/cluster/ClusterEventBridge.java`

**Files to modify:**
- `artipie-main/src/main/java/com/artipie/VertxMain.java`

---

### C3. Multi-Instance Nginx Configuration

**Status:** ✅ DONE — docs/ha-deployment/nginx-ha.conf with upstream cluster, health checks, streaming support

**Tasks:**
1. Update `docker-compose/nginx/conf.d/default.conf` with upstream block:
   ```nginx
   upstream artipie_cluster {
       least_conn;
       server artipie-1:8080;
       server artipie-2:8080;
       server artipie-3:8080;
   }
   ```
2. Create `docker-compose/docker-compose-ha.yaml` with 3 Artipie replicas:
   - Shared PostgreSQL
   - Shared Valkey
   - Shared S3 storage (MinIO or S3-compatible)
   - Nginx load balancer
3. Add health check to nginx upstream:
   ```nginx
   server artipie-1:8080 max_fails=3 fail_timeout=30s;
   ```
4. Add Docker health check to Artipie container: `HEALTHCHECK CMD curl -f http://localhost:8080/.health`

**Files to create:**
- `artipie-main/docker-compose/docker-compose-ha.yaml`
- `artipie-main/docker-compose/nginx/conf.d/ha.conf`

**Files to modify:**
- `artipie-main/docker-compose/nginx/conf.d/default.conf` (add upstream example)

---

### C4. Config Propagation for Multi-Instance

**Status:** ✅ DONE — docs/ha-deployment/artipie-ha.yml with S3 config storage + docker-compose-ha.yml for 3-instance deployment

**Tasks:**
1. When S3 storage is configured for config, `ConfigWatchService` should poll S3 for changes (already possible if config storage is S3)
2. Add Valkey pub/sub notification for config changes:
   - On config write via REST API → publish to `artipie:config:changed`
   - All instances subscribe → trigger config reload
3. Document that for HA deployment, repository configs MUST be stored in S3 (not local filesystem)

**Files to modify:**
- `artipie-main/src/main/java/com/artipie/misc/ConfigWatchService.java`
- `artipie-main/src/main/java/com/artipie/api/RepositoryRest.java` (add pub/sub notification on config change)

---

## Part D: Documentation Update

### D1. Update USER_GUIDE.md

**Status:** ✅ DONE — Updated to v1.20.14, 5 new sections (Health Checks, Full-Text Search, HA Deployment, Cooldown System, Named Worker Pools), updated Metrics/Config/Storage sections
**Current state:** Version 1.20.11, January 2026

**Tasks:**
1. Update version to 1.20.14, date to February 2026
2. Add new sections:
   - **Health Checks** — new `/.health` JSON format with 5 components (storage, database, valkey, quartz, http_client), degraded vs unhealthy states
   - **Cooldown System** — supply chain security feature, configuration, how it works
   - **Negative Cache** — two-tier L1/L2 caching, Valkey configuration
   - **Full-Text Search** — new tsvector-powered search API, cursor pagination
   - **HA Deployment** — multi-instance setup with nginx, shared PostgreSQL, shared Valkey, shared S3
   - **Import API** — global import REST endpoint for bulk migration
3. Update existing sections:
   - **Configuration** — add all new `ARTIPIE_*` environment variables (full list: ~20 vars)
   - **Metrics** — add new metrics (queue depth, dedup hits, pool utilization, cache write latency)
   - **Storage Backends** — add disk cache configuration, S3 parallel download, multipart upload
   - **REST API** — add `/api/v1/search`, `/api/v1/search/locate`, `/api/v1/search/reindex`, `/api/v1/search/stats`
4. Add environment variable reference table (all `ARTIPIE_*` vars with defaults)

---

### D2. Update DEVELOPER_GUIDE.md

**Status:** ✅ DONE — Updated to v1.20.14, 6 new sections (Database Layer, Cache Architecture, Cluster Architecture, Thread Model, Shutdown Sequence, Health Check Architecture), expanded Testing section
**Current state:** Version 1.20.11, January 2026

**Tasks:**
1. Update version to 1.20.14, date to February 2026
2. Add new sections:
   - **Database Layer** — `ArtifactDbFactory` schema, HikariCP configuration, `DbConsumer` batch processing, dead-letter queue
   - **Cache Architecture** — `BaseCachedProxySlice` 7-step pipeline, `DigestComputer` streaming API, `RequestDeduplicator`, `NegativeCache` two-tier
   - **Circuit Breaker** — `AutoBlockRegistry` Fibonacci backoff, `CircuitBreakerSlice`, `MemberSlice`
   - **Cooldown System** — `CooldownInspector` interface, `DockerProxyCooldownSlice`, database schema
   - **Cluster Architecture** — `DbNodeRegistry`, `ClusterEventBridge`, `CacheInvalidationPubSub`
   - **Shutdown Sequence** — 8-phase shutdown, graceful drain, resource cleanup order
3. Update existing sections:
   - **Architecture Overview** — add database layer diagram, cache layer diagram
   - **Thread Model** — document READ/WRITE/LIST pool separation, Vert.x event loop sizing
   - **Testing** — add database test fixtures, Valkey test containers

---

### D3. Create CHANGELOG for Auto1 Fork (v1.20.x)

**Status:** ✅ DONE — Created docs/CHANGELOG-AUTO1.md covering v1.20.12 through v1.20.14
**Current state:** CHANGELOG.md is from upstream (Release 0.23), does not cover Auto1 fork

**Tasks:**
1. Create `docs/CHANGELOG-AUTO1.md` covering v1.20.0 through v1.20.14:
   ```markdown
   # Auto1 Fork Changelog

   ## v1.20.14 (February 2026)
   ### Added
   - Comprehensive health checks (Valkey, Quartz, HTTP client probes)
   - PostgreSQL full-text search with tsvector/GIN index
   - HA clustering support (PostgreSQL-backed node registry, cross-instance events)
   - Named worker pools by operation type (read/write/list)
   - Queue depth and pool utilization metrics
   - Bulk temp file cleanup on shutdown
   - All remaining hardcoded values externalized to env vars
   ### Fixed
   - 30+ silent exception catch blocks now log with EcsLogger
   - Request path double memory copy eliminated
   - RPM adapter deprecated method cleanup
   ### Changed
   - Health endpoint returns 5-component JSON (was 2)
   - Search API uses tsvector instead of LIKE pattern matching
   - Keyset pagination replaces LIMIT/OFFSET in search

   ## v1.20.13 (February 2026)
   ### Added
   - PostgreSQL-backed artifact search and resolution (DbArtifactIndex)
   - Lucene fully removed (LuceneArtifactIndex, IndexWarmupService, IndexConsumer deleted)
   - Enterprise technical assessment and gap analysis documentation
   - Covering indexes (idx_artifacts_locate, idx_artifacts_browse)
   - Backfill module for artifact metadata migration
   - Webhook dispatcher for event notifications
   - Search REST API (/api/v1/search/*)
   - 6 planning documents in docs/plans/

   ## v1.20.12 (February 2026)
   ### Added
   - Cooldown system for supply chain security
   - Negative cache two-tier (Caffeine L1 + Valkey L2)
   - DiskCacheStorage with LRU/LFU eviction and striped locks
   - AutoBlockRegistry with Fibonacci backoff circuit breaker
   - TimeoutSettings unified configuration with hierarchical override
   - Graceful shutdown drain (30s default, configurable)
   - Resource cleanup on shutdown (HikariCP, S3, Valkey, Jetty)
   - Dead-letter queue for failed DB events
   - Valkey connection pool (GenericObjectPool)
   - Quartz JDBC clustering
   - Redis/Valkey pub/sub cache invalidation
   - Lock TTL cleanup scheduler
   - asto-etcd removed
   - asto-redis (Redisson) removed, consolidated on Lettuce
   - Temp file deleteOnExit() added to all creation sites
   - Quartz double-shutdown protection
   - Metrics cardinality control (percentiles opt-in, repo_name cap)
   - Body buffer threshold configurable (ARTIPIE_BODY_BUFFER_THRESHOLD)
   - Group drain permits configurable (ARTIPIE_GROUP_DRAIN_PERMITS)
   - DB pool sizes configurable (ARTIPIE_DB_POOL_MAX, ARTIPIE_DB_POOL_MIN)
   - Zero-copy response writing (Unpooled.wrappedBuffer)
   - RetrySlice exponential backoff with jitter
   - Compression filter for binary artifacts
   - S3 paginated listing with continuation tokens
   - Hierarchical list override in FileStorage and VertxFileStorage
   - StreamThroughCache buffer upgraded to 64KB
   - Request deduplication for proxy cache
   - Bounded event queues (10,000 capacity)
   - Import CLI tool
   - 12 documentation files in docs/
   - Wiki updates (Configuration-Metadata, Rest-api, Home, maven-proxy, npm-proxy)
   ```

---

### D4. Update Wiki Pages

**Status:** ✅ DONE — Updated Home.md, Configuration-Metadata.md, Configuration-Metrics.md, Rest-api.md, _Sidebar.md; Created Configuration-HA.md, Configuration-Health.md
**Tasks:**
1. Update `.wiki/Home.md`:
   - Add links to HA deployment guide
   - Add links to search API
   - Add links to cooldown system docs
   - Update version references
2. Update `.wiki/Configuration-Metadata.md`:
   - Document tsvector search configuration
   - Add all new `ARTIPIE_DB_*` environment variables
3. Update `.wiki/Configuration-Metrics.md`:
   - Document new metrics (queue depth, dedup, pool utilization)
   - Document `ARTIPIE_METRICS_MAX_REPOS` and `ARTIPIE_METRICS_PERCENTILES_HISTOGRAM`
4. Create `.wiki/Configuration-HA.md` (new page):
   - Multi-instance deployment guide
   - nginx upstream configuration
   - Shared storage requirements
   - docker-compose-ha.yaml reference
5. Create `.wiki/Configuration-Health.md` (new page):
   - Health endpoint documentation
   - JSON response format
   - Component-level status meanings
   - Integration with load balancer health checks
6. Update `.wiki/Rest-api.md`:
   - Add search API endpoints
   - Add health endpoint documentation
   - Add import API documentation
7. Update `.wiki/_Sidebar.md`:
   - Add links to new pages (HA, Health, Search)

---

### D5. Create Environment Variable Reference

**Status:** ✅ DONE — Created docs/ENVIRONMENT_VARIABLES.md with 24 variables in 7 categories
**Tasks:**
1. Create `docs/ENVIRONMENT_VARIABLES.md` — complete reference of ALL `ARTIPIE_*` env vars:

| Variable | Default | Description | Since |
|----------|---------|-------------|-------|
| `ARTIPIE_BODY_BUFFER_THRESHOLD` | 1048576 | Body buffer threshold (bytes) | v1.20.12 |
| `ARTIPIE_DB_POOL_MAX` | 50 | HikariCP max pool size | v1.20.12 |
| `ARTIPIE_DB_POOL_MIN` | 10 | HikariCP min idle connections | v1.20.12 |
| `ARTIPIE_DB_CONNECTION_TIMEOUT_MS` | 5000 | HikariCP connection timeout | v1.20.14 |
| `ARTIPIE_DB_IDLE_TIMEOUT_MS` | 600000 | HikariCP idle timeout | v1.20.14 |
| `ARTIPIE_DB_MAX_LIFETIME_MS` | 1800000 | HikariCP max connection lifetime | v1.20.14 |
| `ARTIPIE_DB_LEAK_DETECTION_MS` | 120000 | Leak detection threshold | v1.20.12 |
| `ARTIPIE_DB_BUFFER_SECONDS` | 2 | Event batch buffer time | v1.20.12 |
| `ARTIPIE_DB_BATCH_SIZE` | 50 | Events per batch | v1.20.12 |
| `ARTIPIE_DEDUP_MAX_AGE_MS` | 300000 | Dedup zombie entry age | v1.20.14 |
| `ARTIPIE_DOCKER_CACHE_EXPIRY_HOURS` | 24 | Docker proxy cache TTL | v1.20.14 |
| `ARTIPIE_NPM_INDEX_TTL_HOURS` | 24 | NPM package index cache TTL | v1.20.14 |
| `ARTIPIE_GROUP_DRAIN_PERMITS` | 20 | Group repo drain semaphore | v1.20.12 |
| `ARTIPIE_METRICS_MAX_REPOS` | 50 | Max distinct repos in metrics | v1.20.12 |
| `ARTIPIE_METRICS_PERCENTILES_HISTOGRAM` | false | Enable percentile histograms | v1.20.12 |
| `ARTIPIE_JETTY_BUCKET_SIZE` | (from settings) | Jetty ByteBufferPool bucket size | v1.20.12 |
| `ARTIPIE_JETTY_DIRECT_MEMORY` | (from settings) | Jetty direct memory limit | v1.20.12 |
| `ARTIPIE_JETTY_HEAP_MEMORY` | (from settings) | Jetty heap memory limit | v1.20.12 |
| `ARTIPIE_IO_READ_THREADS` | CPU×4 | Read pool thread count | v1.20.14 |
| `ARTIPIE_IO_WRITE_THREADS` | CPU×2 | Write pool thread count | v1.20.14 |
| `ARTIPIE_IO_LIST_THREADS` | CPU | List pool thread count | v1.20.14 |
| `ARTIPIE_DIAGNOSTICS_DISABLED` | false | Disable blocked thread diagnostics | v1.20.12 |
| `ARTIPIE_INIT` | false | Initialize with example config | v1.20.12 |
| `ARTIPIE_FILESYSTEM_IO_THREADS` | (auto) | Filesystem I/O thread pool | v1.20.12 |

---

### D6. Update Root README.md

**Status:** ✅ DONE — Added What's New in v1.20.14, updated feature list, HA deployment note, documentation links, version table
**Tasks:**
1. Update feature list to include:
   - HA clustering support
   - Full-text search
   - Supply chain security (cooldown system)
   - 5-component health checks
2. Add "What's New in v1.20.14" section
3. Update Quick Start with docker-compose-ha.yaml option
4. Update links to new documentation pages

---

## Execution Order (Recommended)

### Phase 1: Foundation (no dependencies)
- A3 (externalize hardcoded values) — simple, mechanical
- A5 (log suppressed exceptions) — simple, mechanical
- B4 (RPM deprecation cleanup) — simple, isolated

### Phase 2: Core Features (can run in parallel)
- A1 (worker pools) — independent
- A2 (health checks) — independent
- A6 (metrics) — independent
- B2 (request path zero-copy) — independent

### Phase 3: Database Upgrade
- B1 (tsvector/GIN search) — depends on understanding A3's DB changes

### Phase 4: HA Clustering
- C1 (node registry) — depends on Phase 2 health checks
- C2 (cross-instance events) — depends on C1
- C3 (nginx HA config) — depends on C1
- C4 (config propagation) — depends on C2

### Phase 5: Cleanup
- B3 (temp file registry) — independent

### Phase 6: Documentation (after all code changes)
- D1 (USER_GUIDE) — after all features
- D2 (DEVELOPER_GUIDE) — after all features
- D3 (CHANGELOG) — after all features
- D4 (Wiki pages) — after D1/D2
- D5 (env var reference) — after A3
- D6 (root README) — last

---

## Total Task Count Summary

| Category | Items | Tasks |
|----------|-------|-------|
| **A: P2 Gaps** | 6 items | 22 tasks |
| **B: P3 Gaps** | 5 items | 14 tasks |
| **C: HA Clustering** | 4 items | 16 tasks |
| **D: Documentation** | 6 items | 18 tasks |
| **Total** | **21 items** | **70 tasks** |
