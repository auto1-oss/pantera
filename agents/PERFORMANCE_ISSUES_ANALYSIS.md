# Artipie Performance Issues Analysis & Solutions (Revised)

**Analysis Date**: December 2025  
**Log Period**: December 19-25, 2025  
**Total Log Entries Analyzed**: 412,295  
**Version**: 1.20.10

---

## Production Environment Configuration

| Resource | Value |
|----------|-------|
| **CPU** | 15 vCPUs |
| **Memory (Soft Limit)** | 20GB |
| **Memory (Hard Limit)** | 24GB |
| **Instance Type** | c6i.4xlarge |

### Current JVM Arguments
```
-Xms16g -Xmx16g
-XX:+UseG1GC -XX:G1ReservePercent=10 -XX:MaxGCPauseMillis=200
-XX:MaxDirectMemorySize=4g
-XX:ParallelGCThreads=8 -XX:ConcGCThreads=2
-XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled
-XX:+UseContainerSupport
-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/artipie/logs/heapdump.hprof
-Xlog:gc*:file=/var/artipie/logs/gc.log:time,uptime:filecount=5,filesize=50M
-Djava.io.tmpdir=/var/artipie/cache/tmp
-Dvertx.cacheDirBase=/var/artipie/cache/tmp
-Dio.netty.leakDetection.level=simple
-XX:InitiatingHeapOccupancyPercent=45
-XX:+AlwaysPreTouch
-Dvertx.max.worker.execute.time=120000000000
-Dartipie.filesystem.io.threads=14
```

---

## Executive Summary

The Artipie deployment experiences severe performance degradation and OOM conditions caused by **a perfect storm of resource exhaustion and architectural issues**:

### The Core Problem: Memory Budget Exceeded

```
Container Memory Budget:
├── Heap (Xmx):           16.0 GB
├── Direct Memory:         4.0 GB
├── Metaspace:            ~0.3 GB (typical)
├── Thread Stacks:        ~0.2 GB (200+ threads × 1MB)
├── Native/JNI:           ~0.3 GB
├── Code Cache:           ~0.2 GB
└── Total:               ~21.0 GB  ← EXCEEDS 20GB SOFT LIMIT
```

**With `-XX:+AlwaysPreTouch`, all 16GB heap + 4GB direct = 20GB is allocated at startup**, leaving ZERO headroom for metaspace, thread stacks, native memory, and OS buffers.

### Cascading Failure Sequence

```
1. Prometheus scrapes /metrics endpoint (every 15-30s)
   ↓
2. PrometheusMeterRegistry.scrape() runs ON EVENT LOOP (CPU-bound, 2-10s)
   ↓
3. Response writing needs DirectByteBuffer allocation
   ↓
4. 4GB direct memory is fragmented/exhausted
   ↓
5. JVM calls Bits.reserveMemory() → waits for Reference processing (GC)
   ↓
6. EVENT LOOP THREAD BLOCKS for 11+ seconds waiting for direct memory
   ↓
7. ALL HTTP requests stall (single event loop blocked)
   ↓
8. Health checks fail → container restarts
   ↓
9. Or: OOM Killer terminates process
```

---

## Issue Breakdown by Root Cause

### Issue #1: Prometheus Scraping on Event Loop (CRITICAL)

**Evidence:**
| Metric | Count |
|--------|-------|
| Thread blocked during `PrometheusBackendRegistry.handleRequest` | 1,134 |
| Thread blocked during `Http1xServerResponse.end` | 1,857 |
| Thread blocked during `writeToChannel` | 1,838 |
| Thread blocked during `TextFormat.write` | 456 |
| Thread blocked during `Double.toString` in metrics | 99 |

**Root Cause:**

The Vert.x Micrometer integration (`VertxPrometheusOptions.setStartEmbeddedServer(true)`) handles `/metrics` requests **on the event loop thread**. The `scrape()` operation is CPU-intensive:

1. **Iteration**: Loops through all registered meters (thousands with high-cardinality labels)
2. **Formatting**: Calls `TextFormat.writeEscapedLabelValue()` for each label value
3. **Number conversion**: `Double.toString()` for each metric value
4. **String building**: Large string concatenation for the response

**Why 4GB direct memory doesn't help:**

