# NPM Proxy Request Deduplication Analysis

## Executive Summary

This document analyzes the current in-memory buffering approach for NPM proxy request deduplication, evaluates its effectiveness in high-load environments (1000+ req/s), and proposes alternative strategies.

**Current Issue:** The `CachedNpmProxySlice` buffers entire response bodies in memory to enable request deduplication (thundering herd prevention). This causes:
- `NPM proxy: response exceeded buffer limit` warnings for packages > 5MB
- Memory pressure from buffering large packages (typescript ~30MB, aws-sdk ~20MB)
- Potential OOM in high-concurrency scenarios

---

## 1. Current Implementation Analysis

### How It Works

```
┌─────────────────────────────────────────────────────────────────┐
│                    CachedNpmProxySlice                          │
├─────────────────────────────────────────────────────────────────┤
│  inFlight: ConcurrentHashMap<Key, CompletableFuture<BufferedResponse>>  │
│                                                                 │
│  Request 1 (lodash) ──┐                                         │
│  Request 2 (lodash) ──┼──> Check inFlight map                   │
│  Request 3 (lodash) ──┘                                         │
│                                                                 │
│  If key exists: Wait for existing future (deduplication)        │
│  If key absent: Create new future, fetch upstream, buffer body  │
│                                                                 │
│  When complete: All waiters get copy of BufferedResponse        │
└─────────────────────────────────────────────────────────────────┘
```

### Code Flow

1. **Request arrives** → Check `inFlight` map for existing request
2. **If found** → Join existing future, wait for buffered response
3. **If not found** → Add to `inFlight`, fetch from upstream
4. **On response** → Buffer entire body into `byte[]`
5. **Complete future** → All waiters receive `BufferedResponse.toFreshResponse()`
6. **Cleanup** → Remove from `inFlight` map

### Current Limits

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `MAX_BUFFER_SIZE` | 5 MB | Skip buffering for known-large responses |
| Content-Length check | Pre-read | Skip buffering if Content-Length > 5MB |
| Post-read check | After buffering | Log warning if body > 5MB (already buffered) |

---

## 2. Real-World Scenarios at 1000 req/s

### Scenario A: Cold Cache Stampede (npm install on new project)

```
Time 0ms:   100 builds start simultaneously
            All request "lodash" (no cache)
            
Without deduplication:
  - 100 requests to npmjs.org
  - 100 × 500KB = 50MB network traffic
  - npmjs.org may rate-limit

With deduplication:
  - 1 request to npmjs.org
  - 1 × 500KB buffered in memory
  - 99 requests wait ~50ms for buffer
  - Memory: 500KB for ~50ms
```

**Verdict:** Deduplication helps significantly (100x reduction in upstream requests)

### Scenario B: Large Package Stampede (typescript)

```
Time 0ms:   50 builds request "typescript" simultaneously
            Package metadata: ~30MB

Without deduplication:
  - 50 requests to npmjs.org
  - 50 × 30MB = 1.5GB network traffic (but streamed, not buffered)
  - Memory: ~0 (streaming)

With current deduplication:
  - 1 request to npmjs.org
  - 30MB buffered in memory (exceeds 5MB limit)
  - Warning logged, but still buffered!
  - 49 requests wait for buffer
  - Memory: 30MB for ~500ms
  
With ideal deduplication:
  - 1 request to npmjs.org
  - Stream to disk cache
  - 49 requests wait, then stream from disk
  - Memory: ~0
```

**Verdict:** Current implementation is problematic for large packages

### Scenario C: Warm Cache (steady state)

```
Time 0ms:   1000 req/s for various packages
            Most packages already in storage cache

Flow:
  1. Check negative cache (in-memory) → Miss
  2. Check metadata cache (storage) → Hit for 95%
  3. Only 5% go to upstream

With deduplication:
  - 50 req/s to upstream (5% of 1000)
  - Deduplication window: ~50-100ms per request
  - Probability of collision: ~2-5 concurrent for same package
  - Memory: 2-5 × avg_size (~1MB) = 2-5MB

Without deduplication:
  - Same 50 req/s to upstream
  - But some packages hit 5-10x (thundering herd)
  - ~100-150 req/s actual upstream load
```

**Verdict:** Deduplication provides 2-3x reduction in upstream load

### Scenario D: npmjs.org Slowdown

