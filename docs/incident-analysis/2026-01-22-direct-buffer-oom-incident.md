# Direct Buffer OOM Incident Analysis - January 22, 2026

**Incident Date:** January 22, 2026, 17:42-18:13 Berlin Time (16:42-17:13 UTC)
**Affected Component:** Artipie Repository Manager v1.20.12
**Host:** ssvc-prod-12
**Symptom:** java.lang.OutOfMemoryError: Cannot reserve direct buffer memory
**Analysis Date:** January 22, 2026

---

## Executive Summary

The OOM incident was caused by a **direct buffer memory leak** in `FileSystemArtifactSlice.BackpressureFileSubscription`. When HTTP requests are abandoned (client disconnect, timeout, or stall), the 1MB direct ByteBuffer allocated per request is not released because `Subscription.cancel()` is often not called by Vert.x when connections close unexpectedly.

This bug is **related to but distinct from** the January 20 stalling incident. The stalling bug (now fixed in HEAD but not in 1.20.12) causes requests to hang, which exacerbates the buffer leak by keeping more requests active.

---

## Evidence Summary

### OOM Log Analysis (oom.csv)

| Timestamp (Berlin) | Thread | Allocated | Limit | Gap |
|-------------------|--------|-----------|-------|-----|
| 17:42:26.322 | worker-7 | 4,294,002,341 | 4,294,967,296 | 965 KB |
| 17:42:54.203 | worker-3 | 4,293,975,506 | 4,294,967,296 | 992 KB |
| 17:42:54.204 | worker-9 | 4,294,049,464 | 4,294,967,296 | 918 KB |
| 17:43:00.056 | worker-2 | 4,294,942,392 | 4,294,967,296 | 25 KB |
| 17:43:00.059 | worker-6 | 4,294,966,999 | 4,294,967,296 | 297 bytes |
| 17:43:05.200 | worker-4 | 4,294,393,559 | 4,294,967,296 | 574 KB |
| 17:43:05.201 | worker-10 | 4,294,082,263 | 4,294,967,296 | 885 KB |

**Key observation:** All OOMs occurred at exactly the 4GB limit (`-XX:MaxDirectMemorySize=4g`).

### Stack Trace Analysis

All OOMs have identical stack traces:
```
java.lang.OutOfMemoryError: Cannot reserve 1048576 bytes of direct buffer memory
    at java.base/java.nio.Bits.reserveMemory(Unknown Source)
    at java.base/java.nio.DirectByteBuffer.<init>(Unknown Source)
    at java.base/java.nio.ByteBuffer.allocateDirect(Unknown Source)
    at com.artipie.http.slice.FileSystemArtifactSlice$BackpressureFileSubscription.drainLoop(FileSystemArtifactSlice.java:317)
    at com.artipie.http.slice.FileSystemArtifactSlice$BackpressureFileSubscription.lambda$drain$0(FileSystemArtifactSlice.java:295)
```

**Root location:** `FileSystemArtifactSlice.java:317` - `ByteBuffer.allocateDirect(chunkSize)`

### Prometheus Metrics Analysis

Direct buffer memory usage around incident time:

| Timestamp (UTC) | Direct Memory | Active GET Requests |
|-----------------|---------------|---------------------|
| 16:30:00 | 4.00 GB | 15 |
| 16:42:00 | 4.00 GB | 32 |
| 17:02:00 | 4.00 GB | 26 |
| 17:30:00 | 3.91 GB | 81 |
| 17:33:00 | 0.10 GB | 1 | (← Container restarted)

**Critical finding:** Direct memory was saturated at 4GB with only ~30 active HTTP requests. If each request used only 1MB, we'd expect ~30MB used, not 4GB. This proves buffers are **not being released**.

### Correlation with Stalling

699 "End has already been called" warnings in logs indicate the stalling race condition was active. When requests stall:
1. Client times out and disconnects
2. Vert.x may not call `cancel()` on the subscription
3. Direct buffer remains allocated
4. Buffer accumulates over hours until OOM

---