The issue is NOT the direct memory size. The issue is:
1. Scraping takes 2-10 seconds of CPU time ON the event loop
2. When finally writing the response, Netty tries to allocate DirectByteBuffer
3. If direct memory is fragmented, `Bits.reserveMemory()` triggers GC and waits
4. The event loop thread blocks during this wait

**Stack Trace:**
```java
io.vertx.core.VertxException: Thread blocked
    at java.base/java.lang.ref.Reference.waitForReferenceProcessing(Unknown Source)
    at java.base/java.nio.Bits.reserveMemory(Unknown Source)
    at java.base/java.nio.DirectByteBuffer.<init>(Unknown Source)
    at io.netty.buffer.PoolArena$DirectArena.allocateDirect(PoolArena.java:718)
    ...
    at io.vertx.core.http.impl.Http1xServerResponse.end(Http1xServerResponse.java:447)
    at io.vertx.micrometer.backends.PrometheusBackendRegistry.handleRequest(PrometheusBackendRegistry.java:102)
```

---

### Issue #2: Direct Memory Fragmentation Under Pressure

**Evidence:**
| Metric | Count |
|--------|-------|
| Thread blocked at `reserveMemory` | 595 |
| Thread blocked at `waitForReferenceProcessing` | 595 |
| OOM or near-OOM events | 222 |
| Maximum blocking time | 1,382,331 ms (23 minutes!) |

**Root Cause:**

With the memory budget already exceeded:
- Direct memory (4GB) gets fragmented over time
- Large allocations (like Prometheus response buffers) fail to find contiguous space
- JVM triggers GC to free phantom-referenced DirectByteBuffers
- `Bits.reserveMemory()` calls `Reference.waitForReferenceProcessing()` which BLOCKS
- If GC is already under pressure (heap near 16GB), this wait is very long

**The 23-minute block explained:**

When the JVM is under severe memory pressure:
1. G1GC is struggling to collect (heap at 16GB, frequent full GCs)
2. Reference processing is backlogged
3. `Bits.reserveMemory()` retries 9 times with exponential backoff
4. Each retry calls `System.gc()` and waits for Reference processing
5. Total wait = `MAX_SLEEPS * sum(2^i for i in 0..8)` = up to 23 minutes

---

### Issue #3: Redis/Lettuce Lock Contention (SECONDARY)

**Evidence:**
| Metric | Count |
|--------|-------|
| Thread blocked at `SharedLock.incrementWriters` | 136 |
| Total Lettuce-related blocking | 4,085 (including downstream effects) |

**Root Cause:**

Lettuce's `DefaultEndpoint.write()` uses a `SharedLock` for write serialization:

```java
// Inside Lettuce DefaultEndpoint.write()
sharedLock.incrementWriters();  // THIS CAN BLOCK
try {
    // ... send command to Redis
} finally {
    sharedLock.decrementWriters();
}
```

When called from the event loop during `GroupNegativeCache.isNotFoundAsync()`:
1. If another Redis command is in-flight, the lock acquisition blocks
2. The event loop thread is blocked waiting for the lock
3. All requests stall

**Note:** This is a **secondary issue**. With only 136 occurrences vs 1,857 for Prometheus, it's less impactful but still needs fixing.

---

## Solutions (Prioritized)

### Priority 0: Memory Tuning (Immediate)

**Problem:** 16GB heap + 4GB direct = 20GB = 100% of soft limit. No headroom.

**Solution:** Reduce heap to leave room for JVM overhead.

```bash
# Current (problematic)
-Xms16g -Xmx16g -XX:MaxDirectMemorySize=4g
# Total: 20GB = soft limit, 0 headroom

# Recommended Option A: Reduce heap
-Xms14g -Xmx14g -XX:MaxDirectMemorySize=4g
# Total: 18GB, 2GB headroom for metaspace/native/threads

# Recommended Option B: Reduce both
-Xms14g -Xmx14g -XX:MaxDirectMemorySize=2g
# Total: 16GB, 4GB headroom (most conservative)

# Recommended Option C: Increase container limit
# Request 24GB soft limit, 28GB hard limit
# Keep current JVM settings
```

**Why this helps:**
- Leaves room for metaspace (~300MB), thread stacks (~200MB), code cache (~200MB)
- Reduces GC pressure, making Reference processing faster
- Prevents OOM killer from terminating the process

---

