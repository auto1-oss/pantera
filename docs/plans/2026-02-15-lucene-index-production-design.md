# Lucene Index as Production Group Lookup Engine

**Date:** 2026-02-15
**Status:** Approved
**Version:** 1.20.13

## Problem Statement

The Lucene-based `ArtifactIndex` exists in the codebase but is not wired into production:
- `LuceneArtifactIndex` implementation is complete but never instantiated
- `GroupSlice` has index-first lookup logic but receives `ArtifactIndex.NOP`
- `SearchRest` exposes search endpoints but operates on an empty index
- The existing `GroupNegativeCache` (two-tier L1 Caffeine + L2 Valkey) handles group 404 caching but is a reactive system that only learns from failures

The artifact event pipeline exists (Upload -> RepositoryEvents -> queue -> EventsProcessor -> DbConsumer -> PostgreSQL) but nothing feeds the Lucene index.

## Decision

Replace `GroupNegativeCache` entirely with the Lucene positive index. Remove the cache management REST API (`CacheRest`, `cache.yaml`, `ApiCachePermission`).

### Approach: Positive Index with Warmup Fallback

The index tracks **where artifacts exist** (positive knowledge). During a warmup period after startup, GroupSlice falls back to fan-out on index-miss. Once warmed up (initial storage scan complete), the index is trusted fully.

**Why this over negative cache:**
- Positive knowledge ("member-A has artifact-X") is more useful than negative knowledge ("member-B doesn't have artifact-X")
- Event-driven updates keep the index accurate without TTL-based expiration
- Eliminates Valkey/Redis dependency for group lookups
- Eliminates L1/L2 cache tier complexity
- Single source of truth for artifact location

## Architecture

```
                    artipie.yaml
                    meta:
                      artifact_index:
                        enabled: true
                        directory: /var/artipie/index

              ┌─────────────────┬─────────────────────┐
              │                 │                       │
              v                 v                       v
    YamlSettings        VertxMain              RestApi
    creates             passes index           SearchRest uses
    LuceneArtifactIndex to GroupSlice          index for API
           │                  │
           v                  v
    IndexConsumer       GroupSlice
    (event -> index)    locate(path) -> target query
                        warm? -> trust index
                        cold? -> fan-out fallback
```

## Event Flow (Write Path)

The existing event pipeline is extended with a parallel consumer:

```
Upload/Delete Event
        |
        v
   Event Queue
        |
        +---> DbConsumer -> PostgreSQL  (existing, unchanged)
        |
        +---> IndexConsumer -> LuceneArtifactIndex  (NEW)
              |- INSERT event -> index.index(ArtifactDocument)
              +- DELETE event -> index.remove(repoName, path)
```

`IndexConsumer` implements `Consumer<ArtifactEvent>`. It converts `ArtifactEvent` fields to `ArtifactDocument` and calls `index.index()` or `index.remove()`.

Proxy artifacts flow through the same pipeline: ProxyArtifactEvent -> ProxyPackageProcessor -> main event queue -> IndexConsumer. No changes needed.

## Request Flow (Read Path)

### Before (Negative Cache)

```
Request -> GroupSlice -> for each member:
    negative cache check (L1 -> L2) -> if miss -> query member -> cache 404
```

### After (Lucene Index)

```
Request -> GroupSlice -> index.locate(path)
    |- Index returns [member-A]    -> query only member-A -> return response
    |- Index returns [] AND warm   -> return 404 immediately
    +- Index returns [] AND cold   -> fan-out to all members (warmup mode)
```

When a member returns a successful response during fan-out, GroupSlice also calls `index.index()` to update the index (belt-and-suspenders alongside the event pipeline).

## Initial Storage Scan (Warmup)

On startup, the index is empty. `IndexWarmupService` scans all group member repositories:

1. After `LuceneArtifactIndex` is created, launch async scan
2. For each repository that participates in a group, list all keys in storage
3. For each key, create `ArtifactDocument` and call `index.index()`
4. When scan completes, set `indexWarmedUp = true`
5. During scan, GroupSlice falls back to fan-out on index miss

The warmup runs on a background thread and does not block startup. GroupSlice serves requests immediately using fan-out mode until warmup completes.

## Configuration

```yaml
meta:
  artifact_index:
    enabled: true                     # default: false
    directory: /var/artipie/index     # MMapDirectory path (required if enabled)
    warmup_on_startup: true           # scan repos on startup (default: true)
```

If `enabled: false` or section missing, `ArtifactIndex.NOP` is used (plain fan-out, no index).

## Components Changed

### New Files
- `IndexConsumer.java` - Event consumer that feeds ArtifactEvent into LuceneArtifactIndex
- `IndexWarmupService.java` - Initial storage scan to populate index on startup

### Modified Files
- `Settings.java` - Add `ArtifactIndex artifactIndex()` method
- `YamlSettings.java` - Parse `artifact_index` config, create LuceneArtifactIndex, add IndexConsumer to event pipeline
- `LuceneArtifactIndex.java` - Add `isWarmedUp()`, `setWarmedUp()`, `getStats()` methods
- `VertxMain.java` - Pass index to RepositorySlices, trigger warmup
- `RepositorySlices.java` - Store index, pass to all GroupSlice constructors
- `RestApi.java` - Use real index from settings instead of NOP
- `GroupSlice.java` - Replace negative cache with index.locate(), warmup-aware logic
- `SearchRest.java` - Add /api/v1/search/stats endpoint
- `search.yaml` - Add stats endpoint specification
- `swagger-initializer.js` - Remove cache.yaml reference

### Deleted Files
- `GroupNegativeCache.java` - Replaced by Lucene index
- `CacheRest.java` - Removed (index stats via search API)
- `cache.yaml` - Removed
- `ApiCachePermission.java` - Removed
- `ApiCachePermissionFactory.java` - Removed

### Documentation Updates
- `docs/USER_GUIDE.md` - Add artifact_index configuration, update group repos section
- `docs/DEVELOPER_GUIDE.md` - Add index architecture, event flow, warmup explanation
- `.wiki/Configuration-Metadata.md` - Add index configuration reference
- `.wiki/Rest-api.md` - Document search API, remove cache API references

## GroupSlice.response() Pseudocode

```java
public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
    String path = extractPath(line);

    // Index lookup
    List<String> indexResults = this.artifactIndex.locate(path).join();

    if (!indexResults.isEmpty()) {
        // Index hit: query only the members that have this artifact
        List<MemberSlice> targeted = filterMembers(indexResults);
        return queryMembers(targeted, line, headers, body);
    }

    if (this.artifactIndex.isWarmedUp()) {
        // Index is warm and says nobody has it: 404
        return CompletableFuture.completedFuture(NOT_FOUND);
    }

    // Index is cold (warming up): fall back to fan-out
    return queryAllMembersInParallel(line, headers, body);
}
```

## Testing Strategy

- Unit tests for `IndexConsumer` (event -> index write)
- Unit tests for `IndexWarmupService` (storage scan -> index population)
- Integration test: upload artifact -> event fires -> index updated -> group locate finds it
- Integration test: warmup scan populates index from existing storage
- Existing `GroupSliceTest` updated to use real index instead of negative cache
- `LuceneArtifactIndexTest` already has coverage for index/remove/search/locate

## Migration Notes

- The negative cache Valkey/Redis configuration (`meta.caches.negative`) becomes unused
- Existing group repositories will work in fan-out mode during warmup
- No data migration needed - the index is built from scratch on startup
- If `artifact_index.enabled` is false, groups use plain fan-out (no negative cache, no index)