## Root Cause Analysis

### The Bug: Missing Cleanup Path

In `FileSystemArtifactSlice.BackpressureFileSubscription`, the 1MB direct buffer is only cleaned up in these paths:

1. **onComplete()** - Stream finished normally
2. **onError()** - IOException occurred
3. **cancel()** - Subscriber explicitly cancelled

**Missing cleanup path:** When the HTTP connection closes unexpectedly (client timeout, disconnect, or stall), Vert.x often does NOT call `Subscription.cancel()`. The subscription becomes orphaned but the buffer remains allocated.

### Code Path Analysis

```java
// Line 316-318: Buffer allocated on first drain
if (directBuffer == null && !cancelled.get()) {
    directBuffer = ByteBuffer.allocateDirect(chunkSize);  // 1MB allocation
}

// Cleanup only called in these cases:
// 1. onComplete() at lines 326, 377
// 2. cancel() at line 280
// 3. IOException catch at line 385
```

### Why ExitOnOutOfMemoryError Didn't Trigger

JVM option `-XX:+ExitOnOutOfMemoryError` only exits when OOM occurs on the **main thread** or propagates properly. In this case:
- OOM occurs in `artipie.io.filesystem.worker-X` threads
- These threads are daemon threads
- The OOM kills the thread but doesn't propagate to main
- JVM stays alive in degraded state

---

## Proposed Solutions

### Solution 1: PhantomReference-Based Buffer Cleanup (Recommended)

Use `PhantomReference` to detect when subscriptions become unreachable and clean up their buffers automatically:

```java
private static final class BackpressureFileSubscription implements Subscription {
    private static final ReferenceQueue<BackpressureFileSubscription> CLEANUP_QUEUE =
        new ReferenceQueue<>();
    private static final ConcurrentHashMap<PhantomReference<BackpressureFileSubscription>, ByteBuffer>
        BUFFER_REGISTRY = new ConcurrentHashMap<>();

    // Static cleanup thread
    static {
        Thread cleaner = new Thread(() -> {
            while (true) {
                try {
                    PhantomReference<?> ref = CLEANUP_QUEUE.remove();
                    ByteBuffer buffer = BUFFER_REGISTRY.remove(ref);
                    if (buffer != null) {
                        cleanDirectBuffer(buffer);
                        EcsLogger.warn("com.artipie.http")
                            .message("Cleaned orphaned direct buffer via PhantomReference")
                            .eventCategory("memory")
                            .eventAction("orphan_cleanup")
                            .log();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "artipie-buffer-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    // In constructor, register this subscription
    BackpressureFileSubscription(...) {
        // ... existing code ...
        PhantomReference<BackpressureFileSubscription> ref =
            new PhantomReference<>(this, CLEANUP_QUEUE);
        // Buffer will be registered when allocated
    }

    // When allocating buffer:
    if (directBuffer == null && !cancelled.get()) {
        directBuffer = ByteBuffer.allocateDirect(chunkSize);
        BUFFER_REGISTRY.put(phantomRef, directBuffer);
    }
}
```

### Solution 2: Timeout-Based Buffer Release (Simpler)

Add a maximum lifetime for subscriptions:

```java
private static final class BackpressureFileSubscription implements Subscription {
    private static final long MAX_SUBSCRIPTION_LIFETIME_MS = 300_000; // 5 minutes
    private final long creationTime = System.currentTimeMillis();

    private void drainLoop() {
        // Check for timeout at start of each drain
        if (System.currentTimeMillis() - creationTime > MAX_SUBSCRIPTION_LIFETIME_MS) {
            if (!cancelled.get() && completed.compareAndSet(false, true)) {
                cleanup();
                subscriber.onError(new TimeoutException(
                    "File streaming exceeded maximum lifetime of " +
                    MAX_SUBSCRIPTION_LIFETIME_MS + "ms"));
            }
            return;
        }
        // ... rest of drainLoop
    }
}
```

### Solution 3: Buffer Pool Instead of Per-Request Allocation

Use a bounded pool of direct buffers:

```java
private static final class DirectBufferPool {
    private static final BlockingQueue<ByteBuffer> POOL =
        new ArrayBlockingQueue<>(100); // Max 100 buffers = 100MB

    static {
        // Pre-allocate buffers
        for (int i = 0; i < 100; i++) {
            POOL.offer(ByteBuffer.allocateDirect(CHUNK_SIZE));
        }
    }

    static ByteBuffer acquire(long timeoutMs) throws InterruptedException {
        return POOL.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    static void release(ByteBuffer buffer) {
        buffer.clear();
        POOL.offer(buffer);
    }
}
```

### Solution 4: Heap Buffers for Large Files (Simplest)

Replace direct buffers with heap buffers and let GC handle cleanup:

```java
// Change line 317 from:
directBuffer = ByteBuffer.allocateDirect(chunkSize);

// To:
directBuffer = ByteBuffer.allocate(chunkSize);  // Heap buffer, GC managed
```

**Trade-off:** ~10-20% slower I/O but no memory leak risk.

---

## Recommended Implementation Priority

| Priority | Solution | Complexity | Risk | Impact |
|----------|----------|------------|------|--------|
| 1 | Solution 4: Heap Buffers | Low | Very Low | Eliminates leak, slight perf impact |
| 2 | Solution 2: Timeout | Low | Low | Limits leak, doesn't eliminate |
| 3 | Solution 1: PhantomReference | Medium | Medium | Best long-term, complex |
| 4 | Solution 3: Buffer Pool | High | Medium | Best performance, complex |

**Immediate recommendation:** Deploy Solution 4 (heap buffers) as hotfix, then implement Solution 1 or 3 for optimal performance.

---

## Reproducing the Bug

### Test Case: Orphaned Subscription Leak

```java
@Test
void orphanedSubscriptionLeaksDirectBuffer() throws Exception {
    // Arrange: Create a large file
    Path testFile = Files.createTempFile("test", ".bin");
    Files.write(testFile, new byte[10 * 1024 * 1024]); // 10MB

    // Create slice
    FileStorage storage = new FileStorage(testFile.getParent());
    FileSystemArtifactSlice slice = new FileSystemArtifactSlice(storage);

    // Get initial direct memory
    long initialMemory = getDirectMemoryUsed();

    // Act: Start 100 requests but abandon them without completing
    List<CompletableFuture<Response>> futures = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
        futures.add(slice.response(
            RequestLine.from("GET /" + testFile.getFileName() + " HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ));
    }

    // Get responses (allocates buffers)
    List<Response> responses = futures.stream()
        .map(f -> f.join())
        .collect(Collectors.toList());

    // Verify buffers are allocated
    long afterAllocation = getDirectMemoryUsed();
    assertTrue(afterAllocation > initialMemory + 50_000_000,
        "Expected at least 50MB direct memory allocated");

    // Now abandon all responses WITHOUT consuming body or cancelling
    responses.clear();
    futures.clear();

    // Force GC - but direct buffers won't be cleaned!
    System.gc();
    Thread.sleep(1000);

    // Assert: Memory should still be high (this is the bug!)
    long afterGc = getDirectMemoryUsed();

    // BUG: This assertion will FAIL on current code because buffers leak
    assertTrue(afterGc < initialMemory + 10_000_000,
        "Expected direct memory to be released after abandoning requests, " +
        "but still have " + (afterGc - initialMemory) / 1024 / 1024 + "MB allocated");
}

private static long getDirectMemoryUsed() {
    return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
        .stream()
        .filter(b -> b.getName().equals("direct"))
        .findFirst()
        .map(BufferPoolMXBean::getMemoryUsed)
        .orElse(0L);
}
```

---

## Monitoring Recommendations

Add these metrics:

```java
// Track active file subscriptions
Metrics.gauge("artipie_filesystem_active_subscriptions",
    activeSubscriptions, AtomicInteger::get);

// Track direct buffer allocations
Metrics.counter("artipie_direct_buffer_allocations").increment();

// Track orphaned buffer cleanups
Metrics.counter("artipie_orphaned_buffer_cleanups").increment();
```