```
Upstream latency: 2000ms (instead of normal 50ms)
Concurrent requests: 100 for "react"

Without deduplication:
  - 100 connections held for 2s each
  - 100 × 2s = 200 connection-seconds
  - Potential connection pool exhaustion

With deduplication:
  - 1 connection for 2s
  - 99 requests waiting on future
  - 1 × 2s = 2 connection-seconds
  - Memory: package_size for 2s
```

**Verdict:** Deduplication is critical during upstream slowdowns

---

## 3. Problems with Current Buffering Approach

### Problem 1: Memory Pressure

```
Peak memory = concurrent_packages × avg_buffered_size

Example:
  - 50 unique packages being fetched concurrently
  - Average size: 2MB (some small, some large)
  - Peak memory: 50 × 2MB = 100MB just for buffering

Worst case (typescript stampede):
  - 10 large packages (typescript, aws-sdk, @types/node, etc.)
  - Average size: 20MB
  - Peak memory: 10 × 20MB = 200MB
```

### Problem 2: Content-Length Missing

npmjs.org sometimes doesn't send Content-Length for package metadata. Current code:
1. Can't check size before buffering
2. Buffers entire response
3. Logs warning AFTER buffering (too late)

### Problem 3: OneTimePublisher Constraint

The buffering exists because `Response.body()` returns a `Content` that can only be consumed once. To share with multiple waiters, we must buffer.

### Problem 4: Blocking Waiters on Large Responses

When a large response is detected (Content-Length > 5MB):
```java
newRequest.complete(new BufferedResponse(
    RsStatus.SERVICE_UNAVAILABLE, Headers.EMPTY, new byte[0]
));
```

Waiters get `503 Service Unavailable` and must retry. This defeats deduplication.

---

## 4. Alternative Deduplication Strategies

### Strategy A: Disk-Based Buffering (Recommended)

```
┌─────────────────────────────────────────────────────────────────┐
│  Request arrives for "typescript"                               │
│                                                                 │
│  1. Check inFlight map                                          │
│  2. If not found: Create temp file, stream response to disk     │
│  3. Complete future with file path                              │
│  4. All waiters stream from same temp file                      │
│  5. Cleanup: Delete temp file after last reader                 │
└─────────────────────────────────────────────────────────────────┘

Memory usage: O(1) regardless of package size
Disk I/O: Sequential write + N sequential reads (fast on SSD)
Latency: +5-10ms for disk I/O
```

**Pros:**
- Handles any package size
- Constant memory usage
- Still provides deduplication

**Cons:**
- Slightly higher latency
- Requires temp disk space
- More complex implementation

### Strategy B: Streaming Multicast (Complex)

```
┌─────────────────────────────────────────────────────────────────┐
│  Use reactive multicast to fan-out single upstream stream       │
│                                                                 │
│  Upstream ──> Multicast ──┬──> Subscriber 1                     │
│                           ├──> Subscriber 2                     │
│                           └──> Subscriber 3                     │
└─────────────────────────────────────────────────────────────────┘

Memory usage: O(chunk_size × subscribers) for backpressure buffer
No disk I/O
```

**Pros:**
- True streaming, minimal memory
- No disk I/O

**Cons:**
- Complex backpressure handling
- Slowest subscriber blocks others
- Difficult to implement correctly

### Strategy C: Storage-Level Deduplication (Simplest)

```
┌─────────────────────────────────────────────────────────────────┐
│  Remove in-flight deduplication entirely                        │
│  Rely on storage cache for deduplication                        │
│                                                                 │
│  Request 1 ──> Cache miss ──> Fetch ──> Save to storage         │
│  Request 2 ──> Cache miss ──> Fetch ──> Save to storage (race)  │
│  Request 3 ──> Cache HIT ──> Serve from storage                 │
└─────────────────────────────────────────────────────────────────┘

Window of duplicate requests: ~50-100ms (upstream latency)
```

**Pros:**
- Simplest implementation (remove code)
- No memory buffering
- Storage handles caching

**Cons:**
- 2-5x more upstream requests during cold cache
- Potential rate limiting from npmjs.org
- Higher network costs

### Strategy D: Hybrid (Small Buffer + Disk Fallback)

