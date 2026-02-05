# HTTP Connection Leak Patterns Review

## Comprehensive Analysis of Jetty + Vert.x HTTP Paths

This document provides an in-depth review of all HTTP paths for common leak patterns including:
- Unconsumed response bodies
- Error paths without cleanup
- Non-cancelled futures
- Non-completed CompletableFutures
- Resource lifecycle issues

---

## 1. JettyClientSlice (Jetty HTTP Client)

### File: `http-client/src/main/java/com/artipie/http/client/jetty/JettyClientSlice.java`

### 1.1 Request Body Streaming (Lines 91-109)

```java
final AsyncRequestContent async = new AsyncRequestContent();
Flowable.fromPublisher(body)
    .doOnError(async::fail)           // ✅ FIXED: Propagates errors to Jetty
    .doOnCancel(
        () -> async.fail(new CancellationException("Request body cancelled"))
    )                                  // ✅ FIXED: Handles cancellation
    .doFinally(async::close)          // ✅ Ensures close on all paths
    .subscribe(...)
```

**Status: ✅ SAFE**
- Error propagation via `async::fail`
- Cancellation handling
- `doFinally` ensures `async.close()` is always called

### 1.2 Response Content Reading - Demander (Lines 244-328)

```java
final Content.Chunk chunk = this.source.read();
// ... processing ...
final ByteBuffer stored;
try {
    stored = JettyClientSlice.copyChunk(chunk);  // ✅ Copies data immediately
} finally {
    chunk.release();                              // ✅ Always releases chunk
}
```

**Status: ✅ SAFE**
- Chunks are copied and released immediately in `finally` block
- No `retain()` without matching `release()`
- Timeout protection (30 seconds)
- Max iterations limit (10,000)

### 1.3 Response Future Completion (Lines 131-166)

```java
request.send(result -> {
    if (result.getFailure() == null) {
        res.complete(...);                        // ✅ Success path completes
    } else {
        res.completeExceptionally(...);           // ✅ Failure path completes
    }
});
```

**Status: ✅ SAFE**
- Jetty guarantees callback invocation exactly once
- Both success and failure paths complete the future

### 1.4 Potential Issue: Response Body Not Consumed

```java
Flowable<ByteBuffer> content = Flowable.fromIterable(buffers)
    .map(ByteBuffer::asReadOnlyBuffer);
```

**Risk: ⚠️ LOW**
- If downstream never subscribes to `content`, the `buffers` list stays in memory
- However, since chunks are already copied and released, no Jetty buffer leak occurs
- Memory is eventually GC'd when Response object is collected

---

## 2. VertxSliceServer (Vert.x HTTP Server)

### File: `vertx-server/src/main/java/com/artipie/vertx/VertxSliceServer.java`

### 2.1 Request Body Handling (Lines 268-301)

```java
if (req.headers().contains("Content-Length") && !"0".equals(req.getHeader("Content-Length"))) {
    req.bodyHandler(body -> {
        this.serveWithBody(req, body).whenComplete((result, throwable) -> {
            // ... error handling ...
            if (!req.response().ended()) {
                VertxSliceServer.sendError(req.response(), throwable);  // ✅ Always ends response
            }
        });
    });
} else {
    this.serve(req, null).whenComplete(...);  // ✅ Same pattern
}
```

**Status: ✅ SAFE**
- Request body is fully buffered before processing
- Error handling always ends the response
- No body leak possible

### 2.2 Request Timeout Handling (Lines 558-615)

```java
final long timerId = this.vertx.setTimer(
    this.requestTimeout.toMillis(),
    ignored -> {
        if (guarded.completeExceptionally(timeout)) {
            delegate.cancel(true);              // ✅ Cancels upstream future
        }
    }
);
delegate.whenComplete((resp, error) -> {
    this.vertx.cancelTimer(timerId);           // ✅ Always cancels timer
    // ... completion logic ...
});
```

**Status: ✅ SAFE**
- Timer is always cancelled on completion
- Upstream future is cancelled on timeout
- Returns 503 response on timeout

### 2.3 ResponseTerminator (Lines 889-966)

```java
private static final class ResponseTerminator {
    private final AtomicBoolean finished = new AtomicBoolean(false);

    void end() {
        if (this.finished.compareAndSet(false, true)) {
            this.response.end();
            this.promise.complete(null);
        }
    }

    void fail(final Throwable error) {
        if (this.finished.compareAndSet(false, true)) {
            // CRITICAL: Must end response even on error
            if (!this.response.headWritten()) {
                this.response.setStatusCode(500);
                this.response.end(errorMsg);
            } else {
                this.response.end();
            }
            this.promise.completeExceptionally(error);
        }
    }
}
```

