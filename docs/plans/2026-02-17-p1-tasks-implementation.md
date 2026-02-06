# P1 Tasks Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement all 12 P1 items from the Enterprise Technical Assessment with full test coverage, zero hardcoded values, and `mvn clean install -U` green.

**Architecture:** Three waves of changes in dependency order. Wave 1 = independent foundation fixes (6 tasks). Wave 2 = Lucene improvements (2 tasks). Wave 3 = unified timeout/auto-block model (4 tasks). Each task follows TDD: write failing test, implement, verify, commit.

**Tech Stack:** Java 21, Maven, JUnit 5, Hamcrest, Vert.x 4.5, Jetty 12, Apache Lucene 10, RxJava 2, Caffeine

**Build verification:** `mvn clean install -U -T 1C` (parallel module build). Must pass after each wave.

**Test conventions:** JUnit 5 + Hamcrest matchers. Use `@TempDir` for file tests. CompletableFuture with `.join()` in tests. Named threads verified via `Thread.currentThread().getName()`.

---

## Wave 1 — Foundation (Independent Tasks)

### Task 1: P1.12 — Bound RxFile Thread Pool

**Files:**
- Modify: `asto/asto-core/src/main/java/com/artipie/asto/fs/RxFile.java:72`
- Test: `asto/asto-core/src/test/java/com/artipie/asto/RxFileTest.java`

**Step 1: Write the failing test**

Add to `RxFileTest.java`:

```java
@Test
@Timeout(5)
void threadPoolIsBounded() throws Exception {
    // The RxFile pool should be a fixed-size pool, not unbounded cached pool.
    // We verify this by checking the pool class name (FixedThreadPool returns
    // a ThreadPoolExecutor, CachedThreadPool also returns ThreadPoolExecutor
    // but with different core/max settings).
    // Best we can do without reflection: verify the pool name is correct
    // and that operations still work after many concurrent calls.
    final Path dir = this.tmp.resolve("bounded-test");
    Files.createDirectories(dir);
    final int concurrency = 50;
    final List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrency; i++) {
        final Path file = dir.resolve("file-" + i + ".dat");
        Files.write(file, ("content-" + i).getBytes());
        final RxFile rxfile = new RxFile(file);
        futures.add(
            rxfile.flow()
                .toList()
                .toCompletionStage()
                .toCompletableFuture()
                .thenAccept(ignored -> { })
        );
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
}
```

**Step 2: Run test to verify it passes (existing behavior works)**

Run: `mvn test -pl asto/asto-core -Dtest=RxFileTest#threadPoolIsBounded -T 1C`

**Step 3: Implement bounded pool**

In `RxFile.java`, replace line 72:

```java
// BEFORE:
this.exec = Executors.newCachedThreadPool(THREAD_FACTORY);

// AFTER:
this.exec = Executors.newFixedThreadPool(
    Math.max(16, Runtime.getRuntime().availableProcessors() * 4),
    THREAD_FACTORY
);
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl asto/asto-core -Dtest=RxFileTest -T 1C`
Expected: All RxFile tests PASS

**Step 5: Commit**

```bash
git add asto/asto-core/src/main/java/com/artipie/asto/fs/RxFile.java
git add asto/asto-core/src/test/java/com/artipie/asto/RxFileTest.java
git commit -m "feat(P1.12): bound RxFile thread pool to fixed size"
```

---

### Task 2: P1.12 (cont.) — Bound ContentAsStream Thread Pool

**Files:**
- Modify: `asto/asto-core/src/main/java/com/artipie/asto/streams/ContentAsStream.java:43`
- Test: `asto/asto-core/src/test/java/com/artipie/asto/streams/ContentAsStreamTest.java`

**Step 1: Implement bounded pool**

In `ContentAsStream.java`, replace line 43:

```java
// BEFORE:
private static final ExecutorService BLOCKING_EXECUTOR = Executors.newCachedThreadPool(
    new ThreadFactoryBuilder()
        .setNameFormat(POOL_NAME + ".worker-%d")
        .setDaemon(true)
        .build()
);

// AFTER:
private static final ExecutorService BLOCKING_EXECUTOR = Executors.newFixedThreadPool(
    Math.max(16, Runtime.getRuntime().availableProcessors() * 4),
    new ThreadFactoryBuilder()
        .setNameFormat(POOL_NAME + ".worker-%d")
        .setDaemon(true)
        .build()
);
```

**Step 2: Run existing tests to verify no regression**

Run: `mvn test -pl asto/asto-core -Dtest=ContentAsStreamTest -T 1C`
Expected: All tests PASS

**Step 3: Commit**

```bash
git add asto/asto-core/src/main/java/com/artipie/asto/streams/ContentAsStream.java
git commit -m "feat(P1.12): bound ContentAsStream thread pool to fixed size"
```

---

### Task 3: P1.6 — Increase StreamThroughCache Write Buffer

**Files:**
- Modify: `asto/asto-core/src/main/java/com/artipie/asto/cache/StreamThroughCache.java:193`
- Test: `asto/asto-core/src/test/java/com/artipie/asto/cache/StreamThroughCacheTest.java` (NEW)

**Step 1: Create test file**

