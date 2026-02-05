# Vert.x 5 Stream-Through Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement non-blocking stream-through caching using Vert.x 5 native streams (TeeReadStream, AsyncFile, Pump) with virtual thread finalization.

**Architecture:** Data flows through TeeReadStream which duplicates to client response AND AsyncFile cache simultaneously. Backpressure pauses upstream when either destination is full. Virtual thread verticle handles post-stream finalization (checksum, move file).

**Tech Stack:** Vert.x 5.0.0, Java 21 virtual threads, AsyncFile, Pump, ReadStream/WriteStream

---

## Phase 0: Preparation & Cleanup

### Task 0.1: Remove Broken Implementation Files

**Files:**
- Delete: `artipie-core/src/main/java/com/artipie/http/misc/BackpressuredFileTeeContent.java`
- Delete: `artipie-core/src/test/java/com/artipie/http/misc/BackpressuredFileTeeContentTest.java`
- Delete: `docker-adapter/src/main/java/com/artipie/docker/cache/TeeBlob.java`
- Delete: `npm-adapter/src/main/java/com/artipie/npm/proxy/model/TeeNpmAsset.java`

**Step 1: Delete broken files**

```bash
rm -f artipie-core/src/main/java/com/artipie/http/misc/BackpressuredFileTeeContent.java
rm -f artipie-core/src/test/java/com/artipie/http/misc/BackpressuredFileTeeContentTest.java
rm -f docker-adapter/src/main/java/com/artipie/docker/cache/TeeBlob.java
rm -f npm-adapter/src/main/java/com/artipie/npm/proxy/model/TeeNpmAsset.java
```

**Step 2: Verify deletion**

```bash
ls artipie-core/src/main/java/com/artipie/http/misc/
# Should NOT show BackpressuredFileTeeContent.java
```

**Step 3: Commit cleanup**

```bash
git add -A
git commit -m "chore: remove broken BackpressuredFileTeeContent implementation

The blocking I/O implementation caused 79+ second timeouts.
Will be replaced with Vert.x 5 native non-blocking streams."
```

---

## Phase 1: Vert.x 5 Upgrade

### Task 1.1: Update Parent POM Vert.x Version

**Files:**
- Modify: `pom.xml:84`

**Step 1: Update vertx.version property**

Change line 84 from:
```xml
<vertx.version>4.5.22</vertx.version>
```
To:
```xml
<vertx.version>5.0.0</vertx.version>
```

**Step 2: Verify compilation**

```bash
mvn compile -pl artipie-core -DskipTests
```
Expected: BUILD SUCCESS (may have deprecation warnings)

**Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: upgrade Vert.x from 4.5.22 to 5.0.0"
```

### Task 1.2: Fix Vert.x 5 API Breaking Changes in vertx-server

**Files:**
- Modify: `vertx-server/src/main/java/com/artipie/vertx/VertxSliceServer.java`

**Step 1: Check for compilation errors**

```bash
mvn compile -pl vertx-server -DskipTests 2>&1 | grep -i "error\|cannot find"
```

**Step 2: Fix any ThreadingModel imports**

If errors about `setWorker`, replace:
```java
// Old (if present)
new DeploymentOptions().setWorker(true)

// New
import io.vertx.core.ThreadingModel;
new DeploymentOptions().setThreadingModel(ThreadingModel.WORKER)
```

**Step 3: Fix any callback-based APIs to Future-based**

Vert.x 5 removes callback overloads. If errors like "method not found":
```java
// Old
vertx.fileSystem().open(path, opts, ar -> { ... })

// New
vertx.fileSystem().open(path, opts)
    .onSuccess(file -> { ... })
    .onFailure(err -> { ... })
```

**Step 4: Verify compilation**

```bash
mvn compile -pl vertx-server -DskipTests
```
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add vertx-server/
git commit -m "fix: update vertx-server for Vert.x 5 API changes"
```

### Task 1.3: Fix Vert.x 5 Changes Across All Modules

**Files:**
- Modify: Multiple modules as needed

**Step 1: Compile all modules**