Dashboard alert:
```promql
# Alert when direct memory usage exceeds 80% of limit
jvm_buffer_memory_used_bytes{id="direct"} / 4294967296 > 0.8
```

---

## Related Issues

- **January 20, 2026 Stalling Incident**: The stalling bug exacerbates this buffer leak. When fixed, fewer orphaned subscriptions occur.
- **Version 1.20.12**: Does NOT include the body-aware timeout fix, making it more susceptible to stalls and thus leaks.

---

## Additional Findings: Cascading Failure Analysis

### Metrics Scraper Worker Queue Time Spike

During the incident, the `metrics-scraper` worker pool experienced severe queue time spikes:

| Time (Berlin) | Queue Time (p95) | Normal |
|---------------|------------------|--------|
| 17:44 | **17.5 seconds** | ~1ms |
| 18:50 | **3.5 seconds** | ~1ms |

**Root cause:** When direct buffer memory saturates, the JVM triggers aggressive `System.gc()` (G1 Old Generation). GC was running at **1.2 seconds per second** (100%+ overhead), blocking all worker threads including the metrics scraper pool.

### GC Behavior During Incident

```
G1 Old Generation GC Time Rate:
- 17:30-17:43 Berlin: ~1.2 sec/sec (GC running continuously)
- After restart: ~0 sec/sec (healthy)
```

The GC was triggered by `System.gc()` calls attempting to free direct buffer memory, but direct buffers are NOT freed by normal GC - they require explicit `Cleaner` invocation or `sun.misc.Unsafe` deallocation.

### 404 HEAD Request Storm - The Trigger

Massive spikes in 404 HEAD requests correlated perfectly with the OOM events:

| Time (Berlin) | 404 HEAD Rate | Normal |
|---------------|---------------|--------|
| 17:44-17:48 | **93-142 req/s** | ~0 |
| 18:50-18:54 | **130-200 req/s** | ~0 |

**Hypothesis:** These 404 HEADs are from Maven/Gradle clients checking artifact existence before download. Each HEAD request that returns 404 still goes through `FileSystemArtifactSlice`, potentially allocating buffers that leak when the connection closes.

### Cascading Failure Chain

```
1. 404 HEAD Request Storm (130-200 req/s)
           ↓
2. FileSystemArtifactSlice processes requests
           ↓
3. Buffers allocated but leak on connection close
           ↓
4. Direct memory saturates at 4GB limit
           ↓
5. JVM triggers aggressive System.gc()
           ↓
6. GC runs 100%+ blocking all threads
           ↓
7. Metrics scraper workers blocked (17s queue time)
           ↓
8. Prometheus scrapes timeout, alerting gaps
           ↓
9. OOM when new buffer allocation attempted
```

### Recommendations

1. **Investigate 404 HEAD source**: Identify what's generating the 404 HEAD storm
   - Check CI/CD pipeline logs around incident time
   - Review Maven/Gradle dependency resolution patterns
   - Consider rate limiting or caching 404 responses

2. **Add alerting for direct buffer usage**:
   ```promql
   jvm_buffer_memory_used_bytes{id="direct"} / 4294967296 > 0.8
   ```

3. **Add alerting for metrics scraper queue time**:
   ```promql
   histogram_quantile(0.95, sum(rate(vertx_pool_queue_time_seconds_bucket{pool_name="metrics-scraper"}[5m])) by (le)) > 1
   ```

4. **Consider reducing MaxDirectMemorySize** temporarily while fix is deployed to fail faster and trigger restart

---

## Implementation Status

### Implemented Fix: Timeout-Based Deterministic Cleanup (Solution 2)

**Status:** Implemented and verified in version 1.20.13

The fix adds an inactivity timeout to `BackpressureFileSubscription`. When no `request(n)` calls are received within the timeout period, the subscription automatically calls `cleanup()` to release the direct buffer.

**Key implementation details:**