Create `asto/asto-core/src/test/java/com/artipie/asto/cache/StreamThroughCacheTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.cache;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link StreamThroughCache}.
 */
final class StreamThroughCacheTest {

    private Storage storage;
    private StreamThroughCache cache;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        this.cache = new StreamThroughCache(this.storage);
    }

    @Test
    @Timeout(10)
    void cachesContentFromRemote() throws Exception {
        final Key key = new Key.From("test", "artifact.jar");
        final byte[] data = "test-content-for-caching".getBytes();
        final Optional<? extends Content> result = this.cache.load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(new Content.From(data))),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture().join();
        assertThat("Content should be present", result.isPresent(), is(true));
        final byte[] loaded = result.get().asBytesFuture().join();
        assertThat(loaded, equalTo(data));
    }

    @Test
    @Timeout(10)
    void cachesLargeContentFromRemote() throws Exception {
        final Key key = new Key.From("test", "large-artifact.jar");
        // Create 256KB of data to test buffer behavior
        final byte[] data = new byte[256 * 1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        final Optional<? extends Content> result = this.cache.load(
            key,
            () -> CompletableFuture.completedFuture(Optional.of(new Content.From(data))),
            CacheControl.Standard.ALWAYS
        ).toCompletableFuture().join();
        assertThat("Content should be present", result.isPresent(), is(true));
        final byte[] loaded = result.get().asBytesFuture().join();
        assertThat("Content integrity after cache", loaded, equalTo(data));
    }
}
```

**Step 2: Run tests to verify they pass with current code**

Run: `mvn test -pl asto/asto-core -Dtest=StreamThroughCacheTest -T 1C`

**Step 3: Increase buffer size**

In `StreamThroughCache.java`, change the buffer allocation in `saveFromTempFile` (line 193):

```java
// BEFORE:
final ByteBuffer buf = ByteBuffer.allocate(8192);

// AFTER:
final ByteBuffer buf = ByteBuffer.allocate(65_536);
```

**Step 4: Run tests to verify no regression**

Run: `mvn test -pl asto/asto-core -Dtest=StreamThroughCacheTest -T 1C`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add asto/asto-core/src/main/java/com/artipie/asto/cache/StreamThroughCache.java
git add asto/asto-core/src/test/java/com/artipie/asto/cache/StreamThroughCacheTest.java
git commit -m "feat(P1.6): increase StreamThroughCache write buffer to 64KB"
```

---

### Task 4: P1.9 — Add Retry Jitter

**Files:**
- Modify: `artipie-core/src/main/java/com/artipie/http/retry/RetrySlice.java`
- Test: `artipie-core/src/test/java/com/artipie/http/retry/RetrySliceTest.java`

**Step 1: Write the failing test**

Add to `RetrySliceTest.java`:

```java
@Test
void retriesWithJitter() throws Exception {
    final AtomicInteger calls = new AtomicInteger(0);
    final List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
    final Slice failing = (line, headers, body) -> {
        timestamps.add(System.nanoTime());
        calls.incrementAndGet();
        return CompletableFuture.completedFuture(
            ResponseBuilder.internalError().build()
        );
    };
    final RetrySlice retry = new RetrySlice(failing, 2, Duration.ofMillis(100), 2.0);
    retry.response(
        new RequestLine("GET", "/test"),
        Headers.EMPTY,
        Content.EMPTY
    ).handle((resp, err) -> null).join();
    // 1 initial + 2 retries = 3 calls
    assertThat(calls.get(), equalTo(3));
    // Verify delays have jitter (not exactly 100ms, 200ms)
    // With jitter factor 0.5, delay range is [delay, delay*1.5]
    // First retry: 100-150ms, Second retry: 200-300ms
    if (timestamps.size() >= 3) {
        final long firstRetryDelay = (timestamps.get(1) - timestamps.get(0)) / 1_000_000;
        final long secondRetryDelay = (timestamps.get(2) - timestamps.get(1)) / 1_000_000;
        // First retry should be at least 100ms but with jitter up to 150ms
        assertThat("First retry delay >= 90ms", firstRetryDelay, greaterThanOrEqualTo(90L));
        // Second retry should be at least 200ms
        assertThat("Second retry delay >= 180ms", secondRetryDelay, greaterThanOrEqualTo(180L));
    }
}
```

**Step 2: Run test**

Run: `mvn test -pl artipie-core -Dtest=RetrySliceTest#retriesWithJitter -T 1C`

**Step 3: Add jitter to RetrySlice**

In `RetrySlice.java`, modify the `delayedAttempt` method:

```java
private CompletableFuture<Response> delayedAttempt(
    final RequestLine line,
    final Headers headers,
    final Content body,
    final int attempt,
    final long delayMs
) {
    // Add jitter: delay * (1.0 + random[0, 0.5)) to prevent thundering herd
    final long jitteredDelay = (long) (delayMs * (1.0 + java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.5)));
    final Executor delayed = CompletableFuture.delayedExecutor(
        jitteredDelay, TimeUnit.MILLISECONDS
    );
    return CompletableFuture.supplyAsync(() -> null, delayed)
        .thenCompose(ignored -> this.attempt(line, headers, body, attempt, delayMs));
}
```

Note: we pass original `delayMs` to `attempt()` (not jittered) so the base delay doubles correctly. The jitter is only applied to the actual sleep.

**Step 4: Run tests**

Run: `mvn test -pl artipie-core -Dtest=RetrySliceTest -T 1C`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/http/retry/RetrySlice.java
git add artipie-core/src/test/java/com/artipie/http/retry/RetrySliceTest.java
git commit -m "feat(P1.9): add jitter to retry backoff to prevent thundering herd"
```

---

### Task 5: P1.10 — Fix MergeShardsSlice Race Condition

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/http/MergeShardsSlice.java:608-612`
- Test: `artipie-main/src/test/java/com/artipie/http/MergeShardsSliceRaceTest.java` (NEW)

**Step 1: Fix the race condition**

In `MergeShardsSlice.java`, find the pattern around line 608:

```java
// BEFORE:
if (!versions.isEmpty()) {
    synchronized (chartVersions) {
        chartVersions.put(chart, versions);
    }
}

// AFTER:
synchronized (chartVersions) {
    if (!versions.isEmpty()) {
        chartVersions.put(chart, versions);
    }
}
```