### Priority 1: Move Prometheus Scraping Off Event Loop

**Problem:** `scrape()` is CPU-intensive and runs on event loop, blocking all requests.

**Solution:** Deploy custom async metrics verticle as worker.

**File:** `artipie-main/src/main/java/com/artipie/metrics/AsyncMetricsVerticle.java`

Key features:
1. Runs on **worker thread pool**, not event loop
2. **Caches metrics for 5-10 seconds** to reduce scrape overhead
3. **Request deduplication**: Only one scrape in flight at a time
4. **Heap-based response buffer**: Avoids DirectByteBuffer allocation on event loop
5. **Graceful degradation**: Returns stale cache on errors

**Deployment:**
```java
// In VertxMain, instead of using embedded Prometheus server:
DeploymentOptions metricsOpts = new DeploymentOptions()
    .setWorker(true)
    .setWorkerPoolName("metrics-scraper")
    .setWorkerPoolSize(2);

vertx.deployVerticle(
    new AsyncMetricsVerticle(registry, metricsPort, "/metrics", 10000L),
    metricsOpts
);
```

**Configuration change:** Update Prometheus scrape config:
```yaml
scrape_configs:
  - job_name: 'artipie'
    scrape_interval: 30s   # Increase from 15s
    scrape_timeout: 25s    # Allow time for worker execution
```

---

### Priority 2: Use Heap Buffers for Large Responses

**Problem:** Netty allocates DirectByteBuffer for HTTP responses, which can block on GC.

**Solution:** Configure Vert.x to use heap buffers for metrics responses.

**JVM argument additions:**
```bash
# Prefer heap buffers when direct memory is under pressure
-Dio.netty.noPreferDirect=true

# Reduce pooled direct memory chunk size
-Dio.netty.allocator.maxOrder=8

# Disable thread-local buffer caching (reduces fragmentation)
-Dio.netty.recycler.maxCapacityPerThread=0
-Dio.netty.allocator.useCacheForAllThreads=false
```

**Code change for metrics response:**

```java
// In AsyncMetricsVerticle, force heap buffer for response
Buffer responseBuffer = Buffer.buffer(metricsString.getBytes(StandardCharsets.UTF_8));
request.response()
    .putHeader("Content-Type", PROMETHEUS_CONTENT_TYPE)
    .end(responseBuffer);
```

---

### Priority 3: Fix Redis Operations (Dedicated Executor)

**Problem:** Lettuce SharedLock can block event loop during cache operations.

**Solution:** Execute all Redis operations on dedicated thread pool.

**Code change in `GroupNegativeCache.java`:**

```java
private static final ExecutorService REDIS_EXECUTOR =
    Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "artipie-redis-cache-" + counter.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

public CompletableFuture<Boolean> isNotFoundAsync(String memberName, Key path) {
    // L1 check (in-memory, safe on event loop)
    Boolean l1Result = this.l1Cache.getIfPresent(key);
    if (l1Result != null) {
        return CompletableFuture.completedFuture(true);
    }
    
    // L2 check (Redis, run on dedicated executor)
    return CompletableFuture.supplyAsync(() -> {
        try {
            byte[] bytes = this.l2.get(key)
                .toCompletableFuture()
                .get(50, TimeUnit.MILLISECONDS);
            // ... handle result
        } catch (Exception e) {
            return false; // Fail fast, don't block
        }
    }, REDIS_EXECUTOR);
}
```

---

### Priority 4: Reduce Metrics Cardinality

**Problem:** High-cardinality labels (full package paths) increase scrape time exponentially.

**Evidence from logs:** Many unique package names like:
- `@graphql-codegen/typescript-operations`
- `@retail/backoffice-interaction-notes`
- `@nx/nx-darwin-x64`

**Solution:** Normalize package names in metrics labels.

```java
// Instead of full package name:
.tag("package", "@graphql-codegen/typescript-operations")

// Use normalized group:
.tag("package_scope", "graphql-codegen")

// Or hash for uniqueness without cardinality:
.tag("package_hash", hashFirst8Chars(packageName))
```

---

## Complete Recommended JVM Configuration

