# Vert.x 5 Stream-Through Cache Design

**Date:** 2026-01-26
**Status:** Approved
**Author:** Claude + Human collaboration

## Problem Statement

The previous `BackpressuredFileTeeContent` implementation caused severe performance degradation:
- Blocking I/O on Vert.x event loop (FileChannel.write)
- Thread.sleep() in async paths
- Synchronized blocks during I/O
- 79+ second blob downloads, 502 Bad Gateway timeouts

## Solution Overview

Upgrade to Vert.x 5 and implement proper non-blocking stream-through caching using:
1. **TeeReadStream** - Duplicates data to client AND cache with backpressure
2. **AsyncFile** - Vert.x native non-blocking file I/O
3. **Virtual Thread Verticle** - For cache finalization only (checksum, move)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      Proxy Request Flow                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Client ←─── Pump ←─── TeeReadStream ←─── Upstream Response     │
│                              │                                  │
│                              ↓                                  │
│                           Pump                                  │
│                              ↓                                  │
│                         AsyncFile (temp)                        │
│                              │                                  │
│                              ↓ (on complete)                    │
│                    Virtual Thread Verticle                      │
│                    - Verify checksum                            │
│                    - Move temp → final location                 │
│                    - Update cache index                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### 1. Non-Blocking Backpressure

Vert.x `pause()`/`resume()` are non-blocking signals:
- `pause()` sets internal flag, returns in nanoseconds
- Event loop continues processing other connections
- TCP backpressure naturally propagates to upstream

### 2. TeeReadStream Implementation

```java
public class TeeReadStream<T> implements ReadStream<T> {
    private final ReadStream<T> source;
    private final WriteStream<T> destination1;  // Client response
    private final WriteStream<T> destination2;  // AsyncFile cache

    // Backpressure: pause if EITHER destination full
    // Resume only when BOTH destinations ready

    // Cache detachment: on cache error, continue serving client
    private volatile boolean cacheDetached = false;
}
```

### 3. AsyncFile for Cache Writes

```java
vertx.fileSystem().open(tempPath, new OpenOptions()
    .setWrite(true)
    .setCreate(true))
    .onSuccess(asyncFile -> {
        // asyncFile implements WriteStream<Buffer>
        Pump.pump(teeReadStream, asyncFile).start();
    });
```

### 4. Virtual Thread Finalization

Only used for post-streaming operations (not in hot path):
```java
vertx.deployVerticle(() -> new AbstractVerticle() {
    @Override
    public void start() {
        // Checksum verification
        // Move temp to final location
        // Update cache metadata
        vertx.undeploy(context.deploymentID());
    }
}, new DeploymentOptions()
    .setThreadingModel(ThreadingModel.VIRTUAL_THREAD));
```

### 5. Error Handling

**Principle:** Cache failures NEVER affect client response.

- Cache write fails → detach cache, continue serving client
- Checksum mismatch → delete temp file, log warning
- Disk full → same as write failure
- No orphaned temp files on any failure path

## Shared Implementation

**Core classes in `artipie-core`:**
```
artipie-core/src/main/java/com/artipie/http/misc/
├── TeeReadStream.java
├── CacheStreamHandler.java
└── CacheFinalizationVerticle.java
```

**Adapter integration points:**

| Adapter | Class | Method |
|---------|-------|--------|
| Docker | `CacheLayers.java` | `get(Digest)` |
| Maven | `CachedProxySlice.java` | `fetchAndCache()` |
| Gradle | `CachedProxySlice.java` | Same as Maven |
| Go | `CachedProxySlice.java` | Same as Maven |
| NPM | `NpmProxy.java` | `getAsset()` |
| PyPI | `CachedPyProxySlice.java` | `saveAndRespond()` |
| Files | `FileProxySlice.java` | `streamThroughFetch()` |

## Vert.x 5 Upgrade

**Version changes:**
```xml
<vertx.version>5.0.0</vertx.version>
<maven.compiler.source>21</maven.compiler.source>
<maven.compiler.target>21</maven.compiler.target>
```

**Key migrations:**
1. `setWorker(true)` → `setThreadingModel(ThreadingModel.VIRTUAL_THREAD)`
2. Callback handlers → Future-based API
3. `Vertx.vertx(options)` → `Vertx.builder().with(options).build()`

## Files to Remove

- `BackpressuredFileTeeContent.java` (broken)
- `TeeBlob.java` (uses broken implementation)
- `TeeNpmAsset.java` (uses broken implementation)
- Related test files

## Testing Strategy

| Test Type | Purpose |
|-----------|---------|
| Unit: `TeeReadStreamTest` | Backpressure, detachment, error handling |
| Unit: `CacheStreamHandlerTest` | End-to-end cache streaming |
| Integration: `ProxyCacheIT` | Real HTTP proxy with caching |
| Performance: `ProxyCacheBenchmark` | Latency, throughput validation |

## Performance Acceptance Criteria

- Latency overhead vs direct proxy: **< 10%** (was 77%)
- Throughput: **> 1000 req/s** sustained
- Memory: Bounded regardless of artifact size
- No "Slow member response" warnings under normal load

## References

- [Vert.x Reactive Streams](https://vertx.io/docs/vertx-reactive-streams/java/)
- [Vert.x 5 Migration Guide](https://vertx.io/docs/guides/vertx-5-migration-guide/)
- [What's new in Vert.x 5](https://vertx.io/blog/whats-new-in-vert-x-5/)
- [AsyncFile API](https://vertx.io/docs/apidocs/io/vertx/core/file/AsyncFile.html)