```bash
mvn compile -DskipTests 2>&1 | tee /tmp/vertx5-compile.log
```

**Step 2: Fix errors iteratively**

For each module with errors, apply the same patterns:
- `setWorker(true)` → `setThreadingModel(ThreadingModel.WORKER)`
- Callback handlers → Future chains
- `Vertx.vertx(options)` → `Vertx.builder().with(options).build()` (if used)

**Step 3: Verify full compilation**

```bash
mvn compile -DskipTests
```
Expected: BUILD SUCCESS for all modules

**Step 4: Commit**

```bash
git add -A
git commit -m "fix: update all modules for Vert.x 5 compatibility"
```

---

## Phase 2: Core Streaming Infrastructure

### Task 2.1: Create TeeReadStream Interface

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/http/stream/TeeReadStream.java`
- Test: `artipie-core/src/test/java/com/artipie/http/stream/TeeReadStreamTest.java`

**Step 1: Write the failing test**

Create `artipie-core/src/test/java/com/artipie/http/stream/TeeReadStreamTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TeeReadStream}.
 */
final class TeeReadStreamTest {

    private FakeReadStream source;
    private FakeWriteStream dest1;
    private FakeWriteStream dest2;

    @BeforeEach
    void setUp() {
        this.source = new FakeReadStream();
        this.dest1 = new FakeWriteStream();
        this.dest2 = new FakeWriteStream();
    }

    @Test
    void duplicatesDataToBothDestinations() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        source.emit(Buffer.buffer("chunk1"));
        source.emit(Buffer.buffer("chunk2"));

        assertEquals(2, dest1.received.size());
        assertEquals(2, dest2.received.size());
        assertEquals("chunk1", dest1.received.get(0).toString());
        assertEquals("chunk2", dest2.received.get(1).toString());
    }

    @Test
    void pausesWhenEitherDestinationFull() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        // Fill dest1's queue
        dest1.setWriteQueueFull(true);
        source.emit(Buffer.buffer("data"));

        assertTrue(source.isPaused(), "Source should be paused when dest1 queue full");
    }

    @Test
    void resumesOnlyWhenBothDestinationsReady() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        // Both destinations full
        dest1.setWriteQueueFull(true);
        dest2.setWriteQueueFull(true);
        source.emit(Buffer.buffer("data"));
        assertTrue(source.isPaused());

        // Only dest1 drains - should still be paused
        dest1.setWriteQueueFull(false);
        dest1.triggerDrain();
        assertTrue(source.isPaused(), "Should remain paused until both ready");

        // Now dest2 drains - should resume
        dest2.setWriteQueueFull(false);
        dest2.triggerDrain();
        assertFalse(source.isPaused(), "Should resume when both ready");
    }

    @Test
    void continuesServingClientWhenCacheDetached() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        tee.start();

        // Simulate cache error
        tee.detachSecondary();

        // Data should still flow to dest1
        source.emit(Buffer.buffer("after-detach"));

        assertEquals(1, dest1.received.size());
        assertEquals(0, dest2.received.size(), "Detached dest should not receive");
    }

    @Test
    void forwardsEndToHandler() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        final AtomicBoolean ended = new AtomicBoolean(false);
        tee.endHandler(v -> ended.set(true));
        tee.start();

        source.end();

        assertTrue(ended.get());
    }

    @Test
    void forwardsExceptionToHandler() {
        final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, dest1, dest2);
        final AtomicBoolean errored = new AtomicBoolean(false);
        tee.exceptionHandler(err -> errored.set(true));
        tee.start();

        source.fail(new RuntimeException("test error"));

        assertTrue(errored.get());
    }

    // ---- Fake implementations for testing ----

    static class FakeReadStream implements ReadStream<Buffer> {
        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;
        private boolean paused = false;

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            this.paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            this.paused = false;
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> handler) {
            this.endHandler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        void emit(Buffer data) {
            if (dataHandler != null) dataHandler.handle(data);
        }

        void end() {
            if (endHandler != null) endHandler.handle(null);
        }

        void fail(Throwable err) {
            if (exceptionHandler != null) exceptionHandler.handle(err);
        }

        boolean isPaused() {
            return paused;
        }
    }

    static class FakeWriteStream implements WriteStream<Buffer> {
        final List<Buffer> received = new ArrayList<>();
        private Handler<Void> drainHandler;
        private boolean writeQueueFull = false;

        @Override
        public WriteStream<Buffer> write(Buffer data) {
            received.add(data);
            return this;
        }

        @Override
        public void write(Buffer data, io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) {
            received.add(data);
            handler.handle(io.vertx.core.Future.succeededFuture());
        }

        @Override
        public void end(io.vertx.core.Handler<io.vertx.core.AsyncResult<Void>> handler) {
            handler.handle(io.vertx.core.Future.succeededFuture());
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return writeQueueFull;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            this.drainHandler = handler;
            return this;
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        void setWriteQueueFull(boolean full) {
            this.writeQueueFull = full;
        }

        void triggerDrain() {
            if (drainHandler != null) drainHandler.handle(null);
        }
    }
}
```

**Step 2: Run test to verify it fails**

```bash
mvn test -pl artipie-core -Dtest=TeeReadStreamTest -DfailIfNoTests=false
```
Expected: FAIL - class TeeReadStream not found

**Step 3: Create package directory**

```bash
mkdir -p artipie-core/src/main/java/com/artipie/http/stream
mkdir -p artipie-core/src/test/java/com/artipie/http/stream
```

**Step 4: Write TeeReadStream implementation**

Create `artipie-core/src/main/java/com/artipie/http/stream/TeeReadStream.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A ReadStream that duplicates data to two WriteStream destinations.
 * Implements proper backpressure: pauses source if EITHER destination is full,
 * resumes only when BOTH destinations are ready.
 *
 * <p>If the secondary destination fails, it can be detached and streaming
 * continues to the primary destination only.</p>
 *
 * @param <T> The data type (typically Buffer)
 * @since 1.21.0
 */