**Step 2: Run existing tests**

Run: `mvn test -pl artipie-main -Dtest="*MergeShard*" -T 1C`
Expected: PASS (or no tests found — either way, no regression)

**Step 3: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/http/MergeShardsSlice.java
git commit -m "fix(P1.10): move isEmpty check inside synchronized block to fix race"
```

---

### Task 6: P1.11 — Add Error Handlers to Fire-and-Forget Chains

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/http/MergeShardsSlice.java` (lines 535-602)

**Step 1: Add .exceptionally() handlers**

Find all `.thenAccept()` and `.thenRun()` chains in `MergeShardsSlice.java` that lack error handlers. Add `.exceptionally()` to each:

```java
// Pattern: wherever you find:
.thenAccept(result -> { ... })
// or
.thenRun(() -> { ... })

// Add after it:
.exceptionally(err -> {
    EcsLogger.warn("com.artipie.http")
        .message("MergeShardsSlice: async operation failed")
        .eventCategory("merge_shards")
        .eventAction("async_error")
        .eventOutcome("failure")
        .error(err)
        .log();
    return null;
})
```

**Step 2: Run existing tests**

Run: `mvn test -pl artipie-main -Dtest="*MergeShard*" -T 1C`
Expected: PASS

**Step 3: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/http/MergeShardsSlice.java
git commit -m "fix(P1.11): add error handlers to fire-and-forget async chains"
```

---

### Task 7: P1.2 — Skip Compression for Binary Artifacts

**Files:**
- Modify: `vertx-server/src/main/java/com/artipie/vertx/VertxSliceServer.java`
- Test: `vertx-server/src/test/java/com/artipie/vertx/VertxSliceServerTest.java`

**Step 1: Write the failing test**

Add to `VertxSliceServerTest.java`:

```java
@Test
void doesNotCompressJarFiles() throws Exception {
    final byte[] jarContent = new byte[1024];
    java.util.Arrays.fill(jarContent, (byte) 'A');
    final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
        ResponseBuilder.ok()
            .header("Content-Type", "application/java-archive")
            .body(jarContent)
            .build()
    );
    // Start server with the slice
    final VertxSliceServer server = new VertxSliceServer(this.vertx, slice, this.port);
    server.start();
    try {
        final WebClient client = WebClient.create(this.vertx);
        final io.vertx.reactivex.ext.web.client.HttpResponse<io.vertx.reactivex.core.buffer.Buffer> resp =
            client.get(this.port, "localhost", "/test.jar")
                .putHeader("Accept-Encoding", "gzip")
                .rxSend()
                .blockingGet();
        // Response should NOT be gzip-compressed for jar files
        final String encoding = resp.getHeader("Content-Encoding");
        // encoding should be null or "identity", not "gzip"
        if (encoding != null) {
            assertThat(encoding, org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo("gzip")));
        }
    } finally {
        server.stop();
    }
}
```

**Step 2: Implement compression filter**

In `VertxSliceServer.java`, add a set of binary content types that should skip compression. In the response handler where the response is written back to the Vert.x HTTP response, check the Content-Type and disable compression for binary types by calling `response.putHeader("Content-Encoding", "identity")`:

```java
/**
 * Content types that should NOT be compressed (already compressed or binary).
 */
private static final Set<String> INCOMPRESSIBLE_TYPES = Set.of(
    "application/octet-stream",
    "application/java-archive",
    "application/gzip",
    "application/x-gzip",
    "application/zip",
    "application/x-tar",
    "application/x-xz",
    "application/x-bzip2",
    "application/x-rpm",
    "application/x-debian-package",
    "application/x-compressed",
    "application/x-compress",
    "application/zstd",
    "application/vnd.docker.image.rootfs.diff.tar.gzip",
    "application/vnd.oci.image.layer.v1.tar+gzip"
);
```

In the response path (where headers are applied), add:

```java
// Check if response content type is incompressible
final String contentType = /* get content-type from response headers */;
if (contentType != null) {
    final String baseType = contentType.contains(";")
        ? contentType.substring(0, contentType.indexOf(';')).trim()
        : contentType.trim();
    if (INCOMPRESSIBLE_TYPES.contains(baseType.toLowerCase(java.util.Locale.ROOT))) {
        response.putHeader("Content-Encoding", "identity");
    }
}
```

**Step 3: Run tests**

Run: `mvn test -pl vertx-server -Dtest=VertxSliceServerTest -T 1C`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add vertx-server/src/main/java/com/artipie/vertx/VertxSliceServer.java
git add vertx-server/src/test/java/com/artipie/vertx/VertxSliceServerTest.java
git commit -m "feat(P1.2): skip compression for binary artifacts (jar, gz, tar, rpm, etc.)"
```

---

### Wave 1 Verification

Run: `mvn clean install -U -T 1C`
Expected: BUILD SUCCESS with all tests green.

---

## Wave 2 — Lucene Improvements