**Status: ✅ SAFE**
- AtomicBoolean prevents double-completion
- Response is ALWAYS ended, even on error
- Promise is always completed (success or exception)

### 2.4 Response Body Streaming (Lines 658-745)

```java
final Flowable<Buffer> vpb = Flowable.fromPublisher(body).map(VertxSliceServer::mapBuffer);

// Content-Length path
vpb.subscribe(
    buffer -> response.write(buffer),
    error -> terminator.fail(error),           // ✅ Error terminates response
    () -> terminator.end()                     // ✅ Completion terminates response
);

// Chunked path
response.endHandler(ignored -> terminator.completeWithoutEnding());
vpb.doOnError(terminator::fail)
   .subscribe(response.toSubscriber());
```

**Status: ✅ SAFE**
- All three Flowable callbacks (onNext, onError, onComplete) are handled
- ResponseTerminator ensures response is always ended
- Chunked responses use Vert.x's built-in subscriber

---

## 3. GroupSlice (Group Repository)

### File: `artipie-main/src/main/java/com/artipie/group/GroupSlice.java`

### 3.1 Parallel Member Queries (Lines 185-214)

```java
return body.asBytesFuture().thenCompose(requestBytes -> {
    // ... parallel queries ...
    for (MemberSlice member : this.members) {
        queryMember(member, line, headers, requestBytes)
            .orTimeout(this.timeout.getSeconds(), TimeUnit.SECONDS)
            .whenComplete((resp, err) -> {
                if (err != null) {
                    handleMemberFailure(...);
                } else {
                    handleMemberResponse(...);
                }
            });
    }
    return result;
});
```

**Status: ✅ SAFE**
- Request body consumed once before parallel queries
- Timeout on each member query
- All responses handled (success or failure)

### 3.2 Winner/Loser Body Handling (Lines 263-346)

```java
// Winner path
if (completed.compareAndSet(false, true)) {
    result.complete(resp);                     // ✅ Winner's body forwarded
} else {
    drainBody(member.name(), resp.body());     // ✅ Loser's body drained
}

// Error path
drainBody(member.name(), resp.body());         // ✅ Error body drained
```

**Status: ✅ SAFE**
- Winner's body is forwarded to client
- Loser's body is explicitly drained via `drainBody()`
- Error responses are drained

### 3.3 drainBody Implementation (Lines 379-393)

```java
private void drainBody(final String memberName, final Content body) {
    body.asBytesFuture().whenComplete((bytes, err) -> {
        if (err != null) {
            EcsLogger.warn(...)  // Log but don't fail
        }
    });
}
```

**Status: ✅ SAFE (with Jetty fix)**
- `asBytesFuture()` consumes the entire body
- Since JettyClientSlice now copies chunks and releases immediately, this works correctly
- Error is logged but doesn't propagate (intentional)

---

## 4. CachedProxySlice (Maven Proxy)

### File: `maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java`

### 4.1 Upstream Error Handling (Lines 293-359)

```java
return this.client.response(line, Headers.EMPTY, Content.EMPTY)
    .thenCompose((resp) -> {
        if (!resp.status().success()) {
            if (resp.status().code() >= 500) {
                // Consume body to prevent Vert.x request leak
                return resp.body().asBytesFuture()
                    .handle((bytes, err) -> ResponseBuilder.notFound().build());
            }
            // 404 handling
            return resp.body().asBytesFuture()
                .thenApply(bytes -> {
                    this.negativeCache.cacheNotFound(key);
                    return ResponseBuilder.notFound().build();
                });
        }
        // Success path - body forwarded
        return CompletableFuture.completedFuture(
            ResponseBuilder.ok().headers(resp.headers()).body(resp.body()).build()
        );
    })
    .exceptionally(error -> {
        throw new CompletionException(error);  // ✅ Exception propagated
    });
```

**Status: ✅ SAFE**
- 5xx errors: body consumed via `asBytesFuture()`
- 404 errors: body consumed before caching
- Success: body forwarded to client
- Exceptions propagated

### 4.2 Request Deduplication (Lines 391-405)

```java
return this.inFlight.computeIfAbsent(key, k ->
    this.client.response(...)
        .thenCompose(...)
        .exceptionally(...)
        .whenComplete((result, error) -> this.inFlight.remove(k))  // ✅ Always removes
);
```