public final class TeeReadStream<T> implements ReadStream<T> {

    /**
     * Source stream.
     */
    private final ReadStream<T> source;

    /**
     * Primary destination (e.g., client response).
     */
    private final WriteStream<T> primary;

    /**
     * Secondary destination (e.g., cache file).
     */
    private final WriteStream<T> secondary;

    /**
     * Whether secondary is detached due to error.
     */
    private final AtomicBoolean secondaryDetached;

    /**
     * Whether primary destination is ready for more data.
     */
    private volatile boolean primaryReady;

    /**
     * Whether secondary destination is ready for more data.
     */
    private volatile boolean secondaryReady;

    /**
     * Whether source is currently paused.
     */
    private final AtomicBoolean paused;

    /**
     * Handler for data events.
     */
    private Handler<T> dataHandler;

    /**
     * Handler for end event.
     */
    private Handler<Void> endHandler;

    /**
     * Handler for exceptions.
     */
    private Handler<Throwable> exceptionHandler;

    /**
     * Constructor.
     *
     * @param source Source stream to read from
     * @param primary Primary destination (always receives data)
     * @param secondary Secondary destination (can be detached on error)
     */
    public TeeReadStream(
        final ReadStream<T> source,
        final WriteStream<T> primary,
        final WriteStream<T> secondary
    ) {
        this.source = Objects.requireNonNull(source);
        this.primary = Objects.requireNonNull(primary);
        this.secondary = Objects.requireNonNull(secondary);
        this.secondaryDetached = new AtomicBoolean(false);
        this.primaryReady = true;
        this.secondaryReady = true;
        this.paused = new AtomicBoolean(false);
    }

    /**
     * Start streaming. Sets up handlers and begins flow.
     *
     * @return this
     */
    public TeeReadStream<T> start() {
        // Set up drain handlers for backpressure
        this.primary.drainHandler(v -> {
            this.primaryReady = true;
            this.maybeResume();
        });

        this.secondary.drainHandler(v -> {
            this.secondaryReady = true;
            this.maybeResume();
        });

        // Set up source handlers
        this.source.handler(this::handleData);
        this.source.endHandler(this::handleEnd);
        this.source.exceptionHandler(this::handleException);

        return this;
    }