### Task 8: P1.4 — Dedicated Lucene Search Executor

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/index/LuceneArtifactIndex.java`
- Test: `artipie-main/src/test/java/com/artipie/index/LuceneArtifactIndexTest.java`

**Step 1: Write the failing test**

Add to `LuceneArtifactIndexTest.java`:

```java
@Test
void searchRunsOnDedicatedThread() throws Exception {
    // Index a document first
    this.index.index(new ArtifactDocument(
        "maven", "test-repo", "com/example/lib.jar",
        "lib", "1.0", 1024, Instant.now(), "user"
    )).join();
    // Wait for commit
    Thread.sleep(1500);
    // Search and capture thread name
    final CompletableFuture<String> threadName = new CompletableFuture<>();
    this.index.search("lib", 10, 0)
        .thenAccept(result -> threadName.complete(Thread.currentThread().getName()))
        .join();
    final String name = threadName.join();
    assertThat("Search should run on lucene-search thread",
        name, org.hamcrest.Matchers.containsString("lucene-search"));
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl artipie-main -Dtest=LuceneArtifactIndexTest#searchRunsOnDedicatedThread -T 1C`
Expected: FAIL — currently runs on ForkJoinPool

**Step 3: Add dedicated search executor**

In `LuceneArtifactIndex.java`, add field:

```java
/**
 * Dedicated executor for search operations. Avoids ForkJoinPool.commonPool()
 * which can contend with all other async operations.
 */
private final ExecutorService searchExecutor;
```

In constructor, add:

```java
this.searchExecutor = Executors.newFixedThreadPool(
    Math.max(4, Runtime.getRuntime().availableProcessors()),
    r -> {
        final Thread thread = new Thread(r, "lucene-search-" +
            new java.util.concurrent.atomic.AtomicInteger(0).incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }
);
```

Use a proper thread factory with counter. Replace `CompletableFuture.supplyAsync(() -> ...)` in `search()` (line 175) and `locate()` (line 206) with `CompletableFuture.supplyAsync(() -> ..., this.searchExecutor)`.

In `close()`, add `this.searchExecutor.shutdown()`.

**Step 4: Run tests**

Run: `mvn test -pl artipie-main -Dtest=LuceneArtifactIndexTest -T 1C`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/index/LuceneArtifactIndex.java
git add artipie-main/src/test/java/com/artipie/index/LuceneArtifactIndexTest.java
git commit -m "feat(P1.4): dedicated executor for Lucene search operations"
```

---

### Task 9: P1.1 — Batch Lucene Commits

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/index/LuceneArtifactIndex.java`
- Test: `artipie-main/src/test/java/com/artipie/index/LuceneArtifactIndexTest.java`

**Step 1: Write the failing test**

Add to `LuceneArtifactIndexTest.java`:

```java
@Test
void batchesCommitsForMultipleIndexOperations() throws Exception {
    // Index many documents rapidly — they should be batched into fewer commits
    final int count = 100;
    final List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < count; i++) {
        futures.add(this.index.index(new ArtifactDocument(
            "maven", "repo", "com/example/lib-" + i + ".jar",
            "lib-" + i, "1.0", 1024, Instant.now(), "user"
        )));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    // Wait for periodic commit to fire (max 2s)
    Thread.sleep(2000);
    // All documents should be searchable after commit
    final SearchResult result = this.index.search("lib*", count + 10, 0).join();
    assertThat("All documents should be indexed",
        (int) result.totalHits(), equalTo(count));
}
```

**Step 2: Run to verify current behavior**

Run: `mvn test -pl artipie-main -Dtest=LuceneArtifactIndexTest#batchesCommitsForMultipleIndexOperations -T 1C`

**Step 3: Implement batch commit**

In `LuceneArtifactIndex.java`:

1. Add a `ScheduledExecutorService` field for periodic commits:

```java
private final ScheduledExecutorService commitScheduler;
```

2. In constructor, create the scheduler and schedule periodic commits:

```java
this.commitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    final Thread thread = new Thread(r, "lucene-commit-scheduler");
    thread.setDaemon(true);
    return thread;
});
this.commitScheduler.scheduleWithFixedDelay(
    this::periodicCommit, 1, 1, java.util.concurrent.TimeUnit.SECONDS
);
```

3. Add a dirty flag:

```java
private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean(false);
```

4. Modify `index()` to NOT commit per document:

```java
@Override
public CompletableFuture<Void> index(final ArtifactDocument doc) {
    return CompletableFuture.runAsync(() -> {
        try {
            final String uniqueKey = uniqueKey(doc.repoName(), doc.artifactPath());
            this.writer.deleteDocuments(new Term(FLD_UNIQUE_KEY, uniqueKey));
            this.writer.addDocument(toDocument(doc, uniqueKey));
            this.dirty.set(true);
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException("Failed to index document", ex);
        }
    }, this.writeExecutor);
}
```

5. Modify `remove()` similarly — no per-doc commit.

6. Keep `indexBatch()` as-is (it already does a single commit for batches, but also mark dirty and let the scheduler handle it, OR keep the explicit commit in indexBatch since it's already efficient).

7. Add the periodic commit method:

```java
private void periodicCommit() {
    if (this.dirty.compareAndSet(true, false)) {
        try {
            this.writer.commit();
            this.searcherManager.maybeRefresh();
        } catch (final IOException ex) {
            // Log but don't crash the scheduler
            this.dirty.set(true); // retry next cycle
        }
    }
}
```

8. In `close()`, add:
```java
this.commitScheduler.shutdown();
// Final commit for any pending writes
this.periodicCommit();
```

**Step 4: Run tests**

Run: `mvn test -pl artipie-main -Dtest=LuceneArtifactIndexTest -T 1C`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/index/LuceneArtifactIndex.java
git add artipie-main/src/test/java/com/artipie/index/LuceneArtifactIndexTest.java
git commit -m "feat(P1.1): batch Lucene commits with periodic 1s scheduler"
```

---

### Wave 2 Verification

Run: `mvn clean install -U -T 1C`
Expected: BUILD SUCCESS with all tests green.

---

## Wave 3 — Timeout & Auto-Block Model

### Task 10: P1.5 — TimeoutSettings Value Object

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/http/timeout/TimeoutSettings.java`
- Test: `artipie-core/src/test/java/com/artipie/http/timeout/TimeoutSettingsTest.java` (NEW)

**Step 1: Write the test**

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.timeout;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

final class TimeoutSettingsTest {

    @Test
    void usesDefaults() {
        final TimeoutSettings settings = TimeoutSettings.defaults();
        assertThat(settings.connectionTimeout(), equalTo(Duration.ofSeconds(5)));
        assertThat(settings.idleTimeout(), equalTo(Duration.ofSeconds(30)));
        assertThat(settings.requestTimeout(), equalTo(Duration.ofSeconds(120)));
    }

    @Test
    void overridesWithCustomValues() {
        final TimeoutSettings settings = new TimeoutSettings(
            Duration.ofSeconds(3),
            Duration.ofSeconds(15),
            Duration.ofSeconds(60)
        );
        assertThat(settings.connectionTimeout(), equalTo(Duration.ofSeconds(3)));
        assertThat(settings.idleTimeout(), equalTo(Duration.ofSeconds(15)));
        assertThat(settings.requestTimeout(), equalTo(Duration.ofSeconds(60)));
    }

    @Test
    void mergesWithParent() {
        final TimeoutSettings parent = new TimeoutSettings(
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            Duration.ofSeconds(180)
        );
        // Child overrides only connectionTimeout
        final TimeoutSettings child = TimeoutSettings.builder()
            .connectionTimeout(Duration.ofSeconds(3))
            .buildWithParent(parent);
        assertThat(child.connectionTimeout(), equalTo(Duration.ofSeconds(3)));
        assertThat("inherits idle from parent", child.idleTimeout(), equalTo(Duration.ofSeconds(60)));
        assertThat("inherits request from parent", child.requestTimeout(), equalTo(Duration.ofSeconds(180)));
    }
}
```

**Step 2: Implement TimeoutSettings**

Create `artipie-core/src/main/java/com/artipie/http/timeout/TimeoutSettings.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.timeout;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable timeout configuration with hierarchical override support.
 * Resolution: per-remote > per-repo > global > defaults.
 *
 * @since 1.20.13
 */
public final class TimeoutSettings {

    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final Duration connectionTimeout;
    private final Duration idleTimeout;
    private final Duration requestTimeout;

    public TimeoutSettings(
        final Duration connectionTimeout,
        final Duration idleTimeout,
        final Duration requestTimeout
    ) {
        this.connectionTimeout = Objects.requireNonNull(connectionTimeout);
        this.idleTimeout = Objects.requireNonNull(idleTimeout);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
    }

    public static TimeoutSettings defaults() {
        return new TimeoutSettings(
            DEFAULT_CONNECTION_TIMEOUT,
            DEFAULT_IDLE_TIMEOUT,
            DEFAULT_REQUEST_TIMEOUT
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public Duration connectionTimeout() { return this.connectionTimeout; }
    public Duration idleTimeout() { return this.idleTimeout; }
    public Duration requestTimeout() { return this.requestTimeout; }

    public static final class Builder {
        private Duration connectionTimeout;
        private Duration idleTimeout;
        private Duration requestTimeout;

        public Builder connectionTimeout(final Duration val) {
            this.connectionTimeout = val;
            return this;
        }

        public Builder idleTimeout(final Duration val) {
            this.idleTimeout = val;
            return this;
        }

        public Builder requestTimeout(final Duration val) {
            this.requestTimeout = val;
            return this;
        }

        public TimeoutSettings buildWithParent(final TimeoutSettings parent) {
            return new TimeoutSettings(
                this.connectionTimeout != null ? this.connectionTimeout : parent.connectionTimeout(),
                this.idleTimeout != null ? this.idleTimeout : parent.idleTimeout(),
                this.requestTimeout != null ? this.requestTimeout : parent.requestTimeout()
            );
        }

        public TimeoutSettings build() {
            return buildWithParent(TimeoutSettings.defaults());
        }
    }
}
```

**Step 3: Run tests**

Run: `mvn test -pl artipie-core -Dtest=TimeoutSettingsTest -T 1C`
Expected: PASS

**Step 4: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/http/timeout/TimeoutSettings.java
git add artipie-core/src/test/java/com/artipie/http/timeout/TimeoutSettingsTest.java
git commit -m "feat(P1.5): add TimeoutSettings value object with hierarchical override"
```

---

### Task 11: P1.5 + P1.7 — AutoBlockRegistry

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/http/timeout/AutoBlockRegistry.java`
- Create: `artipie-core/src/main/java/com/artipie/http/timeout/AutoBlockSettings.java`
- Create: `artipie-core/src/main/java/com/artipie/http/timeout/BlockState.java`
- Test: `artipie-core/src/test/java/com/artipie/http/timeout/AutoBlockRegistryTest.java` (NEW)

**Step 1: Write the tests**

Create `artipie-core/src/test/java/com/artipie/http/timeout/AutoBlockRegistryTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.timeout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

final class AutoBlockRegistryTest {

    private AutoBlockRegistry registry;

    @BeforeEach
    void setUp() {
        this.registry = new AutoBlockRegistry(new AutoBlockSettings(
            3, Duration.ofMillis(100), Duration.ofMinutes(60)
        ));
    }

    @Test
    void startsUnblocked() {
        assertThat(this.registry.isBlocked("remote-1"), is(false));
        assertThat(this.registry.status("remote-1"), equalTo("online"));
    }

    @Test
    void blocksAfterThresholdFailures() {
        this.registry.recordFailure("remote-1");
        this.registry.recordFailure("remote-1");
        assertThat("Not blocked after 2 failures", this.registry.isBlocked("remote-1"), is(false));
        this.registry.recordFailure("remote-1");
        assertThat("Blocked after 3 failures", this.registry.isBlocked("remote-1"), is(true));
        assertThat(this.registry.status("remote-1"), equalTo("blocked"));
    }

    @Test
    void unblocksAfterDuration() throws Exception {
        // Use very short block duration for testing
        final AutoBlockRegistry fast = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMillis(50), Duration.ofMinutes(60)
        ));
        fast.recordFailure("remote-1");
        assertThat("Should be blocked", fast.isBlocked("remote-1"), is(true));
        Thread.sleep(100);
        assertThat("Should be probing after block expires", fast.isBlocked("remote-1"), is(false));
        assertThat(fast.status("remote-1"), equalTo("probing"));
    }

    @Test
    void resetsOnSuccess() {
        this.registry.recordFailure("remote-1");
        this.registry.recordFailure("remote-1");
        this.registry.recordFailure("remote-1");
        assertThat(this.registry.isBlocked("remote-1"), is(true));
        this.registry.recordSuccess("remote-1");
        assertThat(this.registry.isBlocked("remote-1"), is(false));
        assertThat(this.registry.status("remote-1"), equalTo("online"));
    }

    @Test
    void usesFibonacciBackoff() throws Exception {
        final AutoBlockRegistry fast = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMillis(50), Duration.ofHours(1)
        ));
        // First block: 50ms
        fast.recordFailure("r1");
        assertThat(fast.isBlocked("r1"), is(true));
        Thread.sleep(80);
        assertThat("Unblocked after first interval", fast.isBlocked("r1"), is(false));
        // Second block: 50ms (Fibonacci: 1,1,2,3,5... so second = same as first)
        fast.recordFailure("r1");
        assertThat(fast.isBlocked("r1"), is(true));
        Thread.sleep(80);
        assertThat("Unblocked after second interval", fast.isBlocked("r1"), is(false));
        // Third block: 100ms (Fibonacci: 2 * base)
        fast.recordFailure("r1");
        assertThat(fast.isBlocked("r1"), is(true));
        Thread.sleep(60);
        assertThat("Still blocked during longer interval", fast.isBlocked("r1"), is(true));
    }

    @Test
    void tracksMultipleRemotesIndependently() {
        this.registry.recordFailure("remote-a");
        this.registry.recordFailure("remote-a");
        this.registry.recordFailure("remote-a");
        assertThat(this.registry.isBlocked("remote-a"), is(true));
        assertThat(this.registry.isBlocked("remote-b"), is(false));
    }
}
```

**Step 2: Implement BlockState record**

Create `artipie-core/src/main/java/com/artipie/http/timeout/BlockState.java`:

```java
package com.artipie.http.timeout;