**Status: ✅ SAFE**
- `whenComplete` ensures key is removed from `inFlight` map
- Prevents memory leak from accumulated futures

### 4.3 Root Path Handling (Lines 738-755)

```java
private CompletableFuture<Response> handleRootPath(final RequestLine line) {
    return this.client.response(line, Headers.EMPTY, Content.EMPTY)
        .thenCompose(resp -> {
            if (resp.status().success()) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().headers(resp.headers()).body(resp.body()).build()
                );
            }
            // Consume body to prevent potential leak
            return resp.body().asBytesFuture()
                .thenApply(ignored -> ResponseBuilder.notFound().build());
        });
}
```

**Status: ✅ FIXED**
- On non-success, body is explicitly consumed via `asBytesFuture()`
- Success path forwards body to client
- Changed from `thenApply` to `thenCompose` for proper async handling

---

## 5. Summary of Leak Patterns

### ✅ Fixed/Safe Patterns

| Component | Pattern | Status |
|-----------|---------|--------|
| JettyClientSlice | Chunk retain/release | ✅ Fixed - copy & release immediately |
| JettyClientSlice | Request body errors | ✅ Fixed - `async::fail` propagation |
| JettyClientSlice | Response future completion | ✅ Safe - Jetty guarantees callback |
| VertxSliceServer | Request body buffering | ✅ Safe - fully buffered before processing |
| VertxSliceServer | Request timeout | ✅ Safe - timer cancelled, upstream cancelled |
| VertxSliceServer | ResponseTerminator | ✅ Safe - atomic, always ends response |
| VertxSliceServer | Response body streaming | ✅ Safe - all Flowable callbacks handled |
| GroupSlice | Parallel queries | ✅ Safe - timeout, all responses handled |
| GroupSlice | drainBody | ✅ Safe - works with fixed JettyClientSlice |
| CachedProxySlice | Error body consumption | ✅ Safe - explicit `asBytesFuture()` |
| CachedProxySlice | Request deduplication | ✅ Safe - `whenComplete` cleanup |
| CachedProxySlice | Root path 404 body | ✅ Fixed - explicit `asBytesFuture()` |

### ⚠️ Minor Issues (Non-Critical)

| Component | Pattern | Risk | Mitigation |
|-----------|---------|------|------------|
| JettyClientSlice | Unsubscribed response body | Low | Buffers are copies, GC handles |

> **Note:** The CachedProxySlice root path 404 body issue was fixed during this review.

### 🔧 Configuration Recommendations

| Setting | Old Default | New Default | Reason |
|---------|-------------|-------------|--------|
| `idleTimeout` | 0 (infinite) | 30,000ms | Prevents connection accumulation |
| `connectionAcquireTimeout` | 120,000ms | 30,000ms | Fail fast under back-pressure |
| `maxConnectionsPerDestination` | 512 | 64 | Balanced resource usage |
| `maxRequestsQueuedPerDestination` | 2048 | 256 | Prevents unbounded queuing |

---

## 6. Test Coverage

### New Tests Added

| Test Class | Tests | Coverage |
|------------|-------|----------|
| `JettyClientSliceChunkLifecycleTest` | 8 | Chunk retain/release lifecycle |
| `JettyClientSliceRequestBodyTest` | 8 | Request body error handling |
| `JettyClientSliceLeakTest` | 3 | Buffer pool leak detection |
| `ProxySliceLeakRegressionTest` | 6 | Full proxy chain leak testing |
| `VertxSliceServerRobustnessTest` | 12 | ResponseTerminator, timeouts, concurrency |
| `HttpClientSettingsTest` | 10 | Configuration defaults |

**Total: 47 new tests**

---

## 7. Conclusion

The Jetty + Vert.x HTTP stack is **leak-safe** after the fixes implemented in Phases 1-5:

1. **JettyClientSlice** now correctly manages `Content.Chunk` lifecycle by copying data and releasing immediately
2. **VertxSliceServer** has robust response termination via `ResponseTerminator` with atomic completion
3. **GroupSlice** properly drains all response bodies (winners and losers)
4. **CachedProxySlice** explicitly consumes error response bodies
5. **HttpClientSettings** has production-tuned defaults to prevent pseudo-leaks

The remaining minor issues (unsubscribed response bodies) are non-critical because:
- JettyClientSlice copies chunk data immediately and releases Jetty buffers
- The copied ByteBuffers are regular Java objects subject to GC
- No Jetty buffer pool resources are held