    /**
     * Detach secondary destination. Data will only flow to primary.
     * Used when cache write fails but client should still receive data.
     */
    public void detachSecondary() {
        this.secondaryDetached.set(true);
        this.secondaryReady = true; // Always "ready" when detached
        this.maybeResume();
    }

    /**
     * Check if secondary is detached.
     *
     * @return true if secondary is detached
     */
    public boolean isSecondaryDetached() {
        return this.secondaryDetached.get();
    }

    @Override
    public TeeReadStream<T> handler(final Handler<T> handler) {
        this.dataHandler = handler;
        return this;
    }

    @Override
    public TeeReadStream<T> pause() {
        this.source.pause();
        this.paused.set(true);
        return this;
    }

    @Override
    public TeeReadStream<T> resume() {
        this.paused.set(false);
        this.source.resume();
        return this;
    }

    @Override
    public TeeReadStream<T> fetch(final long amount) {
        this.source.fetch(amount);
        return this;
    }

    @Override
    public TeeReadStream<T> endHandler(final Handler<Void> handler) {
        this.endHandler = handler;
        return this;
    }

    @Override
    public TeeReadStream<T> exceptionHandler(final Handler<Throwable> handler) {
        this.exceptionHandler = handler;
        return this;
    }

    /**
     * Handle incoming data from source.
     *
     * @param data The data chunk
     */
    private void handleData(final T data) {
        // Always write to primary
        this.primary.write(data);

        // Write to secondary if not detached
        if (!this.secondaryDetached.get()) {
            this.secondary.write(data);
        }

        // Check backpressure
        final boolean primaryFull = this.primary.writeQueueFull();
        final boolean secondaryFull = !this.secondaryDetached.get()
            && this.secondary.writeQueueFull();

        if (primaryFull) {
            this.primaryReady = false;
        }
        if (secondaryFull) {
            this.secondaryReady = false;
        }

        if (primaryFull || secondaryFull) {
            this.source.pause();
            this.paused.set(true);
        }

        // Forward to downstream handler
        if (this.dataHandler != null) {
            this.dataHandler.handle(data);
        }
    }

    /**
     * Handle end of stream.
     *
     * @param ignored Void
     */
    private void handleEnd(final Void ignored) {
        if (this.endHandler != null) {
            this.endHandler.handle(null);
        }
    }

    /**
     * Handle exception from source.
     *
     * @param error The error
     */
    private void handleException(final Throwable error) {
        if (this.exceptionHandler != null) {
            this.exceptionHandler.handle(error);
        }
    }

    /**
     * Resume source if both destinations are ready.
     */
    private void maybeResume() {
        if (this.paused.get() && this.primaryReady && this.secondaryReady) {
            this.paused.set(false);
            this.source.resume();
        }
    }
}
```

**Step 5: Run tests**

```bash
mvn test -pl artipie-core -Dtest=TeeReadStreamTest
```
Expected: All tests PASS

**Step 6: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/http/stream/
git add artipie-core/src/test/java/com/artipie/http/stream/
git commit -m "feat(core): add TeeReadStream for non-blocking stream duplication

Implements proper backpressure:
- Pauses source if EITHER destination queue is full
- Resumes only when BOTH destinations are ready
- Secondary can be detached on error (client still receives data)"
```

---

### Task 2.2: Create CacheStreamHandler

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/http/stream/CacheStreamHandler.java`
- Test: `artipie-core/src/test/java/com/artipie/http/stream/CacheStreamHandlerTest.java`

**Step 1: Write the failing test**

Create `artipie-core/src/test/java/com/artipie/http/stream/CacheStreamHandlerTest.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CacheStreamHandler}.
 */
@ExtendWith(VertxExtension.class)
final class CacheStreamHandlerTest {