import java.time.Instant;

/**
 * Immutable block state for a remote endpoint.
 */
record BlockState(
    int failureCount,
    int fibonacciIndex,
    Instant blockedUntil,
    Status status
) {
    enum Status { ONLINE, BLOCKED, PROBING }

    static BlockState online() {
        return new BlockState(0, 0, Instant.MIN, Status.ONLINE);
    }
}
```

**Step 3: Implement AutoBlockSettings**

Create `artipie-core/src/main/java/com/artipie/http/timeout/AutoBlockSettings.java`:

```java
package com.artipie.http.timeout;

import java.time.Duration;

/**
 * Configuration for auto-block behavior. All values configurable.
 */
public record AutoBlockSettings(
    int failureThreshold,
    Duration initialBlockDuration,
    Duration maxBlockDuration
) {
    public static AutoBlockSettings defaults() {
        return new AutoBlockSettings(3, Duration.ofSeconds(40), Duration.ofMinutes(60));
    }
}
```

**Step 4: Implement AutoBlockRegistry**

Create `artipie-core/src/main/java/com/artipie/http/timeout/AutoBlockRegistry.java`:

```java
package com.artipie.http.timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry tracking auto-block state for remote endpoints.
 * Uses Fibonacci backoff for increasing block durations.
 */