1. **Configurable timeout** via system property `artipie.filesystem.subscription.timeout` (default: 60 seconds)
2. **ScheduledExecutorService** for timeout scheduling (daemon thread pool)
3. **Timeout reset** on each `request(n)` call to avoid false positives during slow downloads
4. **Timeout cancellation** in `cleanup()` to avoid redundant cleanup attempts

**Code changes in `FileSystemArtifactSlice.java`:**

```java
// Timeout configuration (default 60 seconds)
private static final long INACTIVITY_TIMEOUT_SECONDS = Long.getLong(
    "artipie.filesystem.subscription.timeout", 60);

// Scheduler for timeout tasks
private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
    Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "artipie-subscription-timeout");
        t.setDaemon(true);
        return t;
    });

// In BackpressureFileSubscription:
private volatile ScheduledFuture<?> inactivityTimeout;

private void resetInactivityTimeout() {
    ScheduledFuture<?> existing = this.inactivityTimeout;
    if (existing != null) {
        existing.cancel(false);
    }
    this.inactivityTimeout = TIMEOUT_SCHEDULER.schedule(
        this::cleanup,
        INACTIVITY_TIMEOUT_SECONDS,
        TimeUnit.SECONDS
    );
}

// Called when buffer is allocated in drainLoop()
resetInactivityTimeout();

// In cleanup(), cancel the timeout
ScheduledFuture<?> timeout = this.inactivityTimeout;
if (timeout != null) {
    timeout.cancel(false);
}
```

### Test Verification

Test class: `DirectBufferLeakTest.java`

**TDD Verification Results:**
- **WITHOUT fix:** Test FAILS - "20.0 MB still allocated after 2 second inactivity timeout"
- **WITH fix:** Test PASSES - "0 bytes leaked after timeout"

Five test cases verify the fixes:
1. `orphanedSubscriptionsLeakDirectBuffers` - Main leak test with timeout verification
2. `properlyConsumedRequestsDoNotLeak` - Normal consumption path still works
3. `cancelledSubscriptionsReleaseBuffers` - Explicit cancellation still works
4. `headRequestsDoNotAllocateBuffers` - HEAD optimization verification
5. `headRequestsReturn404ForMissingFiles` - HEAD 404 handling

### Additional Fix: HEAD Request Optimization

**Problem identified:** HEAD requests for existing files were preparing a streaming body (allocating 1MB buffers) even though HEAD responses only need headers. With 22-39 HEAD req/s during the incident, this contributed to the buffer exhaustion.

**JFrog comparison:** JFrog Artifactory handles HEAD requests efficiently by only checking file existence and returning metadata without body preparation.

**Fix implemented:** Added early return for HEAD requests that skips body preparation:

```java
// HEAD request optimization: Return headers only, no body preparation.
if (line.method() == RqMethod.HEAD) {
    return ResponseBuilder.ok()
        .header("Content-Length", String.valueOf(fileSize))
        .header("Accept-Ranges", "bytes")
        .build();  // No body - no buffer allocation!
}
```

**Test verification:**
```
HEAD test - Direct memory after 100 HEAD requests: 32767 bytes
HEAD test - Memory delta: 0 bytes (0.00 KB)
```

100 HEAD requests = **0 bytes** of direct buffer allocation.

### Deployment Checklist

1. [x] Implement timeout-based cleanup (Solution 2)
2. [x] Create automated test to verify fix
3. [x] Verify test fails without fix, passes with fix (TDD)
4. [x] Implement HEAD request optimization (zero buffer allocation)
5. [x] Add tests for HEAD optimization
6. [ ] Deploy version 1.20.13 with fixes
7. [ ] Add monitoring for direct buffer usage
8. [ ] Monitor `artipie-subscription-timeout` thread behavior in production

### Future Considerations

1. [ ] Consider buffer pooling (Solution 3) for higher performance if timeout overhead becomes measurable
2. [ ] Add metrics for timeout-triggered cleanups to track orphan frequency

---

*Analysis performed by Claude Code on January 22, 2026*
*Fix implemented and verified on January 22, 2026*