```bash
# Memory - leave 2GB headroom
-Xms14g -Xmx14g
-XX:MaxDirectMemorySize=4g

# GC - keep current settings, they're good
-XX:+UseG1GC
-XX:G1ReservePercent=10
-XX:MaxGCPauseMillis=200
-XX:ParallelGCThreads=8
-XX:ConcGCThreads=2
-XX:InitiatingHeapOccupancyPercent=45
-XX:+UseStringDeduplication
-XX:+ParallelRefProcEnabled

# Direct memory / Netty tuning
-Dio.netty.noPreferDirect=true
-Dio.netty.allocator.maxOrder=8
-Dio.netty.recycler.maxCapacityPerThread=0
-Dio.netty.allocator.useCacheForAllThreads=false
-Dio.netty.leakDetection.level=simple

# Container support
-XX:+UseContainerSupport
-XX:+AlwaysPreTouch

# Crash handling
-XX:+ExitOnOutOfMemoryError
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/artipie/logs/heapdump.hprof

# GC logging
-Xlog:gc*:file=/var/artipie/logs/gc.log:time,uptime:filecount=5,filesize=50M

# Temp directories
-Djava.io.tmpdir=/var/artipie/cache/tmp
-Dvertx.cacheDirBase=/var/artipie/cache/tmp

# Vert.x tuning
-Dvertx.max.worker.execute.time=120000000000
-Dartipie.filesystem.io.threads=14
```

---

## Implementation Roadmap

| Phase | Action | Effort | Impact |
|-------|--------|--------|--------|
| **Immediate** | Reduce heap to 14GB | Config change | Prevents OOM |
| **Immediate** | Increase Prometheus scrape interval to 30s | Config change | Reduces blocking frequency |
| **Week 1** | Deploy AsyncMetricsVerticle | Code + deploy | Eliminates event loop blocking |
| **Week 1** | Add Netty heap buffer preference | JVM args | Reduces direct memory pressure |
| **Week 2** | Fix GroupNegativeCache Redis executor | Code + deploy | Eliminates Redis lock blocking |
| **Week 3** | Reduce metrics cardinality | Code review | Reduces scrape time |

---

## Monitoring & Alerting

Add these alerts to detect regression:

```yaml
groups:
  - name: artipie-performance
    rules:
      - alert: ArtipieEventLoopBlocked
        expr: rate(vertx_eventloop_blocked_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Event loop blocked - all requests stalling"
          
      - alert: ArtipieDirectMemoryHigh
        expr: jvm_buffer_memory_used_bytes{id="direct"} / 4294967296 > 0.85
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Direct memory above 85% (3.4GB of 4GB)"
          
      - alert: ArtipieHeapPressure
        expr: jvm_memory_used_bytes{area="heap"} / 14495514624 > 0.90
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Heap usage above 90% (12.6GB of 14GB)"

      - alert: ArtipieMetricsScrapeSlown
        expr: histogram_quantile(0.99, rate(prometheus_target_scrape_duration_seconds_bucket{job="artipie"}[5m])) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Metrics scrape taking >5s at p99"
```

---

## Appendix: Log Analysis Commands

```bash
# Count thread blocking events
grep -c "Thread blocked" logs.csv

# Find blocking during metrics response
grep "Thread blocked" logs.csv | grep -c "PrometheusBackendRegistry"

# Find blocking during HTTP write
grep "Thread blocked" logs.csv | grep -c "Http1xServerResponse.end"

# Find direct memory pressure
grep "Thread blocked" logs.csv | grep -c "reserveMemory"

# Find Redis lock contention
grep "Thread blocked" logs.csv | grep -c "SharedLock"

# Find OOM events
grep -i "outofmemory\|memory.*exhausted" logs.csv | wc -l

# Max blocking duration
grep "has been blocked for" logs.csv | \
  sed -E 's/.*blocked for ([0-9]+) ms.*/\1/' | \
  sort -rn | head -10
```

---

## References

- [Vert.x Golden Rule](https://vertx.io/docs/vertx-core/java/#golden_rule): Don't block the event loop
- [JVM Direct Memory](https://docs.oracle.com/en/java/javase/21/gctuning/other-considerations.html#GUID-8A95E44D-E1E2-4AEA-BED2-BFBC53D25C56)
- [Netty Buffer Management](https://netty.io/wiki/reference-counted-objects.html)
- [G1GC Tuning](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html)
- [Lettuce Connection Pooling](https://lettuce.io/core/release/reference/#connection-pooling)