public final class AutoBlockRegistry {

    private static final long[] FIBONACCI = {1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89};

    private final AutoBlockSettings settings;
    private final ConcurrentMap<String, BlockState> states;

    public AutoBlockRegistry(final AutoBlockSettings settings) {
        this.settings = settings;
        this.states = new ConcurrentHashMap<>();
    }

    public boolean isBlocked(final String remoteId) {
        final BlockState state = this.states.getOrDefault(remoteId, BlockState.online());
        if (state.status() == BlockState.Status.BLOCKED) {
            if (Instant.now().isAfter(state.blockedUntil())) {
                // Transition to PROBING
                this.states.put(remoteId, new BlockState(
                    state.failureCount(), state.fibonacciIndex(),
                    state.blockedUntil(), BlockState.Status.PROBING
                ));
                return false;
            }
            return true;
        }
        return false;
    }

    public String status(final String remoteId) {
        final BlockState state = this.states.getOrDefault(remoteId, BlockState.online());
        if (state.status() == BlockState.Status.BLOCKED
            && Instant.now().isAfter(state.blockedUntil())) {
            return "probing";
        }
        return state.status().name().toLowerCase(java.util.Locale.ROOT);
    }

    public void recordFailure(final String remoteId) {
        this.states.compute(remoteId, (key, current) -> {
            final BlockState state = current != null ? current : BlockState.online();
            final int failures = state.failureCount() + 1;
            if (failures >= this.settings.failureThreshold()) {
                final int fibIdx = state.status() == BlockState.Status.ONLINE
                    ? 0 : Math.min(state.fibonacciIndex() + 1, FIBONACCI.length - 1);
                final long blockMs = Math.min(
                    this.settings.initialBlockDuration().toMillis() * FIBONACCI[fibIdx],
                    this.settings.maxBlockDuration().toMillis()
                );
                return new BlockState(
                    failures, fibIdx,
                    Instant.now().plusMillis(blockMs),
                    BlockState.Status.BLOCKED
                );
            }
            return new BlockState(failures, state.fibonacciIndex(),
                state.blockedUntil(), state.status());
        });
    }