```
┌─────────────────────────────────────────────────────────────────┐
│  If Content-Length <= 1MB: Buffer in memory                     │
│  If Content-Length > 1MB or unknown: Stream to disk             │
│  If no Content-Length: Stream to disk (safe default)            │
└─────────────────────────────────────────────────────────────────┘

Memory usage: O(concurrent_small_packages × 1MB)
Disk usage: O(concurrent_large_packages × package_size)
```

**Pros:**
- Best of both worlds
- Fast for small packages (95% of requests)
- Safe for large packages

**Cons:**
- More complex than current implementation
- Requires temp disk management

---

## 5. Quantitative Analysis

### Package Size Distribution (npmjs.org)

| Size Range | % of Packages | Examples |
|------------|---------------|----------|
| < 100 KB | 60% | lodash, express, moment |
| 100 KB - 1 MB | 30% | react, vue, webpack |
| 1 MB - 5 MB | 8% | @angular/core, rxjs |
| 5 MB - 30 MB | 2% | typescript, aws-sdk, @types/node |

### Deduplication Benefit by Load

| Concurrent Requests | Dedup Benefit | Memory Cost (current) |
|---------------------|---------------|----------------------|
| 10 req/s | 1.2x reduction | ~2 MB |
| 100 req/s | 2-3x reduction | ~20 MB |
| 1000 req/s | 5-10x reduction | ~100-200 MB |

### Upstream Rate Limits

npmjs.org doesn't publish official limits, but observed:
- Soft limit: ~100 req/s per IP
- Hard limit: ~500 req/s per IP (429 responses)
- Recommendation: Stay under 50 req/s sustained

---

## 6. Recommendations

### Short-term (Quick Fix)

1. **Increase MAX_BUFFER_SIZE to 50MB** - Reduces warnings but increases memory risk
2. **Skip buffering for unknown Content-Length** - Stream directly, accept duplicate requests

### Medium-term (Recommended)

**Implement Strategy D: Hybrid approach**

```java
private static final int MEMORY_BUFFER_LIMIT = 1 * 1024 * 1024; // 1MB

if (contentLength > 0 && contentLength <= MEMORY_BUFFER_LIMIT) {
    // Small package: buffer in memory (fast)
    return bufferInMemory(response, key, newRequest);
} else {
    // Large or unknown: stream to temp file
    return streamToDisk(response, key, newRequest);
}
```

### Long-term (Best)

**Implement Strategy A: Disk-based buffering for all**

- Consistent behavior regardless of size
- Predictable memory usage
- Scales to any load

---

## 7. Implementation Sketch: Hybrid Approach

```java
private CompletableFuture<Response> fetchWithDedup(
    Response response, Key key, CompletableFuture<BufferedResponse> newRequest
) {
    final long contentLength = getContentLength(response);
    
    if (contentLength > 0 && contentLength <= MEMORY_BUFFER_LIMIT) {
        // Small: buffer in memory
        return response.body().asBytesFuture()
            .thenApply(bytes -> {
                newRequest.complete(new BufferedResponse(response.status(), response.headers(), bytes));
                return newRequest.join().toFreshResponse();
            });
    }
    
    // Large or unknown: stream to temp file
    final Path tempFile = Files.createTempFile("npm-proxy-", ".tmp");
    return streamToFile(response.body(), tempFile)
        .thenApply(size -> {
            newRequest.complete(new FileBufferedResponse(response.status(), response.headers(), tempFile));
            return newRequest.join().toFreshResponse();
        })
        .whenComplete((r, e) -> scheduleCleanup(tempFile, Duration.ofMinutes(5)));
}
```

---

## 8. Conclusion

| Approach | Memory | Complexity | Dedup Benefit | Recommendation |
|----------|--------|------------|---------------|----------------|
| Current (5MB buffer) | High | Low | Good | Problematic for large packages |
| Remove deduplication | Zero | Lowest | None | Risk of upstream rate limiting |
| Disk-based | Zero | Medium | Good | Best for high-load |
| Hybrid | Low | Medium | Good | **Recommended** |
| Streaming multicast | Low | High | Good | Too complex |

**Recommendation:** Implement **Hybrid approach** (Strategy D) with:
- 1MB memory buffer limit for small packages
- Disk streaming for large/unknown packages
- Automatic temp file cleanup

This provides deduplication benefits while eliminating memory pressure from large packages.
