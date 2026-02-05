# Proxy Cache Debugging Session

## Problem Statement
- Docker pull times out with 502 Bad Gateway
- "Slow member response" warnings flooding logs (up to 79 SECONDS per blob!)
- Performance degraded severely after BackpressuredFileTeeContent implementation

## Phase 1: Root Cause Investigation ✅ COMPLETED

### 1.1 Error Analysis
**Symptoms:**
- `502 Bad Gateway` from nginx (upstream timeout)
- `Slow member response` warnings in artipie logs
- Event durations showing BLOCKING:
  - 17.3s, 26.8s, 44.4s, 54.5s, **79 seconds** for blobs!

**Key Error:**
```
failed to copy: httpReadSeeker: failed open: unexpected status from GET request to
http://localhost:8081/v2/test_prefix/docker_group/rachelos/we-mp-rss/blobs/sha256:71c5dff38128...
: 502 Bad Gateway
```

### 1.2 Recent Changes Analysis
**Files I Modified (PROBLEMATIC):**
- `BackpressuredFileTeeContent.java` - NEW: disk-based tee streaming ❌ BLOCKS!
- `TeeBlob.java` - NEW: Docker blob wrapper
- `TeeNpmAsset.java` - NEW: NPM asset wrapper
- `CacheLayers.java` - Modified to use TeeBlob ❌ MAJOR REGRESSION!
- Various CachedProxySlice files across adapters

**ORIGINAL WORKING CODE (from git show 90152301):**
- CacheLayers was SIMPLE: just fetch from origin, return blob
- NO stream-through caching in Docker adapter
- NO TeeBlob complexity
- Maven/etc used async cache.put() AFTER response sent

### 1.3 ROOT CAUSE IDENTIFIED ✅

**PRIMARY ISSUE: Blocking I/O on Vert.x Event Loop**

The `BackpressuredFileTeeContent` implementation has multiple CRITICAL flaws:

1. **FileChannel.write() is BLOCKING** - called from drainWriteQueue()
   ```java
   this.channel.write(buf); // BLOCKING OPERATION!
   ```

2. **Thread.sleep(10) in finalizeCache()** - NEVER sleep on async paths!
   ```java
   while (this.pendingCount.get() > 0) {
       Thread.sleep(10); // BLOCKING!
   }
   ```

3. **Synchronized blocks holding locks during I/O**
   ```java
   synchronized (this.fileLock) {
       this.channel.write(buf); // Holding lock during blocking I/O!
   }
   ```

4. **IO_EXECUTOR thread pool exhaustion**
   - All proxy requests competing for limited threads
   - Queue builds up, blocking the entire system

5. **TeeBlob adds overhead to EVERY Docker blob request**
   - Even cache hits now go through complex path
   - `cacheStorage.isPresent()` check causes mount fallback

### 1.4 ORIGINAL ARCHITECTURE (working)

The ORIGINAL Docker proxy was simple:
```
Client Request → CacheLayers.get(digest)
                    ↓
              cache.get() → HIT? Return cached
                    ↓ MISS
              origin.get() → Return origin blob directly
                    ↓ (async, no blocking)
              Background save to cache (doesn't block response)
```

**What I BROKE:**
```
Client Request → CacheLayers.get(digest)
                    ↓
              cache.get() → HIT? Return cached
                    ↓ MISS
              origin.get() → TeeBlob wraps blob
                    ↓
              BackpressuredFileTeeContent ← BLOCKS EVENT LOOP!
                    ↓
              Timeout after 79+ seconds
```

## Phase 2: Solution Design

### DECISION: REVERT TO WORKING ARCHITECTURE

The stream-through caching approach is fundamentally incompatible with:
- Vert.x non-blocking event loop model
- Reactive streams backpressure requirements
- High-throughput proxy requirements (1000 req/s)

**CORRECT APPROACH: Store-then-Serve OR Background Cache**

Options:
1. **Revert to original** - Just return origin blob, cache in background
2. **Store-then-serve** - Download complete, then serve (adds latency but works)
3. **Vert.x AsyncFile** - Use proper non-blocking file I/O (complex)

### RECOMMENDED: Revert + Background Caching

For proxy caching at scale, the pattern should be:
1. Check cache → HIT: serve from cache
2. MISS: Fetch from upstream, return to client immediately
3. Save to cache asynchronously (don't block client)
4. Next request hits cache

This is how Nexus/JFrog work and it's proven at scale.

## Phase 3: Implementation Plan

1. **REMOVE** BackpressuredFileTeeContent (broken)
2. **REMOVE** TeeBlob, TeeNpmAsset wrappers
3. **REVERT** CacheLayers to original simple implementation
4. **REVERT** all CachedProxySlice files to use async save
5. **TEST** Docker pull works without timeout
6. **VERIFY** all tests pass

## Files to Revert/Remove

### DELETE:
- `artipie-core/src/main/java/com/artipie/http/misc/BackpressuredFileTeeContent.java`
- `docker-adapter/src/main/java/com/artipie/docker/cache/TeeBlob.java`
- `npm-adapter/src/main/java/com/artipie/npm/proxy/model/TeeNpmAsset.java`
- Related test files

### REVERT:
- `docker-adapter/src/main/java/com/artipie/docker/cache/CacheLayers.java`
- `maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java`
- `gradle-adapter/src/main/java/com/artipie/gradle/http/CachedProxySlice.java`
- `go-adapter/src/main/java/com/artipie/http/CachedProxySlice.java`
- `pypi-adapter/src/main/java/com/artipie/pypi/http/CachedPyProxySlice.java`
- `files-adapter/src/main/java/com/artipie/files/FileProxySlice.java`
- `npm-adapter/src/main/java/com/artipie/npm/proxy/NpmProxy.java`