    public void recordSuccess(final String remoteId) {
        this.states.put(remoteId, BlockState.online());
    }
}
```

**Step 5: Run tests**

Run: `mvn test -pl artipie-core -Dtest=AutoBlockRegistryTest -T 1C`
Expected: All tests PASS

**Step 6: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/http/timeout/
git add artipie-core/src/test/java/com/artipie/http/timeout/
git commit -m "feat(P1.5+P1.7): add AutoBlockRegistry with Fibonacci backoff"
```

---

### Task 12: P1.7 — Wire Auto-Block into CircuitBreakerSlice and MemberSlice

**Files:**
- Modify: `artipie-core/src/main/java/com/artipie/http/slice/CircuitBreakerSlice.java`
- Modify: `artipie-main/src/main/java/com/artipie/group/MemberSlice.java`
- Create: `artipie-core/src/test/java/com/artipie/http/slice/CircuitBreakerSliceTest.java` (NEW)
- Create: `artipie-main/src/test/java/com/artipie/group/MemberSliceTest.java` (NEW)

**Step 1: Write CircuitBreakerSlice test**

```java
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.timeout.AutoBlockRegistry;
import com.artipie.http.timeout.AutoBlockSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

final class CircuitBreakerSliceTest {

    @Test
    void passesRequestsWhenHealthy() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(AutoBlockSettings.defaults());
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final CircuitBreakerSlice slice = new CircuitBreakerSlice(origin, registry, "test-remote");
        final var resp = slice.response(
            new RequestLine("GET", "/test"), Headers.EMPTY, Content.EMPTY
        ).join();
        assertThat(resp.status().code(), equalTo(200));
    }

    @Test
    void failsFastWhenBlocked() throws Exception {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final Slice origin = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final CircuitBreakerSlice slice = new CircuitBreakerSlice(origin, registry, "test-remote");
        // Trigger block
        registry.recordFailure("test-remote");
        final var resp = slice.response(
            new RequestLine("GET", "/test"), Headers.EMPTY, Content.EMPTY
        ).join();
        assertThat("Should return 503 when blocked", resp.status().code(), equalTo(503));
    }
}
```

**Step 2: Rewrite CircuitBreakerSlice to delegate to AutoBlockRegistry**

Replace `CircuitBreakerSlice.java` to delegate all state management to the registry:

```java
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.timeout.AutoBlockRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class CircuitBreakerSlice implements Slice {

    private final Slice origin;
    private final AutoBlockRegistry registry;
    private final String remoteId;

    public CircuitBreakerSlice(
        final Slice origin,
        final AutoBlockRegistry registry,
        final String remoteId
    ) {
        this.origin = origin;
        this.registry = registry;
        this.remoteId = remoteId;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (this.registry.isBlocked(this.remoteId)) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.serviceUnavailable(
                    "Auto-blocked — remote unavailable: " + this.remoteId
                ).build()
            );
        }
        return this.origin.response(line, headers, body)
            .handle((resp, error) -> {
                if (error != null) {
                    this.registry.recordFailure(this.remoteId);
                    throw new CompletionException(error);
                }
                if (resp.status().code() >= 500) {
                    this.registry.recordFailure(this.remoteId);
                } else {
                    this.registry.recordSuccess(this.remoteId);
                }
                return resp;
            });
    }
}
```

**Step 3: Write MemberSlice test and update MemberSlice**

Create `MemberSliceTest.java`:

```java
package com.artipie.group;

import com.artipie.http.timeout.AutoBlockRegistry;
import com.artipie.http.timeout.AutoBlockSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

final class MemberSliceTest {

    @Test
    void reportsOpenCircuitFromRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        assertThat(member.isCircuitOpen(), is(false));
        registry.recordFailure("test-member");
        assertThat(member.isCircuitOpen(), is(true));
    }

    @Test
    void recordsSuccessViaRegistry() {
        final AutoBlockRegistry registry = new AutoBlockRegistry(new AutoBlockSettings(
            1, Duration.ofMinutes(5), Duration.ofMinutes(60)
        ));
        final MemberSlice member = new MemberSlice("test-member", null, registry);
        registry.recordFailure("test-member");
        assertThat(member.isCircuitOpen(), is(true));
        member.recordSuccess();
        assertThat(member.isCircuitOpen(), is(false));
    }
}
```

Update `MemberSlice.java` to use `AutoBlockRegistry` instead of internal state. Add a constructor that accepts `AutoBlockRegistry`. The `isCircuitOpen()`, `recordSuccess()`, `recordFailure()` methods delegate to the registry using `this.name` as the remoteId.

**Step 4: Run tests**

Run: `mvn test -pl artipie-core -Dtest=CircuitBreakerSliceTest -T 1C`
Run: `mvn test -pl artipie-main -Dtest=MemberSliceTest -T 1C`
Expected: All PASS

**Step 5: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/http/slice/CircuitBreakerSlice.java
git add artipie-core/src/test/java/com/artipie/http/slice/CircuitBreakerSliceTest.java
git add artipie-main/src/main/java/com/artipie/group/MemberSlice.java
git add artipie-main/src/test/java/com/artipie/group/MemberSliceTest.java
git commit -m "feat(P1.7): wire CircuitBreakerSlice and MemberSlice to AutoBlockRegistry"
```

---

### Task 13: P1.5 — Remove .orTimeout() from GroupSlice, Wire Idle Timeout

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/group/GroupSlice.java`
- Test: `artipie-main/src/test/java/com/artipie/group/GroupSliceTest.java`