    @TempDir
    Path tempDir;

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    void streamsDataToClientAndCache(VertxTestContext ctx) throws Exception {
        final CacheStreamHandler handler = new CacheStreamHandler(vertx, tempDir);
        final Buffer testData = Buffer.buffer("Hello, World!");
        final Path cacheFile = tempDir.resolve("cached.dat");

        // Create a simple source that emits test data
        final FakeAsyncReadStream source = new FakeAsyncReadStream(vertx);
        final CollectingWriteStream client = new CollectingWriteStream();

        handler.streamWithCache(source, cacheFile, client)
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    // Client received data
                    assertEquals(testData.toString(), client.getCollected().toString());

                    // Cache file exists with same data
                    assertTrue(vertx.fileSystem().existsBlocking(cacheFile.toString()));
                    Buffer cached = vertx.fileSystem().readFileBlocking(cacheFile.toString());
                    assertEquals(testData.toString(), cached.toString());
                });
                ctx.completeNow();
            }));

        // Emit data after setup
        source.emit(testData);
        source.end();

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
    }

    @Test
    void continuesServingClientWhenCacheFails(VertxTestContext ctx) throws Exception {
        final CacheStreamHandler handler = new CacheStreamHandler(vertx, tempDir);
        final Buffer testData = Buffer.buffer("Data for client only");

        // Use invalid path to trigger cache failure
        final Path invalidPath = Path.of("/nonexistent/directory/file.dat");

        final FakeAsyncReadStream source = new FakeAsyncReadStream(vertx);
        final CollectingWriteStream client = new CollectingWriteStream();

        handler.streamWithCache(source, invalidPath, client)
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    // Client still received data despite cache failure
                    assertEquals(testData.toString(), client.getCollected().toString());
                });
                ctx.completeNow();
            }));

        source.emit(testData);
        source.end();

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
    }

    // Helper classes would be here - simplified for plan
}
```

**Step 2: Run test to verify it fails**

```bash
mvn test -pl artipie-core -Dtest=CacheStreamHandlerTest -DfailIfNoTests=false
```
Expected: FAIL - class CacheStreamHandler not found

**Step 3: Write CacheStreamHandler implementation**

Create `artipie-core/src/main/java/com/artipie/http/stream/CacheStreamHandler.java`:

```java
/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.stream;

import com.artipie.http.log.EcsLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.streams.Pump;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Handles stream-through caching using Vert.x native non-blocking I/O.
 *
 * <p>Data flows through a TeeReadStream to both the client response
 * and an AsyncFile cache simultaneously. Backpressure is handled
 * automatically by pausing the upstream when either destination is full.</p>
 *
 * @since 1.21.0
 */
public final class CacheStreamHandler {

    /**
     * Vert.x instance.
     */
    private final Vertx vertx;

    /**
     * Temporary directory for cache files.
     */
    private final Path tempDir;

    /**
     * Constructor.
     *
     * @param vertx Vert.x instance
     * @param tempDir Temporary directory for cache files
     */
    public CacheStreamHandler(final Vertx vertx, final Path tempDir) {
        this.vertx = Objects.requireNonNull(vertx);
        this.tempDir = Objects.requireNonNull(tempDir);
    }

    /**
     * Stream data to client while caching to file.
     *
     * @param source Source stream (upstream response)
     * @param cachePath Final cache file path
     * @param client Client response stream
     * @return Future that completes when client streaming is done
     */
    public Future<Void> streamWithCache(
        final ReadStream<Buffer> source,
        final Path cachePath,
        final WriteStream<Buffer> client
    ) {
        final Promise<Void> clientDone = Promise.promise();
        final Path tempFile = this.tempDir.resolve(UUID.randomUUID().toString() + ".tmp");

        this.vertx.fileSystem().open(
            tempFile.toString(),
            new OpenOptions().setWrite(true).setCreate(true).setTruncateExisting(true)
        ).onSuccess(asyncFile -> {
            final TeeReadStream<Buffer> tee = new TeeReadStream<>(source, client, asyncFile);

            // Handle cache write errors - detach and continue
            asyncFile.exceptionHandler(err -> {
                EcsLogger.warn("com.artipie.http.stream")
                    .message("Cache write failed, continuing without cache")
                    .field("path", cachePath.toString())
                    .error(err)
                    .log();
                tee.detachSecondary();
                this.cleanupTempFile(tempFile);
            });

            // On stream end, finalize cache
            tee.endHandler(v -> {
                clientDone.complete();
                this.finalizeCache(asyncFile, tempFile, cachePath);
            });

            // On error, cleanup and fail
            tee.exceptionHandler(err -> {
                clientDone.fail(err);
                asyncFile.close();
                this.cleanupTempFile(tempFile);
            });

            // Start streaming
            tee.start();

        }).onFailure(err -> {
            // Cache open failed - serve without caching
            EcsLogger.warn("com.artipie.http.stream")
                .message("Failed to open cache file, streaming without cache")
                .field("path", cachePath.toString())
                .error(err)
                .log();

            // Direct pump to client only
            Pump.pump(source, client).start();
            source.endHandler(v -> clientDone.complete());
            source.exceptionHandler(clientDone::fail);
        });

        return clientDone.future();
    }

