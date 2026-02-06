# P1 Implementation Design

**Date:** 2026-02-17
**Scope:** All 12 P1 tasks from the Enterprise Technical Assessment
**Approach:** Dependency-first wave ordering, TDD, `mvn clean install -U` green after each wave

---

## Wave 1 — Foundation (Independent, No Cross-Dependencies)

### P1.12 — Bound RxFile/ContentAsStream Thread Pools
- **Files:** `asto-core/.../RxFile.java:72`, `asto-core/.../ContentAsStream.java:43`
- **Change:** Replace `Executors.newCachedThreadPool()` with `Executors.newFixedThreadPool(Math.max(16, Runtime.getRuntime().availableProcessors() * 4))`
- **Tests:** Extend `RxFileTest`, `ContentAsStreamTest` — verify pool is bounded, named threads exist

### P1.6 — Increase StreamThroughCache Write Buffer
- **File:** `asto-core/.../StreamThroughCache.java:193`
- **Change:** `ByteBuffer.allocate(8192)` → `ByteBuffer.allocate(65536)`
- **Tests:** Verify cache save still works with larger buffer, content integrity preserved

### P1.2 — Skip Compression for Binary Artifacts
- **File:** `vertx-server/.../VertxSliceServer.java`
- **Change:** In response handler, check Content-Type. For binary types (`application/octet-stream`, `application/java-archive`, `application/gzip`, `application/x-tar`, `application/x-rpm`, `application/x-debian-package`, `application/x-compressed`, `application/zip`), remove `Content-Encoding` / set header to skip compression.
- **Tests:** `VertxSliceServerTest` — verify compressed types not re-compressed, text types still compressed

### P1.9 — Add Retry Jitter
- **File:** `artipie-core/.../RetrySlice.java:130`
- **Change:** In `delayedAttempt()`, add jitter: `(long)(delayMs * (1.0 + ThreadLocalRandom.current().nextDouble(0.5)))`
- **Tests:** `RetrySliceTest` — verify retry delays are within expected jitter range

### P1.10 — Fix MergeShardsSlice Race
- **File:** `artipie-main/.../MergeShardsSlice.java:608-612`
- **Change:** Move `isEmpty()` check inside `synchronized(chartVersions)` block
- **Tests:** New `MergeShardsSliceRaceTest` — concurrent helm chart processing doesn't lose data

### P1.11 — Add Error Handlers to Fire-and-Forget Chains
- **File:** `artipie-main/.../MergeShardsSlice.java:535-602`
- **Change:** Add `.exceptionally()` handlers to all `.thenAccept()`/`.thenRun()` chains
- **Tests:** Verify exceptions are logged, not swallowed

---

## Wave 2 — Lucene Improvements

### P1.1 — Batch Lucene Commits
- **File:** `artipie-main/.../LuceneArtifactIndex.java`
- **Change:** Replace per-doc `writer.commit()` with periodic commit. Use `ScheduledExecutorService` (1s interval). Buffer write operations. `indexBatch()` already single-commit (no change needed). `index()` and `remove()` queue operations instead of immediate commit.
- **Tests:** `LuceneArtifactIndexTest` — verify batch commit, verify search sees indexed docs after commit interval

### P1.4 — Dedicated Lucene Search Executor
- **File:** `artipie-main/.../LuceneArtifactIndex.java:175,206`
- **Change:** Replace `CompletableFuture.supplyAsync(() -> ...)` (implicit commonPool) with explicit `searchExecutor` — `Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors()))` named `lucene-search`
- **Tests:** `LuceneArtifactIndexTest` — verify searches run on named threads

---

## Wave 3 — Timeout & Auto-Block Model

### P1.5 — Unified Idle-Timeout & Auto-Block Model
- **New files:**
  - `artipie-core/.../timeout/TimeoutSettings.java` — Immutable config value object
  - `artipie-core/.../timeout/AutoBlockRegistry.java` — ConcurrentHashMap-based block state tracker with Fibonacci backoff
  - `artipie-core/.../timeout/BlockState.java` — Immutable record: failureCount, fibonacciIndex, blockedUntil, status
- **Modified files:**
  - `http-client/.../HttpClientSettings.java` — Wire `TimeoutSettings` values
  - `artipie-main/.../GroupSlice.java` — Remove `DEFAULT_TIMEOUT_SECONDS=120`, remove `.orTimeout()`, use idle timeout from HTTP client, integrate `AutoBlockRegistry`
- **Tests:** `TimeoutSettingsTest`, `AutoBlockRegistryTest`, `GroupSliceTest` updates

### P1.7 — Replace Circuit Breaker with Auto-Block
- **Modified files:**
  - `artipie-core/.../CircuitBreakerSlice.java` — Delegate to `AutoBlockRegistry`
  - `artipie-main/.../MemberSlice.java` — Remove hardcoded 5/30s, delegate to `AutoBlockRegistry`
- **Tests:** `CircuitBreakerSliceTest` (new), `MemberSliceTest` (new)

### P1.8 — Move Slice Cache Resolution Off Event Loop
- **File:** `artipie-main/.../RepositorySlices.java:1012`
- **Change:** Wrap `resolve()` body in `vertx.executeBlocking()` or `CompletableFuture.supplyAsync(..., blockingExecutor)`
- **Tests:** Verify resolution doesn't block event loop thread

### P1.3 — VertxFileStorage Hierarchical List
- **File:** `asto-vertx-file/.../VertxFileStorage.java`
- **Change:** Override `list(Key prefix, String delimiter)` using `Files.newDirectoryStream()` instead of recursive fallback
- **Tests:** `VertxFileStorageTest` — verify hierarchical list returns immediate children only

---

## Acceptance Criteria

- `mvn clean install -U` passes with zero test failures
- No skipped tests (except Windows-specific platform tests)
- No disabled tests
- No integration tests required (only unit tests)
- All new code has test coverage
- All hardcoded timeout values replaced with configurable YAML settings