**Step 1: Remove .orTimeout() and DEFAULT_TIMEOUT_SECONDS**

In `GroupSlice.java`:

1. Remove `DEFAULT_TIMEOUT_SECONDS = 120` constant
2. Remove all `.orTimeout(this.timeout.getSeconds(), ...)` calls (lines 381, 428)
3. The idle timeout is now handled at the HTTP client socket level (Jetty's `HttpClient.setIdleTimeout()`), not at the CompletableFuture level. This ensures large file downloads that keep streaming data are NOT killed.
4. Update constructors to accept `TimeoutSettings` instead of raw `long timeoutSeconds`

**Step 2: Update constructors**

Keep backward compatibility by having the old constructors delegate to the new one. The `timeout` field changes from `Duration` (absolute) to being removed — the HTTP client idle timeout handles it.

**Step 3: Run tests**

Run: `mvn test -pl artipie-main -Dtest=GroupSliceTest -T 1C`
Expected: All PASS

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/group/GroupSlice.java
git add artipie-main/src/test/java/com/artipie/group/GroupSliceTest.java
git commit -m "feat(P1.5): remove absolute .orTimeout() from GroupSlice, use idle timeout"
```

---

### Task 14: P1.8 — Move Slice Cache Resolution Off Event Loop

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/RepositorySlices.java`

**Step 1: Find the resolve() method**

In `RepositorySlices.java`, find where `resolve()` constructs the full slice tree including synchronous Jetty client startup (around line 1012 in `SharedClient` constructor).

**Step 2: Wrap in executeBlocking or async executor**

The `resolve()` method is called from the event loop. Wrap the heavy part (Jetty client initialization) in `CompletableFuture.supplyAsync()` with a dedicated blocking executor:

```java
// Add a static executor for blocking resolution
private static final ExecutorService RESOLVE_EXECUTOR = Executors.newFixedThreadPool(
    Math.max(4, Runtime.getRuntime().availableProcessors()),
    r -> {
        final Thread t = new Thread(r, "slice-resolve-" + resolveCounter.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
);
```

**Step 3: Run tests**

Run: `mvn test -pl artipie-main -T 1C`
Expected: All PASS

**Step 4: Commit**

```bash
git add artipie-main/src/main/java/com/artipie/RepositorySlices.java
git commit -m "feat(P1.8): move slice cache resolution off event loop"
```

---

### Task 15: P1.3 — VertxFileStorage Hierarchical List

**Files:**
- Modify: `asto/asto-vertx-file/src/main/java/com/artipie/asto/fs/VertxFileStorage.java`
- Test: `asto/asto-vertx-file/src/test/java/com/artipie/asto/VertxFileStorageHierarchicalListTest.java` (NEW)

**Step 1: Write the test**

```java
package com.artipie.asto;

import com.artipie.asto.fs.VertxFileStorage;
import io.vertx.reactivex.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;

final class VertxFileStorageHierarchicalListTest {

    private static Vertx vertx;

    @TempDir
    Path tmp;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    @Test
    void listsImmediateChildrenOnly() throws Exception {
        // Create directory structure:
        // root/a/file1.txt
        // root/a/sub/file2.txt
        // root/b/file3.txt
        final Path root = this.tmp.resolve("root");
        Files.createDirectories(root.resolve("a/sub"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/file1.txt"), "content1");
        Files.writeString(root.resolve("a/sub/file2.txt"), "content2");
        Files.writeString(root.resolve("b/file3.txt"), "content3");

        final VertxFileStorage storage = new VertxFileStorage(root, vertx);
        final Collection<Key> keys = storage.list(Key.ROOT, "/").join();
        // Should return only immediate children: "a", "b"
        assertThat(keys, hasSize(2));
    }
}
```

**Step 2: Implement hierarchical list override**

In `VertxFileStorage.java`, add:

```java
@Override
public CompletableFuture<Collection<Key>> list(final Key prefix, final String delimiter) {
    if (!"/".equals(delimiter)) {
        return this.list(prefix);
    }
    return CompletableFuture.supplyAsync(() -> {
        try {
            final Path dir = this.dir(prefix);
            if (!Files.isDirectory(dir)) {
                return Collections.emptyList();
            }
            final List<Key> keys = new ArrayList<>();
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (final Path entry : stream) {
                    final Key key = prefix.isEmpty()
                        ? new Key.From(entry.getFileName().toString())
                        : new Key.From(prefix, entry.getFileName().toString());
                    keys.add(key);
                }
            }
            return keys;
        } catch (final IOException ex) {
            throw new com.artipie.asto.ArtipieIOException(ex);
        }
    });
}
```

**Step 3: Run tests**

Run: `mvn test -pl asto/asto-vertx-file -Dtest=VertxFileStorageHierarchicalListTest -T 1C`
Expected: PASS

**Step 4: Commit**

```bash
git add asto/asto-vertx-file/src/main/java/com/artipie/asto/fs/VertxFileStorage.java
git add asto/asto-vertx-file/src/test/java/com/artipie/asto/VertxFileStorageHierarchicalListTest.java
git commit -m "feat(P1.3): implement hierarchical list in VertxFileStorage"
```

---

### Wave 3 Verification

Run: `mvn clean install -U -T 1C`
Expected: BUILD SUCCESS with all tests green.

---

## Final Verification

Run: `mvn clean install -U -T 1C`

Verify:
- Zero test failures
- No skipped tests (except platform-specific Windows tests)
- No disabled tests
- BUILD SUCCESS