    /**
     * Finalize cache: close file and move to final location.
     * Uses virtual thread for blocking operations.
     *
     * @param asyncFile The async file to close
     * @param tempFile Temporary file path
     * @param finalPath Final cache path
     */
    private void finalizeCache(
        final AsyncFile asyncFile,
        final Path tempFile,
        final Path finalPath
    ) {
        asyncFile.close().onComplete(closed -> {
            // Move temp to final (non-blocking via Vert.x file system)
            this.vertx.fileSystem().move(
                tempFile.toString(),
                finalPath.toString()
            ).onSuccess(v -> {
                EcsLogger.debug("com.artipie.http.stream")
                    .message("Cache file finalized")
                    .field("path", finalPath.toString())
                    .log();
            }).onFailure(err -> {
                EcsLogger.warn("com.artipie.http.stream")
                    .message("Failed to finalize cache file")
                    .field("path", finalPath.toString())
                    .error(err)
                    .log();
                this.cleanupTempFile(tempFile);
            });
        });
    }

    /**
     * Cleanup temporary file on error.
     *
     * @param tempFile Temporary file to delete
     */
    private void cleanupTempFile(final Path tempFile) {
        this.vertx.fileSystem().delete(tempFile.toString())
            .onFailure(err -> {
                EcsLogger.debug("com.artipie.http.stream")
                    .message("Failed to delete temp file")
                    .field("path", tempFile.toString())
                    .log();
            });
    }
}
```

**Step 4: Run tests**

```bash
mvn test -pl artipie-core -Dtest=CacheStreamHandlerTest
```
Expected: All tests PASS

**Step 5: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/http/stream/CacheStreamHandler.java
git add artipie-core/src/test/java/com/artipie/http/stream/CacheStreamHandlerTest.java
git commit -m "feat(core): add CacheStreamHandler for stream-through caching

Uses TeeReadStream + AsyncFile for non-blocking cache writes.
Automatically detaches cache on errors, client always receives data."
```

---

## Phase 3: Adapter Integration

### Task 3.1: Revert Docker CacheLayers to Simple Implementation

**Files:**
- Modify: `docker-adapter/src/main/java/com/artipie/docker/cache/CacheLayers.java`

**Step 1: Restore original simple implementation**

Replace the entire CacheLayers.java with the original working version from git:

```bash
git show 90152301:docker-adapter/src/main/java/com/artipie/docker/cache/CacheLayers.java > docker-adapter/src/main/java/com/artipie/docker/cache/CacheLayers.java
```

**Step 2: Verify compilation**

```bash
mvn compile -pl docker-adapter -DskipTests
```
Expected: BUILD SUCCESS

**Step 3: Run Docker adapter tests**

```bash
mvn test -pl docker-adapter -Dtest=CacheLayersTest
```
Expected: All tests PASS

**Step 4: Commit**

```bash
git add docker-adapter/src/main/java/com/artipie/docker/cache/CacheLayers.java
git commit -m "fix(docker): revert CacheLayers to working implementation

Remove TeeBlob complexity. The original simple fetch-and-return
pattern works correctly without blocking the event loop."
```

### Task 3.2: Revert Maven CachedProxySlice

