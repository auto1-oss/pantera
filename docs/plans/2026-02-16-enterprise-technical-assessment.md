# Artipie v1.20.13 — Enterprise Technical Assessment
## Scale, Safety & Scalability

**Date:** 2026-02-16
**Scope:** Code-level analysis of hot paths, thread models, storage I/O, caching, proxy logic, database, search index, concurrency, error handling, resource lifecycle, and operational reliability.
**Method:** Full source review of ~2,051 Java classes across 23 modules with exact line references.
**Purpose:** Prepare Artipie for enterprise production at 1000 req/s, 2TB+ storage, multi-instance deployment.

---

## Table of Contents

1. [Request Lifecycle & Thread Model](#1-request-lifecycle--thread-model)
2. [Storage Layer Deep Dive](#2-storage-layer-deep-dive)
3. [Proxy Caching Pipeline](#3-proxy-caching-pipeline)
4. [Group Resolution & Fan-Out](#4-group-resolution--fan-out)
5. [Database Layer](#5-database-layer)
6. [Search & Artifact Resolution — Database-Centric Architecture](#6-search--artifact-resolution--database-centric-architecture)
7. [etcd Evaluation: Keep or Slash](#7-etcd-evaluation-keep-or-slash)
8. [Caching Architecture](#8-caching-architecture)
9. [Concurrency & Thread Safety](#9-concurrency--thread-safety)
10. [Error Handling & Resilience](#10-error-handling--resilience)
11. [Resource Lifecycle & Graceful Shutdown](#11-resource-lifecycle--graceful-shutdown)
12. [Configuration Gaps & Hardcoded Limits](#12-configuration-gaps--hardcoded-limits)
13. [Metrics & Observability](#13-metrics--observability)
14. [Throughput Model: 1000 req/s Analysis](#14-throughput-model-1000-reqs-analysis)
15. [Scalability to 2TB & Multi-Instance](#15-scalability-to-2tb--multi-instance)
16. [Prioritized Action Plan with Implementations](#16-prioritized-action-plan-with-implementations)

---

## 1. Request Lifecycle & Thread Model

### 1.1 Thread Pool Configuration

Defined in `VertxMain.java:678-691`:

| Pool | Size | Purpose |
|------|------|---------|
| Event loop | `CPU x 2` | Non-blocking I/O dispatch (8-core = 16 threads) |
| Worker pool | `max(20, CPU x 4)` | Blocking ops (8-core = 32 threads) |
| REST API verticles | `CPU x 2` instances | Management API handlers |
| Metrics scraper | 2 threads (dedicated pool `metrics-scraper`) | Prometheus scrape off event loop |
| OptimizedStorageCache I/O | `max(8, CPU x 2)` threads | Direct NIO file reads for cache |
| DiskCache cleaner | `max(2, CPU / 4)` daemon, `MIN_PRIORITY` | LRU/LFU eviction |
| ~~Lucene writer~~ | ~~1 thread~~ | **Removed** — see Section 6 |
| Config watcher | 1 daemon + 1 scheduled executor | `artipie.yml` hot-reload |
| Cooldown executor | `max(8, CPU x 4)` threads | Cooldown evaluation |
| RxFile I/O | **Unbounded** `newCachedThreadPool()` | File reads in asto-core |
| ContentAsStream I/O | **Unbounded** `newCachedThreadPool()` | Content stream conversion |

**No pool separation by operation type.** All storage operations (read, write, list, delete) share the same pools. A slow recursive `list()` blocks the same workers that serve reads.

**Unbounded thread pools:** `RxFile.java:72` and `ContentAsStream.java:43` both use `Executors.newCachedThreadPool()` which creates threads without limit. Under burst load these can exhaust OS thread limits.

**Blocked-thread detection:** 10s check interval, 5s event-loop warning, 120s worker warning (`VertxMain.java:686-691`). GC pause detection at 500ms threshold (`BlockedThreadDiagnostics.java:72` — hardcoded).

### 1.2 HTTP Server Options

`VertxSliceServer.java:139-156`:

| Option | Value | Impact |
|--------|-------|--------|
| Idle timeout | 60s | Drops idle connections |
| TCP keep-alive | true | OS-level keepalive |
| TCP no-delay | true | Nagle disabled |
| ALPN | true | HTTP/2 negotiation |
| h2c | true | HTTP/2 cleartext for NLB passthrough |
| Compression | enabled, level 6 | CPU trade for bandwidth |
| Request timeout | 2 min (configurable) | Outer safety net |

**Issue — Compression on binary artifacts:** Level 6 compression applied to ALL responses including `.jar`, `.tar.gz`, `.deb`, `.rpm` which are already compressed. Wastes CPU with zero bandwidth gain. No content-type filter.

### 1.3 Request Dispatch: The Three Paths

`VertxSliceServer.proxyHandler()` branches on `Content-Length` at line 285:

| Condition | Path | Behavior |
|-----------|------|----------|
| No body or `Content-Length: 0` | `serve()` | `Content.EMPTY`, no buffering |
| `Content-Length` < **1 MB** | `serveWithBody()` | Full body buffered into single `Buffer` |
| `Content-Length` >= **1 MB** | `serveWithStream()` | Streamed via `req.toFlowable()` |

**The 1 MB threshold is hardcoded** (line 294).

**Double memory copy on every chunk:**
- Request: `buffer.getBytes()` -> `ByteBuffer.wrap(bytes)` (`VertxSliceServer.java:474-478`)
- Response: `ByteBuffer.get(bytes)` -> `Buffer.buffer(bytes)` (`VertxSliceServer.java:1277-1281`)

A 1 GB artifact transfer creates ~2 GB of heap churn from these copies.

### 1.4 Three Timeout Layers (Current — All Hardcoded)

| Layer | Default | Status | Location |
|-------|---------|--------|----------|
| Vert.x-level | 2 min | 503 | `VertxSliceServer.java:751-808` |
| MainSlice | 60s | 500 | `MainSlice.java:107-108` |
| Per-proxy repo | 60s | 500 | Per adapter wrapping |

The inner two are redundant (both 60s). The outer 2-min is the safety net. **All three are hardcoded and use absolute timeouts** — they will kill large file downloads mid-transfer. These are replaced by the unified idle-timeout model in P1.5: all values configurable via YAML, absolute timeouts replaced with idle timeouts (data-flow-aware), and the redundant MainSlice layer removed.

### 1.5 Response Backpressure

`VertxSliceServer.java:882-931`: Uses `response.toSubscriber()` which provides production-grade backpressure via Vert.x's `WriteStreamSubscriber`. Pauses producer when write queue full, resumes on drain. Correct.

**Critical threading comment at line 856:** `"CRITICAL: Must execute on Vert.x event loop thread for thread safety. DO NOT use observeOn() - it breaks Vert.x threading model and causes corruption."`

### 1.6 Slice Cache (Repository Handler Reuse)

`RepositorySlices.java:158-188`:

| Parameter | Value |
|-----------|-------|
| Max entries | 500 |
| Expiry | 30 min after last access |
| Eviction | Closes Jetty client lease on removal |

**Issue — Cache miss blocks event loop:** `resolve()` constructs full slice tree including synchronous Jetty client startup (`RepositorySlices.java:1012`). First request to a new repo blocks the event loop.

### 1.7 Shared HTTP Client Pooling

`RepositorySlices.java:927-1087`: Clients shared by config fingerprint in `ConcurrentHashMap`. Reference counting via `AtomicInteger`.

| Parameter | Default | High-throughput | Configurable? |
|-----------|---------|----------------|---------------|
| Max connections/destination | **64** | **128** | YAML only |
| Max queued requests/destination | **256** | **512** | YAML only |
| Connect timeout | 15s | 15s | **No — hardcoded. See P1.5: → `meta.timeouts.connection_timeout`** |
| Idle timeout | 30s | 30s | **No — hardcoded. See P1.5: → `meta.timeouts.idle_timeout`** |
| Connection acquire timeout | 30s | 30s | **No — hardcoded. See P2.5: → `meta.http_client.connection_acquire_timeout`** |

Jetty ByteBufferPool: max 1024 buffers/bucket, **2 GB direct memory, 1 GB heap** — all hardcoded at `JettyClientSlices.java:269-279`.

---

## 2. Storage Layer Deep Dive

### 2.1 Storage Interface

`asto-core/Storage.java` — 12 async methods. Two listing modes:
- `list(Key prefix)` — **recursive**, all descendants — O(N log N)
- `list(Key prefix, String delimiter)` — **hierarchical**, immediate children — O(K)

**Critical:** The hierarchical `list()` is a `default` method (line 72) that falls back to recursive list + dedup. Backends must override for optimization.

### 2.2 FileStorage Performance

`asto-core/fs/FileStorage.java`:

| Operation | Implementation | Performance |
|-----------|---------------|-------------|
| Read | NIO streaming via `rio` | O(file_size) |
| Write | Temp UUID file -> atomic `Files.move()` | O(file_size), fsync |
| Recursive list | `Files.walk()` + sort | **O(N log N)** — 1M files ~120s |
| Hierarchical list | `Files.newDirectoryStream()` | **O(K)** — ~100ms |
| Exists | `Files.readAttributes()` | O(1) |
| Delete | `Files.delete()` + empty parent cleanup | O(depth) |

**Write atomicity:** Temp file in `.tmp/` with UUID, atomic move, one retry on `NoSuchFileException` race (`FileStorage.java:516-524`).

### 2.3 VertxFileStorage — Missing Hierarchical Override

**VertxFileStorage does NOT override `list(Key, String)`**. Inherits default which calls full recursive `list()` then deduplicates. Zero benefit from hierarchical listing.

### 2.4 S3Storage Thresholds

`asto-s3/S3Storage.java`:

| Parameter | Default | Line |
|-----------|---------|------|
| Multipart threshold | **16 MB** | 69, 73 |
| Part size | **16 MB** | 164 |
| Multipart concurrency | **32** | 165 |
| Parallel download threshold | **64 MB** | 171 |
| Parallel download chunk | **8 MB** | 172 |
| Parallel download concurrency | **16** | 173 |
| Parallel download | **disabled** by default | 170 |

**S3 list NOT paginated:** `list()` at line 285 uses `ListObjectsRequest` v1 — max 1000 objects, **no pagination loop**. Truncates silently.

**Move is not atomic:** Copy + delete at line 354. If delete fails after copy, data duplicates (safe side of the failure mode).

**Unknown-size uploads double-write:** `EstimatedContentCompliment.java:83-117` writes entire content to temp file first for size, then re-reads.

**Streaming reads are zero-copy:** `ResponseAdapter.onStream()` at line 690 wraps SDK publisher directly.

### 2.5 Buffer Sizes Across the Stack

| Context | Buffer Size | Location |
|---------|-------------|----------|
| OptimizedStorageCache NIO read | **1 MB** | `OptimizedStorageCache.java:41` |
| DiskCacheStorage file read | **64 KB** | `DiskCacheStorage.java:378` |
| StreamThroughCache save | **8 KB** | `StreamThroughCache.java:193` |
| Content `asInputStream()` pipe | **64 KB** | `Content.java:143` |
| S3 multipart merge | **16-32 MB** | `MultipartUpload.java:118` |
| S3 parallel download chunk | **8 MB** | `S3Storage.java:172` |

**StreamThroughCache 8 KB write buffer is 125x smaller than the 1 MB read buffer** — severe I/O asymmetry.

### 2.6 Workers Per Operation Type — Current State

**No separation exists.** All operations share the same pools:

| Pool | Used By |
|------|---------|
| Vert.x worker pool | All blocking storage ops |
| OptimizedStorageCache executor | Cache file reads |
| `newCachedThreadPool()` (RxFile) | File I/O — **unbounded** |
| ForkJoinPool.commonPool() | Default async operations |

A pathological `list()` on 500K files blocks workers that serve reads.

---

## 3. Proxy Caching Pipeline

### 3.1 BaseCachedProxySlice — The 8-Step Pipeline

`artipie-core/http/cache/BaseCachedProxySlice.java`:

```
1. Root path passthrough -> forward directly (line 208-210)
2. Negative cache check -> Caffeine L1 O(1), 404 fast-fail (line 213-216)
3. Adapter pre-process hook -> short-circuit (line 218-222)
4. Cacheability check -> isCacheable(path) (line 224-226)
5. Storage-backed decision -> if no storage/cache, fetchDirect (line 228-232)
6. Cache-first flow -> check cache, serve if hit (line 390-416)
7. Cooldown evaluation -> 403 if blocked (line 421)
8. Fetch and cache -> upstream fetch + dedup + store (line 453-503)
```

### 3.2 Content Flow Timeline — The Core Problem

**For cacheable artifacts (<=256MB) on cache miss:**
```
1. Client sends request
2. fetchAndCache() fetches from upstream (line 461)
3. isResponseTooLargeToCache() checks Content-Length (line 476)
4. cacheResponse() called (line 490)
5. LINE 592: resp.body().asBytesFuture() -- ENTIRE ARTIFACT BUFFERED INTO byte[]
6. computeDigests(bytes) computes SHA-256/SHA-1/MD5 (line 593)
7. cache.load() saves byte[] to storage (line 596-601)
8. signalToResponse() reads back from cache AGAIN (line 554)
9. Client finally receives bytes
```

**Problems:**
- Full heap buffering — 256 MB artifact = 256 MB heap per concurrent request
- Double I/O — write to cache, then read back from cache for response
- Client blocked until entire buffer+cache+readback cycle completes
- Chunked responses (no Content-Length) bypass size check — **OOM vector**

**For large artifacts (>256MB):**
```
1. Client sends request
2. fetchAndCache() fetches from upstream
3. isResponseTooLargeToCache() returns true
4. Returns streaming response directly — NO CACHING
5. Client receives bytes immediately
```

This means artifacts >256MB are **never cached** — every request hits upstream. For Docker layers, RPMs, and large binaries, this defeats the entire purpose of a proxy repository.

### 3.3 The 256 MB Cliff & Chunked Transfer OOM

`BaseCachedProxySlice.java:73-74`:
```java
private static final long MAX_CACHEABLE_SIZE = 256L * 1024 * 1024;
```

`isResponseTooLargeToCache()` (line 511-539) only checks `Content-Length` header. When absent (chunked transfer encoding — common with Docker Hub, npm registries), returns `false` — proceeds to full heap buffering regardless of actual size.

**Impact:** 10 concurrent cache misses for 1 GB chunked artifacts = 10 GB heap pressure = OOM.

### 3.4 Correct Fix: Streaming Cache with Incremental Digest Computation

The proxy repository contract requires caching. The fix must cache every artifact (that's the whole point of a proxy repo — local cache for performance and upstream failure protection) without heap buffering.

**Root cause analysis:** The byte[] buffering exists because `DigestComputer.compute(byte[], Set<String>)` at `DigestComputer.java:75` requires all bytes at once. But `java.security.MessageDigest` natively supports incremental `update()` calls — the API signature is the problem, not a computational requirement.

**Three-part implementation:**

**Part 1 — Add streaming API to DigestComputer** (`DigestComputer.java`):

```java
// Create MessageDigest instances for incremental updates
public static Map<String, MessageDigest> createDigests(Set<String> algorithms) {
    final Map<String, MessageDigest> digests = new HashMap<>(algorithms.size());
    for (final String algo : algorithms) {
        digests.put(algo, MessageDigest.getInstance(algo));
    }
    return digests;
}

// Feed a chunk — called per ByteBuffer as stream flows through
public static void updateDigests(Map<String, MessageDigest> digests, ByteBuffer buf) {
    final ByteBuffer readOnly = buf.asReadOnlyBuffer();
    for (final MessageDigest digest : digests.values()) {
        readOnly.rewind();
        digest.update(readOnly);  // JDK native method — zero copy
    }
}

// Finalize and extract hex strings after stream completes
public static Map<String, String> finalizeDigests(Map<String, MessageDigest> digests) {
    final Map<String, String> result = new HashMap<>(digests.size());
    for (final Map.Entry<String, MessageDigest> entry : digests.entrySet()) {
        result.put(entry.getKey(), HEX.formatHex(entry.getValue().digest()));
    }
    return Collections.unmodifiableMap(result);
}
```

**Part 2 — New hook in BaseCachedProxySlice:**

```java
// Subclasses override to declare which digests they need
protected Set<String> digestAlgorithms() {
    return Collections.emptySet();
}
```

Maven returns `MAVEN_DIGESTS` (SHA-256, SHA-1, MD5), Gradle returns `GRADLE_DIGESTS` (SHA-256, MD5).

**Part 3 — Rewrite `cacheResponse()` to stream through disk:**

```java
private CompletableFuture<FetchSignal> cacheResponse(
    Response resp, Key key, String owner, CachedArtifactMetadataStore store
) {
    final Path tempFile = Files.createTempFile("artipie-proxy-", ".tmp");
    final FileChannel channel = FileChannel.open(tempFile, WRITE, TRUNCATE_EXISTING);
    final Map<String, MessageDigest> digests =
        DigestComputer.createDigests(this.digestAlgorithms());
    final AtomicLong sizeCounter = new AtomicLong(0);

    // Tee: each chunk -> write to disk + update digests
    Flowable.fromPublisher(resp.body())
        .doOnNext(buf -> {
            sizeCounter.addAndGet(buf.remaining());
            DigestComputer.updateDigests(digests, buf);
            ByteBuffer copy = buf.asReadOnlyBuffer();
            while (copy.hasRemaining()) { channel.write(copy); }
        })
        .doOnComplete(() -> {
            channel.force(true);
            channel.close();
            Map<String, String> hexDigests = DigestComputer.finalizeDigests(digests);
            long size = sizeCounter.get();
            // Save from temp file to cache storage (64KB chunks)
            saveToCacheFromTempFile(key, tempFile, size, hexDigests,
                                    resp.headers(), owner, store);
        })
        .doOnError(err -> {
            closeQuietly(channel);
            deleteTempFileQuietly(tempFile);
        })
        .subscribe();

    return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
}
```

**Result:**

| Metric | Before | After |
|--------|--------|-------|
| Heap per concurrent request | Up to 256 MB (or unlimited for chunked) | ~0 bytes |
| Artifact cached? | Yes (<=256MB), No (>256MB) | **Yes — always** |
| Digests computed? | Yes (byte[]) | Yes (streaming MessageDigest) |
| Client latency | Waits for full buffer+cache round-trip | Streams immediately |
| MAX_CACHEABLE_SIZE cliff | Required | Can be removed |
| Chunked transfer | OOM vector | Safe |
| Digest correctness | SHA-256/SHA-1/MD5 over byte[] | Mathematically identical — `update()` is incremental |
| Atomic cache entry | Yes | Yes — doOnComplete saves from complete temp file |

**Existing code to leverage:** `StreamThroughCache.java` already implements the temp-file-tee pattern with NIO FileChannel. The gap is only that it doesn't compute digests during streaming and isn't wired into the proxy path.

### 3.5 Request Deduplication

`RequestDeduplicator.java:42-131`:

- `ConcurrentHashMap<Key, InFlightEntry>` — lock-free
- `putIfAbsent()` — first request fetches, all others wait on shared `CompletableFuture`
- Zombie protection: 5-minute `MAX_AGE_MS`, 60s cleanup cycle
- Effective: 1000 requests for same artifact = 1 upstream fetch

**Subtle race:** `inFlight.remove(key)` at line 94 happens before `fresh.complete(signal)` at line 99. A request arriving in this micro-window starts a second fetch. Harmless but measurable under extreme concurrency.

### 3.6 signalToResponse — Double Read Penalty

`BaseCachedProxySlice.java:544-580`:

After `cacheResponse()` saves to storage, `signalToResponse()` at line 554 reads back from cache to build the HTTP response. This is a **double I/O penalty**: write to cache, then read from cache. The streaming fix eliminates this by letting the client receive bytes directly from the tee stream.

---

## 4. Group Resolution & Fan-Out

### 4.1 Parallel Race Strategy

`GroupSlice.java:49`: All members queried simultaneously. First non-404 wins via `CompletableFuture.complete()` (atomic first-writer-wins).

Body buffered via `body.asBytesFuture()` at line 69 for fan-out. For GET (empty body): negligible. For PUT: full buffering.

Late arrival cleanup: losing responses consumed via `res.body().asBytesFuture()` (line 106) — bounded by `DRAIN_PERMITS` semaphore (20 permits, hardcoded — `GroupSlice.java:63`).

### 4.2 No Per-Member Timeout or Circuit Breaker

**No timeout on individual members.** A 5-member group where members 1-4 return 404 in 1ms and member 5 hangs: group blocks for 60s (proxy timeout) before returning 404.

**MemberSlice circuit breaker** (`MemberSlice.java`): 5-failure threshold, 30s reset — both hardcoded.

**Industry reference:** Both JFrog Artifactory and Sonatype Nexus use **idle timeouts** (not absolute timeouts) for member/remote resolution. The idle timeout only fires when no data flows over the socket for N seconds — this naturally allows large file downloads to complete while still detecting hung connections. Nexus additionally implements **auto-blocking** of unresponsive remotes with Fibonacci backoff (40s → 60min), preventing repeated timeout penalties on known-dead members. See P1.5 for the unified timeout model that applies these patterns across all of Artipie's outbound connections.

### 4.3 Fan-Out Amplification

1000 requests to 5-member group = 5,000 outbound HTTP requests. With 64 connections/destination: 320 active, 1,280 queued, remaining fail with connection-acquire timeout.

---

## 5. Database Layer

### 5.1 HikariCP Configuration

`ArtifactDbFactory.java:197-216`:

| Setting | Value | Configurable |
|---------|-------|-------------|
| `maximumPoolSize` | **50** | YAML only |
| `minimumIdle` | **10** | YAML only |
| `connectionTimeout` | **30s** | **Hardcoded** |
| `idleTimeout` | **10 min** | **Hardcoded** |
| `maxLifetime` | **30 min** | **Hardcoded** |
| `leakDetectionThreshold` | **2 min** | **Hardcoded** |
| JMX MBeans | enabled | — |

**No env var support** for pool sizes. No shutdown — see Section 11.

### 5.2 Schema & Indexes

Three tables: `artifacts`, `artifact_cooldowns`, `import_sessions`. 10 indexes (`ArtifactDbFactory.java:307-408`). All `CREATE IF NOT EXISTS` — no migration framework.

All SQL uses `PreparedStatement` with `?` — no injection risk.

### 5.3 Batch Processing

`DbConsumer.java:70`: RxJava `buffer(2s, 50 events)`:
- Events sorted by `(repo_name, name, version)` before batch to **prevent deadlocks** (line 137-144)
- Explicit transaction: `setAutoCommit(false)` at line 163, `commit()` at 202
- After 3 consecutive failures: events **silently dropped** (line 206-226)

**No dead-letter queue, no alerting, no exponential backoff** on batch failures.

### 5.4 Unbounded Event Queues

`EventQueue.java` uses `ConcurrentLinkedQueue` with **no capacity limit**. `MetadataEventQueues.java:115` uses `ConcurrentLinkedQueue<ProxyArtifactEvent>` — **no backpressure**.

Under burst load (1000 concurrent cache misses), these queues grow without bound until OOM.

---

## 6. Search & Artifact Resolution — Database-Centric Architecture

### 6.1 Industry Analysis: How JFrog and Nexus Solve This

Neither JFrog Artifactory nor Sonatype Nexus uses a separate search index (Lucene/Elasticsearch) for the critical artifact resolution path:

| Product | Resolution mechanism | Search mechanism | Separate search engine |
|---------|---------------------|-----------------|----------------------|
| **JFrog Artifactory** | PostgreSQL metadata DB queries (1-8 queries per request) | AQL — SQL-based query language against metadata DB | **None** — database is the index |
| **Sonatype Nexus 3** | PostgreSQL/H2 ordered member iteration + first match | SQL search (removed Elasticsearch in v3.88.0) | **Removed** — moved fully to SQL |
| **Artipie (current)** | Per-instance Lucene MMapDirectory | Lucene full-text on `artifact_name` | Lucene — local, stale, no coordination |

**Key insight from JFrog:** *"All Artifactory requests are translated into database queries. Storing metadata in the database means operations are always up to date, blazingly fast, and never broken."* Their checksum-based storage architecture separates binary storage (filestore) from metadata (PostgreSQL), with the database serving as the single source of truth for artifact location, resolution, and search.

**Key insight from Nexus:** Sonatype explicitly removed Elasticsearch in v3.88.0 and moved fully to SQL search. Their reasoning: the database already has the metadata, maintaining a separate search index adds complexity with no benefit at their scale.

### 6.2 Current Lucene Architecture — Problems

The current `LuceneArtifactIndex` has fundamental problems that cannot be fixed incrementally:

| Problem | Impact | Fixable? |
|---------|--------|----------|
| Per-instance local index (MMapDirectory) | Each instance has different data — stale, inconsistent | No — architectural |
| No DB→Lucene sync pipeline | Lucene diverges from PostgreSQL over time | Would require building what the DB already does |
| Warmup from storage keys produces garbage | `version=""`, `size=0`, `owner=""`, `created_at=now()` | Only with backfill data — which lives in the DB |
| Per-document fsync on publish | 100 fsyncs/sec bottleneck on NFS/EFS | Fixable but irrelevant if we remove Lucene |
| Search on `ForkJoinPool.commonPool()` | Contends with all async operations | Fixable but irrelevant |
| 3.5 TB index at 10B records | Unviable on single node | Not fixable — Lucene doesn't scale horizontally |
| No multi-instance coordination | Index corruption if two instances share MMapDirectory | Architectural limitation |
| Silent error swallowing (`search`, `locate`) | Index corruption invisible | Fixable but irrelevant |

### 6.3 Target Architecture — PostgreSQL as the Single Index

**Replace Lucene entirely with PostgreSQL queries.** The `artifacts` table already contains all metadata. The database is the shared, always-consistent source of truth.

```
Current (broken):
  GroupSlice → Lucene locate(path) → targeted fan-out
  /api/v1/search → Lucene full-text → results
  Problems: local, stale, no warmup, no coordination, 3.5TB per instance

Target (industry-proven):
  GroupSlice → PostgreSQL query → targeted fan-out
  /api/v1/search → PostgreSQL full-text search → results
  Benefits: shared, always consistent, zero warmup, horizontally scalable
```

**Components to remove:**

| Component | File | Replacement |
|-----------|------|-------------|
| `LuceneArtifactIndex` | `artipie-main` | `DbArtifactIndex` — queries PostgreSQL |
| `IndexWarmupService` | `artipie-main` | **Delete** — no warmup needed, DB is always hot |
| `IndexConsumer` | `artipie-main` | **Delete** — `DbConsumer` already writes to PostgreSQL |
| `ArtifactIndex` interface | `artipie-core` | Keep interface, new DB implementation |
| Lucene dependencies | `pom.xml` | Remove `lucene-core`, `lucene-queryparser`, `lucene-analysis-common` |

**Components to add/modify:**

| Component | Purpose |
|-----------|---------|
| `DbArtifactIndex` | Implements `ArtifactIndex` via JDBC queries against `artifacts` table |
| `GroupSlice` | Replace `idx.locate()` with `DbArtifactIndex.locate()` — identical interface |
| `SearchRest` | Query PostgreSQL instead of Lucene — same REST API |
| Schema migration | Add `tsvector` column, GIN index, covering indexes, table partitioning |

### 6.4 PostgreSQL Schema for Search & Resolution

**Minimum PostgreSQL version: 16** (for incremental sort, SIMD-accelerated text processing, improved GIN index performance). **Recommended: PostgreSQL 17** (17.4+):

| PG 17 Feature | Benefit for Artipie |
|---|---|
| **Streaming I/O for sequential scans** | 27% faster on large partitions — directly benefits browse/search queries over `artifacts` partitions with hundreds of millions of rows |
| **Vacuum 20x less memory** | Autovacuum on partitions with 50-500M rows runs without memory pressure; critical for `repo_name` LIST partitions under continuous upsert load |
| **Lock after partition pruning** | Concurrent requests to different repos (`WHERE repo_name = ?`) only lock the target partition — eliminates cross-repo contention |
| **Parallel BRIN index builds** | Faster index creation during backfill and partition maintenance |
| **Improved B-tree IN clause handling** | Benefits multi-repo lookups (`WHERE repo_name IN (...)`) in group repo resolution |
| **2x write throughput** | Directly benefits backfill bulk inserts and runtime `DbConsumer` upserts |

**Not yet recommended: PostgreSQL 18.x** — The AIO (async I/O) subsystem offers up to 3x I/O improvement and skip scan for B-tree, but PG 18.0 GA was September 2025 (only ~5 months old). AIO is a fundamental plumbing change; allow one more minor release cycle (target 18.2+, mid-2026) before adopting. Enterprise PostgreSQL providers (EDB, Crunchy, AWS RDS, Cloud SQL) are still certifying PG 18.

#### 6.4.1 Full-Text Search Column

```sql
-- Add generated tsvector column for full-text search on artifact name
ALTER TABLE artifacts ADD COLUMN IF NOT EXISTS
    name_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', name)) STORED;

-- GIN index for full-text search queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artifacts_name_fts
    ON artifacts USING GIN (name_tsv);
```

The `'simple'` dictionary avoids stemming — artifact names like `commons-lang3` should match exactly, not be stemmed to `common-lang`. The `GENERATED ALWAYS AS ... STORED` column auto-updates on INSERT/UPDATE with zero application code changes.

#### 6.4.2 Covering Indexes for locate() and search()

```sql
-- locate() query: "which repos have this artifact?"
-- Covering index — PostgreSQL answers entirely from the index (index-only scan)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artifacts_locate
    ON artifacts (name, repo_name)
    INCLUDE (repo_type);

-- Search by repo + name prefix (browsing)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artifacts_browse
    ON artifacts (repo_name, name, version)
    INCLUDE (size, created_date, owner);

-- Drop redundant indexes that the new ones subsume
DROP INDEX IF EXISTS idx_artifacts_repo_lookup;        -- subsumed by idx_artifacts_browse
DROP INDEX IF EXISTS idx_artifacts_repo_type_name;     -- subsumed by idx_artifacts_locate
```

#### 6.4.3 Table Partitioning for 10B Records

```sql
-- Partition by repo_name using list partitioning
-- Each repository gets its own partition — vacuums independently,
-- indexes fit in memory, queries skip irrelevant partitions
CREATE TABLE artifacts_partitioned (
    id BIGSERIAL,
    repo_type VARCHAR NOT NULL,
    repo_name VARCHAR NOT NULL,
    name VARCHAR NOT NULL,
    version VARCHAR NOT NULL,
    size BIGINT NOT NULL,
    created_date BIGINT NOT NULL,
    release_date BIGINT,
    owner VARCHAR NOT NULL,
    name_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', name)) STORED,
    PRIMARY KEY (repo_name, id),
    UNIQUE (repo_name, name, version)
) PARTITION BY LIST (repo_name);

-- Auto-create partitions for each repo (done by application on first insert)
-- Example:
CREATE TABLE artifacts_p_maven_central PARTITION OF artifacts_partitioned
    FOR VALUES IN ('maven-central');
CREATE TABLE artifacts_p_docker_hub PARTITION OF artifacts_partitioned
    FOR VALUES IN ('docker-hub');
-- Default partition for repos without explicit partition
CREATE TABLE artifacts_p_default PARTITION OF artifacts_partitioned DEFAULT;
```

**Why LIST partitioning by repo_name:**
- Every query includes `repo_name` (locate, search, browse) — partition pruning eliminates all irrelevant data
- Each partition has its own indexes, vacuum, and autovacuum — no global bloat
- A 10B-row table with 200 repos = ~50M rows per partition average — well within PostgreSQL's comfort zone
- Partitions can be on different tablespaces (fast NVMe for hot repos, slower storage for archives)

#### 6.4.4 Query Patterns

**`locate(name)` — Used by GroupSlice for targeted fan-out:**
```sql
-- Sub-millisecond: index-only scan on idx_artifacts_locate
SELECT DISTINCT repo_name FROM artifacts WHERE name = $1;
```

**`search(query)` — Full-text search via REST API:**
```sql
-- Full-text search with ranking, paginated via keyset
SELECT repo_type, repo_name, name, version, size, created_date, owner,
       ts_rank_cd(name_tsv, query) AS rank
FROM artifacts, to_tsquery('simple', $1) query
WHERE name_tsv @@ query
ORDER BY rank DESC, name, version
LIMIT $2;
```

**`search(query)` with cursor pagination (deep pages):**
```sql
-- Keyset pagination — constant performance at any depth
SELECT repo_type, repo_name, name, version, size, created_date, owner
FROM artifacts
WHERE name_tsv @@ to_tsquery('simple', $1)
  AND (name, version) > ($2, $3)    -- cursor from previous page
ORDER BY name, version
LIMIT $4;
```

**`browse(repo, prefix)` — Package listing:**
```sql
-- Browse artifacts in a repo with optional name prefix
SELECT name, version, size, created_date, owner
FROM artifacts
WHERE repo_name = $1
  AND name LIKE $2 || '%'
ORDER BY name, version
LIMIT $3 OFFSET $4;
```

### 6.5 Performance at Scale

#### 10 Billion Records

| Query | Index used | Expected latency | Notes |
|-------|-----------|-----------------|-------|
| `locate(name)` | `idx_artifacts_locate` (B-tree, covering) | **<1ms** | Index-only scan, no table access |
| `search(query)` full-text | `idx_artifacts_name_fts` (GIN) | **10-50ms** | GIN index scan + heap fetch |
| `browse(repo, prefix)` | `idx_artifacts_browse` (B-tree, covering) | **<5ms** | Partition-pruned, index-only scan |
| `UPSERT` single | Unique constraint index | **<1ms** | Single-row insert with conflict check |
| `UPSERT` batch (1000) | Unique constraint index | **5-15ms** | Batch within single transaction |

#### Table Size Estimate (10B rows)

| Component | Size per row | Total (10B rows) |
|-----------|-------------|-------------------|
| Table data | ~200 bytes | ~2 TB |
| `idx_artifacts_locate` | ~60 bytes | ~600 GB |
| `idx_artifacts_browse` | ~100 bytes | ~1 TB |
| `idx_artifacts_name_fts` (GIN) | ~40 bytes | ~400 GB |
| Unique constraint index | ~60 bytes | ~600 GB |
| **Total** | | **~4.6 TB** |

With LIST partitioning by repo_name (200 repos): **~23 GB per partition average** — fits entirely in RAM on a 64GB database server.

#### Comparison: Lucene vs PostgreSQL at 10B Records

| Aspect | Lucene (current) | PostgreSQL (target) |
|--------|-----------------|-------------------|
| Index size per instance | ~3.5 TB | 0 (shared DB) |
| Total storage | 3.5 TB × N instances | ~4.6 TB shared |
| Warmup time | Hours (full storage scan) | **Zero** |
| Multi-instance consistency | Stale, divergent | **Always consistent** |
| locate() latency | ~0.1ms (in-process) | ~1-3ms (network) |
| search() latency | ~5ms | ~10-50ms |
| Operational complexity | MMapDirectory per instance, no monitoring | Standard PostgreSQL ops, pgBouncer, replicas |
| Failure mode | Silent corruption, empty results | SQL errors, connection failures — visible |
| Horizontal scaling | Not possible | Read replicas, partitioning, pgBouncer |

The 1-3ms network penalty for `locate()` is irrelevant — the subsequent artifact download (storage read, network transfer) takes 10-1000ms. Zero warmup and multi-instance consistency far outweigh the in-process speed advantage.

### 6.6 Concurrency & Connection Management

#### PgBouncer (Required for Multi-Instance)

Direct HikariCP connections from N instances each with pool_max=50 = 50×N connections to PostgreSQL. At 10 instances = 500 connections — excessive.

```ini
# pgbouncer.ini
[pgbouncer]
pool_mode = transaction          ; release connection after each transaction
max_client_conn = 1000           ; total client connections across all instances
default_pool_size = 50           ; actual PostgreSQL connections
reserve_pool_size = 10           ; burst headroom
reserve_pool_timeout = 3         ; seconds before using reserve
server_idle_timeout = 300        ; close idle server connections
```

**Transaction pooling mode** is critical: connections are returned to the pool after each transaction, not held for the session. This means 10 instances × 50 HikariCP connections = 500 logical connections, but PgBouncer multiplexes them into just 50 actual PostgreSQL connections.

#### PostgreSQL Server Tuning (10B rows, 64GB RAM)

```
# postgresql.conf
shared_buffers = 16GB                    # 25% of RAM
effective_cache_size = 48GB              # 75% of RAM (OS page cache)
work_mem = 64MB                          # per-sort/hash operation
maintenance_work_mem = 2GB               # for VACUUM, CREATE INDEX
max_connections = 60                     # pgBouncer manages multiplexing
max_parallel_workers_per_gather = 4      # parallel query for large scans
max_parallel_workers = 8
max_parallel_maintenance_workers = 4     # parallel index builds
random_page_cost = 1.1                   # SSD/NVMe (not spinning disk)
effective_io_concurrency = 200           # NVMe
wal_buffers = 64MB
checkpoint_completion_target = 0.9
default_statistics_target = 500          # better query plans for large tables
jit = on                                 # JIT compilation for complex queries

# Autovacuum tuning for high-write workload
autovacuum_max_workers = 6
autovacuum_naptime = 10s
autovacuum_vacuum_threshold = 5000
autovacuum_vacuum_scale_factor = 0.01    # vacuum at 1% dead tuples (not default 20%)
autovacuum_analyze_threshold = 5000
autovacuum_analyze_scale_factor = 0.005
```

#### Read Replicas for Search Queries

```
                  ┌──────────────┐
                  │   PgBouncer  │
                  └──────┬───────┘
                         │
              ┌──────────┴──────────┐
              │                     │
     ┌────────┴────────┐   ┌───────┴────────┐
     │  Primary (RW)   │   │  Replica (RO)  │
     │  - UPSERT       │   │  - locate()    │
     │  - backfill     │   │  - search()    │
     │  - event writes │   │  - browse()    │
     └─────────────────┘   └────────────────┘
```

Write operations (UPSERT from `DbConsumer`, backfill from `BatchInserter`) go to the primary. Read operations (`locate`, `search`, `browse`) go to the replica. PgBouncer can route based on query type, or the application can use separate DataSource instances.

### 6.7 Migration Path

**Phase 1 — Add PostgreSQL search capability (non-breaking):**
1. Add `name_tsv` column and GIN index to existing `artifacts` table
2. Add covering indexes (`idx_artifacts_locate`, `idx_artifacts_browse`)
3. Implement `DbArtifactIndex` as new `ArtifactIndex` implementation
4. Wire `DbArtifactIndex` in `YamlSettings` alongside Lucene (feature flag)
5. Backfill historical data using `artipie-backfill` tool

**Phase 2 — Switch to PostgreSQL (Lucene becomes fallback):**
1. Default to `DbArtifactIndex` in configuration
2. Lucene available as `artifact_index.type: lucene` for rollback
3. Monitor `locate()` latency — should be <5ms P99

**Phase 3 — Remove Lucene entirely:**
1. Remove `LuceneArtifactIndex`, `IndexWarmupService`, `IndexConsumer`
2. Remove Lucene dependencies from `pom.xml`
3. Remove `artifact_index.directory` config option
4. Implement table partitioning for 10B scale

**Phase 4 — Partitioning for 10B scale:**
1. Create partitioned table `artifacts_partitioned`
2. Migrate data: `INSERT INTO artifacts_partitioned SELECT * FROM artifacts`
3. Rename tables atomically: `ALTER TABLE artifacts RENAME TO artifacts_old; ALTER TABLE artifacts_partitioned RENAME TO artifacts;`
4. Application auto-creates partitions on first insert to new repo

---

## 7. etcd Evaluation: Keep or Slash

### 7.1 Assessment

| Operation | Implementation | Problem |
|-----------|---------------|---------|
| `save` | `content.asBytesFuture()` + `KVClient.put()` | **10 MB limit** (line 48) |
| `list` | `KVClient.get()` keysOnly, **no pagination** | All keys at once |
| `move` | read -> save -> delete (**3 ops, NOT atomic**) | Inconsistency window |
| Watch API | **NOT USED** | Main advantage unused |

### 7.2 Verdict: Slash

The disabled test `@Disabled("FOR_REMOVING")` confirms the team's intent. etcd's value is distributed consensus and Watch — neither used.

**Remove `asto-etcd`.** When HA clustering is needed, introduce `EtcdClusterService` using Watch API for config propagation and leader election — not as a generic storage backend.

---

## 8. Caching Architecture

### 8.1 Multi-Tier Cache Map

```
Request -> Negative Cache (L1 Caffeine, L2 Valkey)
  | miss
Request -> Artifact Cache (L1 DiskCacheStorage, L2 FromStorageCache)
  | miss
Request -> Proxy Upstream (with deduplication)
  | response
Write-through -> Artifact Cache + Negative Cache invalidation
```

### 8.2 Negative Cache Tiers

`NegativeCache.java`:

| Tier | Technology | Max | TTL |
|------|-----------|-----|-----|
| L1 | Caffeine (Window TinyLFU) | 50,000 / 5,000 (two-tier) | 24h / 5 min |
| L2 | Valkey (Lettuce) | 5,000,000 | 7 days |

L2 value: 1-byte sentinel. L2 timeout: 100ms with graceful fallback. `isNotFound()` checks L1 only — non-blocking.

### 8.3 DiskCacheStorage (S3 Acceleration)

`asto-s3/DiskCacheStorage.java`:

| Parameter | Value |
|-----------|-------|
| Lock stripes | 256 |
| Read buffer | 64 KB |
| Orphan threshold | 1 hour |
| Eviction | LRU or LFU (configurable) |

**Stream-through pattern:** Content delivered to caller as it arrives from S3, simultaneously written to disk via FileChannel. No double-buffering.

### 8.4 FromStorageCache — OOM Risk

`FromStorageCache.java:105`: `teeContent()` uses `ByteArrayOutputStream` to accumulate entire artifact in heap while streaming to caller. This is the in-memory fallback.

### 8.5 Valkey L2 — Single Connection Bottleneck

`ValkeyConnection.java`: Single `StatefulRedisConnection<String, byte[]>`, auto-flush, 100ms timeout. Under 1000 req/s with cache misses, single-threaded write becomes bottleneck.

---

## 9. Concurrency & Thread Safety

### 9.1 Critical Blocking Calls

| Location | Call | Severity | Impact |
|----------|------|----------|--------|
| `RetrySlice.java:130` | `.join()` in `exceptionally()` | **CRITICAL** | Blocks ForkJoinPool.commonPool() — cascades to ALL async ops |
| `MergeShardsSlice.java:146` | `.join()` in `thenApply()` | **CRITICAL** | Blocks event loop during PyPI shard merge — total service deadlock |
| `Completables.java:110` | `tuple._2().join()` | **HIGH** | Blocks in reactive pipeline (Conan adapter) |

### 9.2 Synchronized Block Hotspots

| Location | Lock | Risk |
|----------|------|------|
| `JwtPasswordAuthFactory.java:107,122` | `static VERTX_LOCK` | **HIGH** — serializes all JWT auth under load |
| `MergeShardsSlice.java:609` | `synchronized(chartVersions)` | **HIGH** — check-then-act race: `isEmpty()` outside lock |
| `GroupSliceMetrics.java:25` | `synchronized(class)` | MEDIUM — singleton init |
| `YamlSettings.java:245` | `synchronized(this)` | MEDIUM — settings access |
| `Pipeline.java:56-136` | 7 synchronized blocks | MEDIUM — backpressure |
| `MultiPart.java:131-228` | 7 synchronized blocks | MEDIUM — multipart state |

### 9.3 Race Condition in MergeShardsSlice

`MergeShardsSlice.java:609-612`:
```java
if (!versions.isEmpty()) {           // <-- CHECK outside lock
    synchronized (chartVersions) {   // <-- ACT inside lock
        chartVersions.put(chart, versions);
    }
}
```

The `isEmpty()` check is outside the synchronized block. Under concurrent helm chart processing, this is a check-then-act race.

### 9.4 Semaphore Limits (Hardcoded)

| Location | Permits | Purpose |
|----------|---------|---------|
| `GroupSlice.java:63` | 20 | Response body drain concurrency |
| `MetadataMerger.java:86` | 250 | Maven metadata merge concurrency |

Both hardcoded. Under extreme load, 20 drain permits can starve group response processing.

### 9.5 Unbounded Thread Pools

| Location | Pool Type | Risk |
|----------|-----------|------|
| `RxFile.java:72` | `newCachedThreadPool()` | Thread explosion under burst I/O |
| `ContentAsStream.java:43` | `newCachedThreadPool()` | Thread explosion under burst streaming |

---

## 10. Error Handling & Resilience

### 10.1 Circuit Breaker & Auto-Block

`CircuitBreakerSlice.java`:

| Parameter | Value | Configurable |
|-----------|-------|-------------|
| Failure threshold | 5 | **No** |
| Recovery timeout | 1 min | **No** |
| Trigger | 5xx + exceptions | — |

Count-based, not rate-based. A flaky upstream returning 503 once per minute opens the circuit after 5 failures regardless of the 99.9% success rate.

**Required redesign — Unified Auto-Block Model:** Replace the current simple count-based circuit breaker with an auto-block model (industry standard — used by both Nexus and Artifactory):

1. When a remote/member fails or times out, mark it as **auto-blocked**
2. Backoff uses Fibonacci sequence: 40s, 40s, 80s, 120s, 200s, ... capped at configurable max (default 60min)
3. During auto-block, skip the remote entirely (return 404 immediately for group members, or return cached-if-available for proxy remotes)
4. After backoff expires, allow **one probe request** (half-open state). If it succeeds, unblock. If it fails, re-block with next Fibonacci interval.
5. All parameters configurable per repository via YAML (see P1.5 and P1.7)

This model applies consistently to: proxy remote repositories, group member repositories, and any outbound HTTP dependency.

### 10.2 Retry Logic

`RetrySlice.java`:

| Parameter | Value |
|-----------|-------|
| Max retries | 2 (but `ProxyCacheConfig` defaults to **0** — disabled) |
| Initial delay | 100ms |
| Backoff multiplier | 2.0 |
| Jitter | **None** |

**Critical bug:** `.join()` at line 130 blocks `ForkJoinPool.commonPool()` — see Section 9.1.

### 10.3 Fire-and-Forget Operations Without Error Handling

| Location | Pattern | Risk |
|----------|---------|------|
| `MergeShardsSlice.java:535-602` | `.thenAccept()` with no `.exceptionally()` | Silent shard data loss |
| `MergeShardsSlice.java:315-325` | Cleanup in `.exceptionally()` with nested fire-and-forget | Orphaned `.meta`/`.import` dirs accumulate |
| `BaseCachedProxySlice.java:206-226` | Cache save in background | Acceptable — logged |

### 10.4 Silent Exception Swallowing

| Location | What's Swallowed | Impact |
|----------|-----------------|--------|
| `LuceneArtifactIndex.java:190-191` | Search exceptions | Queries return empty — corruption invisible |
| `LuceneArtifactIndex.java:222-223` | Locate exceptions | Same |
| `HealthSlice.java:74-76` | All health check exceptions | Health always reports "down" — no root cause |
| `composer CachedProxySlice.java:583-585` | JSON parse errors | Version comparison silently fails |

### 10.5 Overload Protection — Handled at Infrastructure Level

Vert.x's event loop model does not spawn a thread per request — it naturally handles high connection counts without thread exhaustion. The worker pool (`max(20, CPU*4)`) caps blocking operations. Actual overload protection in production is handled by:
- Reverse proxy / load balancer connection limits and rate limiting (nginx, HAProxy, ALB)
- Jetty client connection pool limits (64 connections, 256 queued per destination — already configured)
- Bounded event queues (see P0.5)
- Health check + horizontal scaling at the LB layer

Application-level load shedding (counting active requests and rejecting above a threshold) is **not** standard practice for artifact repository managers — neither Artifactory nor Nexus implement it. The bottlenecks are I/O-bound (disk, S3, upstream), not request-count-bound, so a simple request counter is a poor proxy for actual system pressure.

### 10.6 Health Check — Too Narrow

`HealthSlice.java`: Only checks storage write. Does NOT check: database, Redis/Valkey, upstream connectivity, Quartz scheduler.

---

## 11. Resource Lifecycle & Graceful Shutdown

### 11.1 Shutdown Sequence

`VertxMain.java:534-542` registers shutdown hook calling `app.stop()`. The `stop()` method (lines 437-486):

1. Stops all VertxSliceServer instances
2. Stops Quartz scheduler (waits for jobs — `shutdown(true)`)
3. Closes ConfigWatchService (5-second grace period)
4. Closes Settings and storage resources

### 11.2 No Graceful Drain

**VertxSliceServer.stop()** at line 229-242 calls `server.rxClose().blockingAwait()` immediately. No drain period — in-flight requests are abruptly terminated. No mechanism to stop accepting new requests while completing existing ones.

### 11.3 Resources Never Closed on Shutdown

| Resource | Created | Closed on Shutdown? |
|----------|---------|-------------------|
| **Vert.x instance** | `VertxMain.vertx()` (line 727) | **NO — major leak** |
| **HikariDataSource** | `ArtifactDbFactory.initialize()` | **NO — Settings.close() is no-op** |
| **Storage instances** | Settings factory | **NO** |
| **Valkey connections** | ValkeyConnection | **NO explicit close** |
| ~~**Lucene IndexWriter**~~ | ~~LuceneArtifactIndex~~ | **Removed** — see Section 6 |
| Jetty HTTP clients | RepositorySlices | Yes — two-phase stop/destroy |
| Quartz scheduler | QuartzService | Yes — but double shutdown hook |
| ConfigWatchService | VertxMain | Yes — 5s grace period |

`Settings.java:34-37`: `default void close() {}` — no-op. Implementations don't override. This means HikariCP connection pools, S3AsyncClient connections, and Valkey connections leak on every restart.

### 11.4 Temp File Leaks

- **HTTP/3 server:** `Http3Server.java:87` creates temp directory `http3-pem-*` — never deleted on shutdown
- **StreamThroughCache:** Temp files cleaned in `doOnComplete`/`doOnError` — correct for happy path, but process kill mid-stream leaves orphans
- **No `deleteOnExit()` calls found** in the codebase for any temp files
- **S3 unknown-size uploads:** `EstimatedContentCompliment.java` creates temp files — cleaned on completion but not on crash

### 11.5 Quartz Double Shutdown

`QuartzService.java:49-66` registers its own JVM shutdown hook. `VertxMain.stop()` also calls `quartzService.stop()`. No guard against double invocation — `scheduler.shutdown()` called twice.

---

## 12. Configuration Gaps & Hardcoded Limits

### 12.1 Configuration Readiness Score

| Category | Configurable | Score |
|----------|-------------|-------|
| Timeouts | 40% (some YAML, few env vars) | 3/10 |
| Pool sizes | 30% (YAML only) | 2/10 |
| Memory limits | 20% (minimal) | 1/10 |
| Cache config | 50% (some YAML) | 4/10 |
| Queue limits | 10% (mostly hardcoded) | 1/10 |
| Retry logic | 30% (some YAML) | 2/10 |
| **Overall** | **35%** | **2.4/10** |

### 12.2 Critical Hardcoded Values

**Timeouts (not configurable — all to be externalized per P1.5 / P2.5):**

| Value | Location | Impact | Target Config Key |
|-------|----------|--------|-------------------|
| 30s DB connection timeout | `ArtifactDbFactory.java:203` | DB outage blocks workers 30s each | `meta.db.connection_timeout` |
| 10 min DB idle timeout | `ArtifactDbFactory.java:204` | May be too long for cloud | `meta.db.idle_timeout` |
| 30 min DB max lifetime | `ArtifactDbFactory.java:205` | Not tunable | `meta.db.max_lifetime` |
| 5 min dedup zombie age | `RequestDeduplicator.java:37` | Not configurable | `meta.dedup.zombie_age` |
| 2 min GroupSlice timeout | `GroupSlice.java:120` | Replaced by idle timeout | `meta.timeouts.idle_timeout` |
| 24h Docker cache expiry | `DockerProxyCooldownInspector.java:45` | Not tunable | `repo.settings.cache_expiry` |
| 24h NPM package index TTL | `InMemoryPackageIndex.java:71` | Not tunable | `repo.settings.index_ttl` |
| 60s VertxSliceServer idle | `VertxSliceServer.java` | Hardcoded | `meta.timeouts.inbound_idle_timeout` |
| 2 min VertxSliceServer request | `VertxSliceServer.java:751` | Hardcoded | `meta.timeouts.request_timeout` |
| 60s MainSlice timeout | `MainSlice.java:107` | Redundant, remove | Removed (redundant) |
| 15s HTTP client connect | `HttpClientSettings.java:210` | Hardcoded | `meta.timeouts.connection_timeout` |
| 30s HTTP client idle | `HttpClientSettings.java:216` | Hardcoded | `meta.timeouts.idle_timeout` |
| 30s HTTP client acquire | `HttpClientSettings.java:222` | Hardcoded | `meta.http_client.connection_acquire_timeout` |
| 5 failures / 1 min CircuitBreaker | `CircuitBreakerSlice.java:60,65` | Hardcoded, count-based | `meta.auto_block.*` (Fibonacci) |
| 5 failures / 30s MemberSlice breaker | `MemberSlice.java` | Hardcoded | `meta.auto_block.*` (Fibonacci) |

**Buffer/memory (not configurable):**

| Value | Location |
|-------|----------|
| 2 GB Jetty direct memory | `JettyClientSlices.java:270` |
| 1 GB Jetty heap memory | `JettyClientSlices.java:271` |
| 1024 Jetty bucket size | `JettyClientSlices.java:269` |
| 256 MB max cacheable | `BaseCachedProxySlice.java:74` |
| 10 MB Redis value limit | `RedisStorage.java:46` |
| 1 MB body buffer threshold | `VertxSliceServer.java:294` |

**Pool/queue (not configurable):**

| Value | Location |
|-------|----------|
| 20 drain permits | `GroupSlice.java:63` |
| 250 merge semaphore | `MetadataMerger.java:86` |
| Unbounded event queue | `EventQueue.java` |
| Unbounded RxFile pool | `RxFile.java:72` |

### 12.3 Environment Variable Support

Only two env vars found:
- `ARTIPIE_FILESYSTEM_IO_THREADS` — FileSystem I/O thread count
- `ARTIPIE_INIT` — initialization path

All other configuration requires YAML or code changes.

---

## 13. Metrics & Observability

### 13.1 Scraping Architecture

`AsyncMetricsVerticle.java`: Worker verticle (off event loop), 2 dedicated threads, 10s cache TTL (hardcoded at `VertxMain.java:374`), 30s scrape timeout, `ReentrantLock` prevents concurrent scrapes, stale cache on failure.

### 13.2 Cardinality Risk

40+ metric names with tags: `repo_name`, `repo_type`, `upstream`, `member_name`, `cache_type`, `cache_tier`.

200 repos x 5 methods x 10 status codes = 10,000 time series for `artipie.http.requests`.
With histogram buckets (~20 per Timer): **~800,000 total time series**.

### 13.3 Missing Operational Metrics

No metrics for: event queue depth, dedup hit rate, cache write latency, temp file count, connection pool utilization, thread pool saturation, DB query latency.

---

## 14. Throughput Model: 1000 req/s Analysis

### 14.1 Best Case: Cached Reads

| Stage | Bottleneck | Throughput |
|-------|-----------|------------|
| Vert.x accept | 16 event loops | >50,000 req/s |
| Slice cache | Guava (lock-free) | >100,000 req/s |
| Negative cache | Caffeine (lock-free) | >1,000,000 req/s |
| Cache hit — NIO | 30 I/O threads, 1 MB chunks | ~10,000 req/s (NVMe) |
| Response | NIC limited (10 Gbps) | — |

**1000 cached reads/s: achievable** on single instance with NVMe.

### 14.2 Worst Case: Concurrent Cache Misses

| Stage | Bottleneck | Throughput |
|-------|-----------|------------|
| Connection pool | 64 active + 256 queued | 320 concurrent |
| Remaining 680 | Acquire timeout (30s) | **Fail** |
| Heap (current) | 64 x 50 MB buffered | 3.2 GB |
| Heap (after fix) | 64 x ~0 (temp file) | ~0 |

**1000 cache-miss req/s to single upstream: NOT achievable** with defaults.

### 14.3 Thundering Herd

1000 requests for one uncached artifact:
- Deduplicator: 1 fetches, 999 wait
- After cache: 999 reads via 30 I/O threads
- Effective upstream load: **1 request**

**Handled correctly.**

### 14.4 Recommended Tuning

```yaml
meta:
  http_client:
    max_connections_per_destination: 128
    max_requests_queued_per_destination: 512
  timeouts:
    connection_timeout: 5s              # TCP handshake for all outbound
    idle_timeout: 30s                   # No-data-flow kill for all outbound
    inbound_idle_timeout: 60s           # Client -> Artipie idle timeout
    request_timeout: 120s              # Outer safety net
  auto_block:
    enabled: true
    failure_threshold: 3
    initial_block_duration: 40s
    max_block_duration: 60m
```

```bash
JAVA_OPTS="-Xmx8g -Xms8g \
  -XX:+UseZGC \
  -XX:MaxDirectMemorySize=4g \
  -XX:+AlwaysPreTouch \
  -Dvertx.disableFileCPResolving=true \
  -Dio.netty.allocator.maxOrder=11"
```

---

## 15. Scalability to 2TB & Multi-Instance

### 15.1 Storage Backend Assessment

| Backend | 2TB? | Limitation |
|---------|------|-----------|
| **S3** | Excellent | List truncated at 1000 (bug) |
| **FileSystem** | Feasible | Recursive listing catastrophic |
| **Redis** | No | 10 MB values, all in memory |
| **etcd** | No | 10 MB, no pagination |

### 15.2 Multi-Instance Barriers

| Component | Problem | Solution |
|-----------|---------|----------|
| ~~Lucene index~~ | ~~Per-instance, separate~~ | **Eliminated** — replaced with PostgreSQL queries (see Section 6) |
| Quartz scheduler | Duplicate jobs | JDBC clustering |
| DiskCacheStorage | No sharing | Shared NFS or Valkey |
| Config watch | No propagation | etcd Watch or Redis pub/sub |
| Slice cache | Stale after config change | Redis pub/sub invalidation |

**Note:** The largest multi-instance barrier (Lucene) is eliminated by the database-centric architecture described in Section 6. PostgreSQL is already shared across instances, so artifact resolution (`locate`) and search are automatically consistent without any warmup or coordination.

### 15.3 Directory Listing at Scale (10M artifacts)

| Method | FileStorage | S3 |
|--------|-----------|-----|
| `list(Key.ROOT)` | **~20 min** | **Truncated at 1000** |
| `list(Key.ROOT, "/")` | **~100ms** | **~100ms** |

Every caller must use hierarchical listing. Audit all `list(Key)` call sites.

### 15.4 Horizontal Scaling Path

**Phase 1 — Stateless behind LB:**
- S3 for storage (shared)
- PostgreSQL for metadata + search + artifact resolution (shared) — see Section 6
- PgBouncer for connection multiplexing (transaction pooling mode)
- Valkey for L2 negative cache (shared)
- No per-instance state — zero warmup, instant horizontal scaling
- 3+ instances behind ALB/NLB

**Phase 2 — Coordinated cluster:**
- Redis pub/sub for cache invalidation
- Quartz JDBC clustering
- PostgreSQL read replicas for search query offloading
- etcd/K8s API for service discovery

**Phase 3 — 10B scale:**
- PostgreSQL table partitioning by `repo_name` (see Section 6.4.3)
- Auto-partition creation on first insert to new repo
- Per-partition indexes, vacuum, autovacuum
- Hot/cold tablespace separation for archive repos

---

## 16. Prioritized Action Plan with Implementations

### P0 — Critical (Blocks Production at Scale)

#### P0.1 — Fix `.join()` in RetrySlice

**File:** `RetrySlice.java:130`
**Problem:** `.join()` in `exceptionally()` blocks `ForkJoinPool.commonPool()`. Under 1000 concurrent requests with failures, all common pool threads blocked — cascades to every async operation.

**Implementation:**
```java
// BEFORE (line 130):
.exceptionally(err -> this.delayedAttempt(...).join())

// AFTER:
.thenCompose(Function.identity())
// ... restructure to use .thenCompose() for retry chaining
// instead of blocking .join()
```

Also add jitter: `delay * (1.0 + ThreadLocalRandom.current().nextDouble(0.5))`

---

#### P0.2 — Fix S3 List Pagination

**File:** `S3Storage.java:285`
**Problem:** `ListObjectsRequest` v1 returns max 1000 objects with no pagination. Repositories with >1000 artifacts silently return truncated results.

**Implementation:**
```java
// Replace ListObjectsRequest v1 with v2 paginator:
ListObjectsV2Request request = ListObjectsV2Request.builder()
    .bucket(this.bucket)
    .prefix(prefix)
    .delimiter(delimiter)
    .build();

return this.client.listObjectsV2Paginator(request)
    .contents()
    .stream()
    .map(obj -> new Key.From(obj.key()))
    .collect(Collectors.toList());
```

---

#### P0.3 — Streaming Proxy Cache (Eliminate OOM)

**Files:** `BaseCachedProxySlice.java:592`, `DigestComputer.java`, adapters
**Problem:** Full heap buffering of every cacheable artifact. Chunked responses bypass size check — OOM vector. Artifacts >256MB never cached — defeats proxy purpose.

**Implementation:** See Section 3.4 for complete three-part implementation:
1. Add streaming `createDigests`/`updateDigests`/`finalizeDigests` to `DigestComputer`
2. Add `digestAlgorithms()` hook in `BaseCachedProxySlice`
3. Rewrite `cacheResponse()` to stream through NIO temp file with incremental digest computation

**Result:** Zero heap pressure. All artifacts cached regardless of size. Client streams immediately.

---

#### P0.4 — Fix `.join()` in MergeShardsSlice (Event Loop Deadlock)

**File:** `MergeShardsSlice.java:146`
**Problem:** `.join()` inside `thenApply()` blocks event loop during PyPI shard merge. Under merge load: total service deadlock.

**Implementation:**
```java
// BEFORE (line 146):
.thenApply(v -> {
    lines.forEach(fut -> sb.append(fut.join()));  // BLOCKING
    return result;
})

// AFTER:
// CompletableFuture.allOf() already ensures completion.
// Use thenApply with get() on already-completed futures,
// or restructure to compose results without blocking.
.thenApply(v -> {
    lines.stream()
        .map(CompletableFuture::resultNow)  // Java 19+ or getNow("")
        .forEach(sb::append);
    return result;
})
```

---

#### P0.5 — Bound Event Queues

**Files:** `EventQueue.java`, `MetadataEventQueues.java:115`
**Problem:** Unbounded `ConcurrentLinkedQueue` grows without limit under burst load.

**Implementation:**
```java
// Replace ConcurrentLinkedQueue with bounded alternative:
private final BlockingQueue<ProxyArtifactEvent> queue =
    new LinkedBlockingQueue<>(100_000);  // configurable capacity

// On offer failure, log and drop (or apply backpressure):
if (!queue.offer(event)) {
    EcsLogger.warn(...)
        .message("Event queue full, dropping event")
        .field("queue.size", queue.size())
        .log();
    droppedEvents.increment();  // metric
}
```

---

### P1 — High Priority (Significant Performance/Reliability Gains)

#### P1.5 Detail — Unified Idle-Timeout & Auto-Block Model

**Motivation:** Artipie currently uses absolute timeouts (`CompletableFuture.orTimeout()`, hardcoded `DEFAULT_TIMEOUT_SECONDS`) which would kill large file downloads mid-transfer. Both JFrog Artifactory and Sonatype Nexus use **idle timeouts** instead — the connection only dies when no data flows for N seconds. Additionally, Nexus implements **auto-blocking** of unresponsive remotes with Fibonacci backoff to prevent repeated timeout penalties. Artipie should adopt both patterns as a unified model applied consistently across all outbound connections.

**Principle: No hardcoded timeout values anywhere.** Every timeout, threshold, and backoff parameter is configurable per repository via YAML, with sensible defaults.

**The model has three layers:**

**Layer 1 — Connection Timeout (TCP handshake)**
- How long to wait to establish a TCP connection to a remote
- Default: 5s (configurable via `connection_timeout`)
- Applies to: proxy remotes, group members, any outbound HTTP

**Layer 2 — Idle Timeout (data flow watchdog)**
- Kill the connection only when **no data has flowed** for N seconds
- Default: 30s (configurable via `idle_timeout`)
- A 2GB Docker layer download that takes 10 minutes but keeps streaming: **stays alive**
- A member that accepts the connection but stalls: **killed after 30s of silence**
- Applies at the HTTP client socket level (Jetty `HttpClient` / Vert.x `HttpClient`)
- **No absolute timeout on body transfer** — as long as bytes flow, the connection lives

**Layer 3 — Auto-Block (failure-driven backoff)**
- When a remote/member **fails** (timeout, 5xx, connection refused), mark it as **auto-blocked**
- During auto-block period, skip the remote entirely:
  - Group members: excluded from fan-out, other members still queried
  - Proxy remotes: serve from cache if available, else 502 with explanation
- Backoff schedule: Fibonacci sequence — 40s, 40s, 80s, 120s, 200s, 320s, ... capped at `max_block_duration` (default: 60min)
- After backoff expires: allow **one probe request** (half-open state)
  - Probe succeeds: unblock, reset backoff counter
  - Probe fails: re-block with next Fibonacci interval
- **Failure threshold** before auto-block: configurable (default: 3 consecutive failures)
- **Status exposed via health/metrics:** `artipie.remote.status{remote="...",repo="..."}` with values `online`, `blocked`, `probing`

**YAML configuration (per repository):**

```yaml
repo:
  type: maven-proxy
  remotes:
    - url: https://repo1.maven.org/maven2
      timeouts:
        connection_timeout: 5s          # TCP handshake (default: 5s)
        idle_timeout: 30s               # No-data-flow kill (default: 30s)
      auto_block:
        enabled: true                   # default: true
        failure_threshold: 3            # consecutive failures before block (default: 3)
        initial_block_duration: 40s     # first block duration (default: 40s)
        max_block_duration: 60m         # cap on Fibonacci backoff (default: 60m)
    - url: https://jcenter.bintray.com
      timeouts:
        connection_timeout: 3s          # tighter for known-flaky remote
        idle_timeout: 15s
      auto_block:
        failure_threshold: 2            # more aggressive for unreliable remote
        max_block_duration: 30m
  settings:
    # Group-level overrides (for group/virtual repos)
    group:
      member_connection_timeout: 5s     # default: inherits from remote config
      member_idle_timeout: 30s          # default: inherits from remote config
```

**Global defaults (artipie.yaml):**

```yaml
meta:
  timeouts:
    connection_timeout: 5s              # global default for all outbound connections
    idle_timeout: 30s                   # global default for all outbound connections
    inbound_idle_timeout: 60s           # client -> Artipie idle timeout
    request_timeout: 120s               # outer safety net (existing 2min)
  auto_block:
    enabled: true
    failure_threshold: 3
    initial_block_duration: 40s
    max_block_duration: 60m
```

**Resolution order:** Per-remote setting > Per-repo setting > Global setting > Built-in default.

**Implementation files:**

| File | Change |
|------|--------|
| `HttpClientSettings.java` | Add `idleTimeout` (rename from current meaning), remove absolute timeout. Wire to Jetty `HttpClient.setIdleTimeout()`. |
| `GroupSlice.java` | Remove `DEFAULT_TIMEOUT_SECONDS = 120`. Use idle timeout from member's HTTP client config. Remove `CompletableFuture.orTimeout()` — the idle timeout on the underlying socket handles it. |
| `AutoBlockRegistry.java` | **New class.** Tracks per-remote block state: `ConcurrentHashMap<RemoteId, BlockState>`. `BlockState` holds: failure count, current Fibonacci interval, blocked-until timestamp, status enum. Thread-safe, metrics-emitting. |
| `CircuitBreakerSlice.java` | **Rewrite** to delegate to `AutoBlockRegistry`. Remove hardcoded 5-failure / 1-min recovery. All parameters from YAML config. |
| `MemberSlice.java` | Remove hardcoded 5-failure / 30s circuit breaker. Delegate to shared `AutoBlockRegistry`. |
| `TimeoutSettings.java` | **New class.** Parses timeout YAML config with resolution-order fallback (per-remote > per-repo > global > default). Immutable value object. |
| `VertxSliceServer.java` | Wire `inbound_idle_timeout` and `request_timeout` from global config instead of hardcoded 60s/2min. |
| `MainSlice.java` | Remove hardcoded 60s inner timeout. Use `request_timeout` from global config. |

**What this replaces:**

| Current (hardcoded) | New (configurable) |
|---|---|
| `GroupSlice.DEFAULT_TIMEOUT_SECONDS = 120` | `meta.timeouts.idle_timeout` (default 30s idle, not absolute) |
| `CircuitBreakerSlice` 5 failures / 1 min | `auto_block.failure_threshold` / Fibonacci backoff |
| `MemberSlice` 5 failures / 30s | Same `AutoBlockRegistry` |
| `VertxSliceServer` idle 60s | `meta.timeouts.inbound_idle_timeout` |
| `VertxSliceServer` request 2 min | `meta.timeouts.request_timeout` |
| `MainSlice` 60s | Removed (redundant with `request_timeout`) |
| `HttpClientSettings` connect 15s / idle 30s / acquire 30s | `meta.timeouts.connection_timeout` / `meta.timeouts.idle_timeout` |

---

| # | Item | File(s) | Implementation |
|---|------|---------|---------------|
| P1.1 | ~~**Batch Lucene commits**~~ | ~~`LuceneArtifactIndex.java`~~ | **Superseded by Section 6** — Lucene removed entirely. PostgreSQL handles all search and resolution. |
| P1.2 | **Skip compression for binary artifacts** | `VertxSliceServer.java:154` | Add content-type filter: disable compression for `application/octet-stream`, `application/java-archive`, `application/gzip`, `application/x-tar`, etc. |
| P1.3 | **Implement VertxFileStorage hierarchical list** | `asto-vertx-file` | Override `list(Key, String)` using `Files.newDirectoryStream()` instead of inheriting recursive fallback. |
| P1.4 | ~~**Dedicated executors for Lucene search**~~ | ~~`LuceneArtifactIndex.java`~~ | **Superseded by Section 6** — Lucene removed entirely. DB queries use HikariCP connection pool. |
| P1.5 | **Unified idle-timeout & auto-block model** | `GroupSlice.java`, `CircuitBreakerSlice.java`, `HttpClientSettings.java`, `MemberSlice.java` | See detailed P1.5 section below. Replace absolute timeouts with idle timeouts + auto-block across all outbound connections. All values configurable per repo via YAML. |
| P1.6 | **Increase StreamThroughCache write buffer** | `StreamThroughCache.java:193` | Change `ByteBuffer.allocate(8192)` to `ByteBuffer.allocate(65536)` — 8x I/O throughput. |
| P1.7 | **Replace circuit breaker with auto-block** | `CircuitBreakerSlice.java`, `MemberSlice.java` | Replace count-based circuit breaker with auto-block model (Fibonacci backoff, half-open probe, all configurable per repo via YAML). Subsumes current `CircuitBreakerSlice` and `MemberSlice` hardcoded breaker. See P1.5 detail. |
| P1.8 | **Move slice cache resolution off event loop** | `RepositorySlices.java:1012` | Wrap `resolve()` in `vertx.executeBlocking()` so Jetty client startup doesn't block event loop. |
| P1.9 | **Add retry jitter** | `RetrySlice.java` | `delay * (1.0 + ThreadLocalRandom.current().nextDouble(0.5))` prevents thundering herd on retry. |
| P1.10 | **Fix MergeShardsSlice race** | `MergeShardsSlice.java:609` | Move `isEmpty()` check inside `synchronized` block, or replace with `ConcurrentHashMap` operations. |
| P1.11 | **Add .exceptionally() to fire-and-forget chains** | `MergeShardsSlice.java:535-602` | Add error handler after every `.thenAccept()` / `.thenRun()` chain to prevent silent data loss. |
| P1.12 | **Bound RxFile/ContentAsStream thread pools** | `RxFile.java:72`, `ContentAsStream.java:43` | Replace `newCachedThreadPool()` with `newFixedThreadPool(max(16, CPU * 4))`. |

### P2 — Medium Priority (Scalability & Operational)

| # | Item | File(s) | Implementation |
|---|------|---------|---------------|
| P2.1 | **Separate worker pools by operation type** | Storage layer | Create `artipie.io.read` (CPU x 4), `artipie.io.write` (CPU x 2), `artipie.io.list` (CPU, capped). Wrap storage methods to dispatch to correct pool. |
| P2.2 | **Graceful shutdown drain** | `VertxSliceServer.java:229-242` | Before `server.rxClose()`: stop accepting new connections, wait up to 30s for in-flight requests to complete (track via `AtomicInteger`), then force-close. |
| P2.3 | **Close resources on shutdown** | `VertxMain.stop()`, `Settings.java` | Wire `Settings.close()` to shut down HikariDataSource, S3AsyncClient, Valkey connections, Vert.x instance. |
| P2.4 | **Comprehensive health checks** | `HealthSlice.java` | Add multi-component health: `{"status":"degraded","components":{"storage":"ok","database":"unhealthy","valkey":"ok","quartz":"ok"}}` |
| P2.5 | **Externalize all remaining hardcoded values** | Multiple files | All values in Section 12.2 that are not covered by P1.5 (timeout/auto-block model) must be configurable via YAML + env var override. Pattern: `ARTIPIE_<SECTION>_<PARAM>` env var overrides YAML. See Section 12.2 `Target Config Key` column for mapping. No hardcoded values remain. |
| P2.6 | **Database dead-letter queue** | `DbConsumer.java:206-226` | On 3 failures: write events to file + emit metric instead of dropping. Add exponential backoff (1s, 2s, 4s, 8s cap). |
| P2.7 | **Reduce DB connection timeout** | `ArtifactDbFactory.java:203` | Change from 30s to 5s. Make configurable via YAML/env. |
| P2.8 | **Valkey connection pool** | `ValkeyConnection.java` | Replace single connection with `GenericObjectPool<StatefulRedisConnection>` or use Lettuce's built-in pooling. |
| P2.9 | **Log all suppressed exceptions** | Multiple files | Add `EcsLogger.warn()` in all `catch(Exception)` blocks currently swallowing silently: health check, cooldown, composer cache. |
| P2.10 | **Metrics cardinality control** | `VertxMain.java` | Make `percentilesHistogram` opt-in. Cap `repo_name` tag to top-N repos. Add queue depth and pool utilization metrics. |
| P2.11 | ~~**Lucene SearchAfter pagination**~~ | ~~`LuceneArtifactIndex.java`~~ | **Superseded by Section 6** — PostgreSQL keyset pagination replaces Lucene offset-based pagination. |
| P2.12 | **Lock TTL cleanup** | `StorageLock.java` | Scheduled job every 60s to remove expired lock proposals from storage. |

### P3 — Lower Priority (Future Scaling)

| # | Item | Impact |
|---|------|--------|
| P3.1 | **Remove asto-etcd** | Reduce maintenance surface |
| P3.2 | **Redis pub/sub for cache invalidation** | Multi-instance consistency |
| P3.3 | **Quartz JDBC clustering** | Prevent duplicate jobs |
| P3.4 | **Replace Lucene with PostgreSQL-backed artifact resolution and search** | **Critical for multi-instance.** Implement `DbArtifactIndex`, add `tsvector` + GIN index, covering indexes, PgBouncer, table partitioning. Remove `LuceneArtifactIndex`, `IndexWarmupService`, `IndexConsumer`. See Section 6 for complete design. |
| P3.5 | **Eliminate double memory copy** in Vert.x paths | Reduce heap churn |
| P3.6 | **Make 1 MB body threshold configurable** | Tuning flexibility |
| P3.7 | **Remove asto-redis** (Redisson), consolidate on Valkey (Lettuce) | Single client |
| P3.8 | **Temp file cleanup on shutdown** | Add `deleteOnExit()` for all temp files, or shutdown hook for temp dir cleanup |
| P3.9 | **Fix Quartz double-shutdown** | Add `stopped` guard in QuartzService.stop() |
| P3.10 | **Deprecation cleanup** | Remove deprecated constructors in npm, rpm, docker adapters |

---

## Appendix A: Configuration Checklist for 1000 req/s

```yaml
# artipie.yaml
meta:
  http_client:
    max_connections_per_destination: 128
    max_requests_queued_per_destination: 512
  timeouts:
    connection_timeout: 5s              # TCP handshake for all outbound (default: 5s)
    idle_timeout: 30s                   # No-data-flow kill for outbound (default: 30s)
    inbound_idle_timeout: 60s           # Client -> Artipie idle (default: 60s)
    request_timeout: 120s              # Outer safety net (default: 120s)
  auto_block:
    enabled: true                       # Enable auto-block for remotes (default: true)
    failure_threshold: 3                # Consecutive failures before block (default: 3)
    initial_block_duration: 40s         # First Fibonacci interval (default: 40s)
    max_block_duration: 60m             # Cap on backoff (default: 60m)
  storage:
    type: s3
    bucket: artipie-artifacts
    region: us-east-1
    multipart:
      threshold: 16777216
      partsize: 16777216
      concurrency: 32
    parallel_download:
      enabled: true
      threshold: 67108864
      chunk: 8388608
      concurrency: 16
  caches:
    valkey:
      enabled: true
      host: valkey.internal
      port: 6379
      timeout: 100
  db:
    host: postgres.internal
    port: 5432
    database: artifacts
    pool_max_size: 50
    pool_min_idle: 10
  metrics:
    port: 8087
    endpoint: /metrics
    type: prometheus
```

```bash
JAVA_OPTS="-Xmx8g -Xms8g \
  -XX:+UseZGC \
  -XX:MaxDirectMemorySize=4g \
  -XX:+AlwaysPreTouch \
  -Dvertx.disableFileCPResolving=true \
  -Dio.netty.allocator.maxOrder=11"
```

## Appendix B: Key Source References

| Component | File | Lines |
|-----------|------|-------|
| Thread config | `VertxMain.java` | 678-691 |
| Request dispatch | `VertxSliceServer.java` | 254-406 |
| Body threshold (1 MB) | `VertxSliceServer.java` | 294 |
| Double copy (response) | `VertxSliceServer.java` | 1277-1281 |
| Timeout layers | `VertxSliceServer/MainSlice/TimeoutSlice` | 751-808 / 107-108 / 51 |
| Slice cache | `RepositorySlices.java` | 158-188 |
| HTTP client pool | `HttpClientSettings.java` | 210-234 |
| Jetty buffer pool | `JettyClientSlices.java` | 269-279 |
| 256 MB cache limit | `BaseCachedProxySlice.java` | 73-74 |
| OOM vector (chunked) | `BaseCachedProxySlice.java` | 511, 592 |
| cacheResponse (heap buffer) | `BaseCachedProxySlice.java` | 585-645 |
| signalToResponse (double-read) | `BaseCachedProxySlice.java` | 544-580 |
| computeDigests(byte[]) | `BaseCachedProxySlice.java` | 265 |
| DigestComputer | `DigestComputer.java` | 75-100 |
| Deduplicator | `RequestDeduplicator.java` | 42-131 |
| Group race | `GroupSlice.java` | 49-155 |
| S3 list (no pagination) | `S3Storage.java` | 285 |
| S3 thresholds | `S3Storage.java` | 69-173 |
| FileStorage recursive list | `FileStorage.java` | 114 |
| FileStorage hierarchical list | `FileStorage.java` | 165 |
| Lucene (to be removed — see §6) | `LuceneArtifactIndex.java` | entire file |
| Lucene warmup (to be removed — see §6) | `IndexWarmupService.java` | entire file |
| Lucene consumer (to be removed — see §6) | `IndexConsumer.java` | entire file |
| RetrySlice .join() | `RetrySlice.java` | 130 |
| MergeShardsSlice .join() | `MergeShardsSlice.java` | 146 |
| MergeShardsSlice race | `MergeShardsSlice.java` | 609-612 |
| MergeShardsSlice no error handler | `MergeShardsSlice.java` | 535-602 |
| Circuit breaker | `CircuitBreakerSlice.java` | 60, 65 |
| HikariCP config | `ArtifactDbFactory.java` | 197-216 |
| DB batch processing | `DbConsumer.java` | 70, 137-144, 206-226 |
| Unbounded event queue | `EventQueue.java` | ConcurrentLinkedQueue |
| Negative cache | `NegativeCache.java` | 54-59 |
| Health check | `HealthSlice.java` | 74-76 |
| StreamThroughCache 8 KB | `StreamThroughCache.java` | 193 |
| FromStorageCache OOM | `FromStorageCache.java` | 105 |
| etcd 10 MB limit | `EtcdStorage.java` | 48 |
| Metrics cache TTL | `VertxMain.java` | 374 |
| Shutdown (no drain) | `VertxSliceServer.java` | 229-242 |
| Settings close (no-op) | `Settings.java` | 34-37 |
| Unbounded thread pool | `RxFile.java` | 72 |
| Unbounded thread pool | `ContentAsStream.java` | 43 |
| JWT static lock | `JwtPasswordAuthFactory.java` | 107, 122 |
| Completables .join() | `Completables.java` | 110 |