**Files:**
- Modify: `maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java`

**Step 1: Restore original implementation**

```bash
git show 90152301:maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java > maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java
```

**Step 2: Verify compilation**

```bash
mvn compile -pl maven-adapter -DskipTests
```
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java
git commit -m "fix(maven): revert CachedProxySlice to working implementation"
```

### Task 3.3: Revert Remaining Adapters

Repeat the same pattern for:

**Files:**
- `gradle-adapter/src/main/java/com/artipie/gradle/http/CachedProxySlice.java`
- `go-adapter/src/main/java/com/artipie/http/CachedProxySlice.java`
- `pypi-adapter/src/main/java/com/artipie/pypi/http/CachedPyProxySlice.java`
- `files-adapter/src/main/java/com/artipie/files/FileProxySlice.java`
- `npm-adapter/src/main/java/com/artipie/npm/proxy/NpmProxy.java`

**Step 1: Revert each file**

```bash
git show 90152301:gradle-adapter/src/main/java/com/artipie/gradle/http/CachedProxySlice.java > gradle-adapter/src/main/java/com/artipie/gradle/http/CachedProxySlice.java
git show 90152301:go-adapter/src/main/java/com/artipie/http/CachedProxySlice.java > go-adapter/src/main/java/com/artipie/http/CachedProxySlice.java
git show 90152301:pypi-adapter/src/main/java/com/artipie/pypi/http/CachedPyProxySlice.java > pypi-adapter/src/main/java/com/artipie/pypi/http/CachedPyProxySlice.java
git show 90152301:files-adapter/src/main/java/com/artipie/files/FileProxySlice.java > files-adapter/src/main/java/com/artipie/files/FileProxySlice.java
git show 90152301:npm-adapter/src/main/java/com/artipie/npm/proxy/NpmProxy.java > npm-adapter/src/main/java/com/artipie/npm/proxy/NpmProxy.java
```

**Step 2: Verify compilation**

```bash
mvn compile -DskipTests
```
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add gradle-adapter/ go-adapter/ pypi-adapter/ files-adapter/ npm-adapter/
git commit -m "fix(adapters): revert all proxy slices to working implementations"
```

---

## Phase 4: Full Build Verification

### Task 4.1: Run Full Test Suite

**Step 1: Clean and build**

```bash
mvn clean install -U
```
Expected: BUILD SUCCESS, all tests pass

**Step 2: If tests fail, fix iteratively**

Check test output:
```bash
mvn test 2>&1 | grep -i "failure\|error" | head -20
```

Fix any remaining issues.

**Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: resolve test failures after Vert.x 5 upgrade"
```

---

## Phase 5: Integration Testing

### Task 5.1: Docker Pull Test

**Step 1: Rebuild and deploy**

```bash
mvn clean package -DskipTests -pl artipie-main
docker-compose -f artipie-main/docker-compose/docker-compose.yml up -d --build
```

**Step 2: Test Docker pull**

```bash
docker login localhost:8081 -u admin -p password
docker pull localhost:8081/test_prefix/docker_group/library/alpine:latest
```
Expected: Pull succeeds without timeout

**Step 3: Check for slow warnings**

```bash
docker logs artipie 2>&1 | grep -i "slow member"
```
Expected: No new "slow member response" warnings

**Step 4: Document results**

Update `PROXY_CACHE_DEBUG.md` with test results.

---

## Summary

| Phase | Tasks | Est. Complexity |
|-------|-------|-----------------|
| 0. Cleanup | Remove broken files | Simple |
| 1. Vert.x 5 Upgrade | Update version, fix API changes | Medium |
| 2. Core Streaming | TeeReadStream, CacheStreamHandler | Medium |
| 3. Adapter Integration | Revert 7 adapters | Simple |
| 4. Build Verification | Full test suite | Medium |
| 5. Integration Testing | Docker pull test | Simple |

**Total estimated tasks:** 12 main tasks

**Key success metrics:**
- No "slow member response" warnings
- Docker pull completes without timeout
- All tests pass
- Latency overhead < 10% vs direct proxy
