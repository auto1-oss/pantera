# Group Metadata Merging Design

## Problem Statement

Currently, non-Maven groups use a "race" strategy where the first successful response wins.
This causes:
1. **Version inconsistency** - Different versions returned depending on which member responds first
2. **Excessive upstream calls** - All members queried even when package exists locally
3. **Slow responses** - Waiting for slowest member when querying all in parallel
4. **Wasted connections** - 10 members × 1000 req/s = 10,000 upstream calls/sec

## Design Goals

- **Enterprise scale**: 1000+ req/s, 10 members per group, 100K+ packages
- **Speed**: Local packages served in <1ms, no unnecessary upstream calls
- **Consistency**: Same versions returned regardless of member response timing
- **Non-blocking**: All operations compatible with Vert.x event loop
- **Configurable**: All TTLs controlled via settings
- **Cooldown-aware**: Respects proxy cooldown, local repos bypass cooldown

## Solution: Index-Based Resolution

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Group Request Flow                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Request: GET /lodash (metadata)                                            │
│                                                                             │
│  Step 1: Check MergedMetadataCache                                          │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │ L1 Caffeine (in-process, <1μs) → L2 Valkey (distributed, ~1ms) │       │
│  │ Key: "npm-group:lodash"                                         │       │
│  │ HIT? → Return immediately                                       │       │
│  └─────────────────────────────────────────────────────────────────┘       │
│                         │ MISS                                              │
│                         ▼                                                   │
│  Step 2: Check PackageLocationIndex                                         │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │ "lodash" → {                                                    │       │
│  │   npm-local:   EXISTS (event-driven, no TTL)                   │       │
│  │   npm-proxy-1: EXISTS (TTL: 15min)                             │       │
│  │   npm-proxy-2: NOT_EXISTS (negative cache TTL: 5min)           │       │
│  │ }                                                               │       │
│  └─────────────────────────────────────────────────────────────────┘       │
│                         │                                                   │
│                         ▼                                                   │
│  Step 3: Fetch from KNOWN locations only (skip npm-proxy-2)                │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │ Local repos:  Fetch in parallel (no cooldown)                   │       │
│  │ Proxy repos:  Fetch in parallel (cooldown pre-applied)          │       │
│  │ Timeout:      Configurable (default 5s)                         │       │
│  │ Max parallel: Configurable (default 10)                         │       │
│  └─────────────────────────────────────────────────────────────────┘       │
│                         │                                                   │
│                         ▼                                                   │
│  Step 4: Merge by priority                                                  │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │ npm-local (priority 1):   4.17.20, 4.17.21, 4.17.22            │       │
│  │ npm-proxy-1 (priority 2): 4.17.20, 4.17.21 (4.17.22 cooldown)  │       │
│  │                                                                 │       │
│  │ Merged: 4.17.20, 4.17.21, 4.17.22 (local wins for conflicts)   │       │
│  └─────────────────────────────────────────────────────────────────┘       │
│                         │                                                   │
│                         ▼                                                   │
│  Step 5: Cache merged result + Update index                                 │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │ MergedMetadataCache: Store merged metadata                      │       │
│  │ PackageLocationIndex: Update TTLs, add new discoveries          │       │
│  └─────────────────────────────────────────────────────────────────┘       │
│                         │                                                   │
│                         ▼                                                   │
│                    Return Response                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Artifact vs Metadata Strategy

| Request Type | Strategy | Reason |
|--------------|----------|--------|
| **Metadata** (package.json, @v/list, /simple/, etc.) | Parallel fetch + merge | Need ALL versions from ALL members |
| **Artifact** (.tgz, .jar, .whl, .zip, blobs) | Cascade by priority | Large files, stop at first success |

```
Metadata request:
  → Parallel fetch from all KNOWN locations
  → Merge results (priority order for conflicts)
  → Cache merged result

Artifact request:
  → Check PackageLocationIndex for locations
  → Try priority-1 member first
  → If 404, try priority-2, etc.
  → Cache location on success for next time
```

## Component Design

### 1. PackageLocationIndex

Maps package names to which members have them.

```java
public final class PackageLocationIndex {

    // L1: In-memory for microsecond lookups
    private final Cache<String, PackageLocations> l1Cache;

    // L2: Valkey for distributed state
    private final ValkeyConnection valkey;

    // Settings
    private final GroupSettings settings;

    /**
     * Get known locations for a package.
     * Non-blocking, returns CompletableFuture.
     */
    public CompletableFuture<PackageLocations> getLocations(
        String groupName,
        String packageName
    );

    /**
     * Mark package as existing in a member.
     * Called on successful fetch or local publish event.
     */
    public void markExists(String groupName, String memberName, String packageName);

    /**
     * Mark package as NOT existing in a member (negative cache).
     * Called on 404 response.
     */
    public void markNotExists(String groupName, String memberName, String packageName);

    /**
     * Invalidate index entry (e.g., on local delete).
     */
    public void invalidate(String groupName, String packageName);
}

public final class PackageLocations {
    // Member name → location status
    private final Map<String, LocationStatus> members;

    public enum LocationStatus {
        EXISTS,      // Package confirmed in this member
        NOT_EXISTS,  // Package confirmed NOT in this member (negative cache)
        UNKNOWN      // Not yet checked
    }

    public List<String> knownLocations();      // Members where EXISTS
    public List<String> unknownLocations();    // Members not yet checked
    public boolean isNegativelyCached(String member);
}
```

**Index Population Strategy:**

| Member Type | Strategy | TTL |
|-------------|----------|-----|
| Local repos | Event-driven (publish/delete hooks) | No TTL (immediate) |
| Proxy repos (EXISTS) | On-demand (learned on first fetch) | Configurable (default 15min) |
| Proxy repos (NOT_EXISTS) | On-demand (learned on 404) | Configurable (default 5min) |

### 2. MergedMetadataCache

Stores merged metadata from all members.

```java
public final class MergedMetadataCache {

    // L1: In-memory
    private final Cache<String, byte[]> l1Cache;

    // L2: Valkey
    private final ValkeyConnection valkey;

    // Settings
    private final GroupSettings settings;

    /**
     * Get cached merged metadata.
     */
    public CompletableFuture<Optional<byte[]>> get(
        String groupName,
        String adapterType,
        String packageName
    );

    /**
     * Store merged metadata.
     */
    public CompletableFuture<Void> put(
        String groupName,
        String adapterType,
        String packageName,
        byte[] mergedMetadata
    );

    /**
     * Invalidate cached metadata (e.g., on local publish).
     */
    public void invalidate(String groupName, String packageName);
}
```

### 3. UnifiedGroupCache

Orchestrates index and metadata cache.

```java
public final class UnifiedGroupCache implements AutoCloseable {

    private final PackageLocationIndex locationIndex;
    private final MergedMetadataCache metadataCache;
    private final NegativeCache negativeCache;  // Reuse existing core cache
    private final GroupSettings settings;

    /**
     * Get metadata for a package, merging from all members if not cached.
     */
    public CompletableFuture<Optional<Content>> getMetadata(
        String groupName,
        String adapterType,
        String packageName,
        List<GroupMember> members,
        MetadataMerger merger
    );

    /**
     * Get artifact, trying members in priority order.
     */
    public CompletableFuture<Optional<Content>> getArtifact(
        String groupName,
        String packageName,
        String artifactPath,
        List<GroupMember> members
    );

    /**
     * Handle local repo publish event.
     */
    public void onLocalPublish(String groupName, String memberName, String packageName);

    /**
     * Handle local repo delete event.
     */
    public void onLocalDelete(String groupName, String memberName, String packageName);
}
```

### 4. MetadataMerger Interface

Adapter-specific metadata merging logic.

```java
@FunctionalInterface
public interface MetadataMerger {
    /**
     * Merge metadata from multiple members.
     *
     * @param responses Map of member name → metadata bytes (priority order)
     * @return Merged metadata bytes
     */
    byte[] merge(LinkedHashMap<String, byte[]> responses);
}
```

**Implementations per adapter:**

| Adapter | Metadata Format | Merge Strategy |
|---------|-----------------|----------------|
| NPM | JSON (package.json) | Merge `versions` object, priority for conflicts |
| Go | Text (@v/list) | Concatenate, sort, deduplicate |
| PyPI | HTML (/simple/) | Parse links, deduplicate by filename |
| Maven | XML (maven-metadata.xml) | Existing MavenGroupSlice logic |
| Docker | JSON (tag list) | Merge arrays, deduplicate |
| Composer | JSON (packages.json) | Merge `packages` object |
| Gradle | Same as Maven | Reuse Maven merger |

### 5. GroupSettings

Configurable settings for group behavior.

```java
public final class GroupSettings {

    // Index TTLs
    private final Duration remoteExistsTtl;      // default: 15 min
    private final Duration remoteNotExistsTtl;   // default: 5 min
    private final boolean localEventDriven;      // default: true

    // Metadata cache
    private final Duration metadataTtl;          // default: 5 min
    private final Duration staleServeDuration;   // default: 1 hour
    private final double backgroundRefreshAt;    // default: 0.8 (80% of TTL)

    // Resolution
    private final Duration upstreamTimeout;      // default: 5 sec
    private final int maxParallelFetches;        // default: 10

    // L1/L2 cache sizes
    private final int l1MaxEntries;              // default: 10,000
    private final long l2MaxEntries;             // default: 1,000,000

    public static GroupSettings fromYaml(YamlMapping yaml);
}
```

## Configuration Schema

Global defaults are set in `artipie.yml` under `meta.group`. Per-repo overrides are set under `repo.settings.group`.

### Global Settings (artipie.yml)

```yaml
meta:
  group:
    index:
      remote_exists_ttl: 15m      # Cache that remote repo has package
      remote_not_exists_ttl: 5m   # Negative cache for 404s
      local_event_driven: true    # Immediate updates for local repos
    metadata:
      ttl: 5m                     # Merged metadata cache TTL
      stale_serve: 1h             # Serve stale if upstream down
      background_refresh_at: 0.8  # Trigger refresh at 80% of TTL
    resolution:
      upstream_timeout: 5s        # Max wait per upstream request
      max_parallel: 10            # Max concurrent upstream requests
    cache_sizing:
      l1_max_entries: 10000       # In-memory cache size
      l2_max_entries: 1000000     # Valkey cache size
```

### Per-Repo Overrides

```yaml
repo:
  type: npm-group
  members:
    - npm-local        # Priority 1 (checked first for artifacts)
    - npm-proxy-1      # Priority 2
    - npm-proxy-2      # Priority 3

  # Optional: override global group settings
  settings:
    group:
      index:
        remote_exists_ttl: 30m    # Override: 30 min for this repo
      metadata:
        ttl: 10m                  # Override: 10 min for this repo
```

## Cooldown Behavior

**Key principle: Cooldown only applies to proxy repos, not local repos.**

```
Local repos:  NO cooldown - versions available immediately on publish
Proxy repos:  Cooldown applied at proxy level (existing behavior)
Groups:       Inherits from members - no group-level cooldown needed
```

**Flow:**

```
User requests lodash@4.17.22 from npm-group:

1. npm-local has 4.17.22 → Return immediately (no cooldown)
2. npm-local doesn't have it:
   → Check npm-proxy-1 (has cooldown configured)
   → If 4.17.22 is in cooldown period → Block
   → If cooldown expired → Return version
```

**Why no group-level cooldown:**
- Local repos bypass cooldown by design (you control what you publish)
- Proxy repos already filter at member level
- Merged result = local (all versions) + proxy (filtered versions)
- Local always takes priority → blocked proxy versions available from local

## Cache Consolidation

### Caches to DELETE

| Cache | Location | Reason |
|-------|----------|--------|
| `NegativeCache` | maven-adapter | Redundant - use core NegativeCache with repo type |
| `GroupNegativeCache` | artipie-main | Merge into core NegativeCache with group-aware keys |
| `GroupMetadataCache` | artipie-main | Replaced by MergedMetadataCache in UnifiedGroupCache |

### Caches to KEEP

| Cache | Location | Reason |
|-------|----------|--------|
| `NegativeCache` | artipie-core | Well-designed, two-tier, reusable |
| `FilteredMetadataCache` | artipie-core | Cooldown-specific, applies to proxy members only |
| `CooldownCache` | artipie-core | Cooldown decisions, proxy-only |
| `StorageCache` | artipie-core | Artifact write-through, already integrated |
| `CachePolicy` | artipie-core | TTL/refresh policy, enhance with group settings |
| `MetadataCache` | maven-adapter | Per-adapter metadata, used by proxy members |

### Caches to ADD

| Cache | Location | Purpose |
|-------|----------|---------|
| `PackageLocationIndex` | artipie-core | Maps package → member locations |
| `MergedMetadataCache` | artipie-core | Stores merged group metadata |
| `UnifiedGroupCache` | artipie-core | Orchestrates index + metadata |

## Vert.x Non-Blocking Requirements

All operations must be non-blocking for Vert.x event loop compatibility:

```java
// ✅ CORRECT: Non-blocking with CompletableFuture
public CompletableFuture<Optional<Content>> getMetadata(...) {
    return locationIndex.getLocations(groupName, packageName)
        .thenCompose(locations -> {
            // Parallel fetch from known locations
            List<CompletableFuture<byte[]>> futures = locations.knownLocations()
                .stream()
                .map(member -> fetchFromMember(member, packageName))
                .toList();

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> mergeResults(futures));
        });
}

// ❌ WRONG: Blocking call in event loop
public Optional<Content> getMetadataBlocking(...) {
    PackageLocations locations = locationIndex.getLocations(...).get(); // BLOCKS!
    // ...
}
```

**Heavy operations offloaded to worker pool:**

```java
// Large metadata merging (100K+ entries) - offload to worker
if (totalSize > MERGE_THRESHOLD) {
    return vertx.executeBlocking(() -> merger.merge(responses));
}
```

## Performance Targets

| Metric | Target | How |
|--------|--------|-----|
| Cache hit latency | <1ms | L1 Caffeine (~1μs) |
| Cache miss (index known) | <50ms | Parallel fetch from known members only |
| Cache miss (index unknown) | <100ms | Cascade by priority until found |
| Memory per group | <100MB | 100K packages × ~1KB per entry |
| Upstream calls reduction | 80%+ | Index prevents querying members without package |

## Implementation Priority

1. **Core infrastructure** - PackageLocationIndex, MergedMetadataCache, UnifiedGroupCache
2. **NPM merger** - Most requested, JSON merging straightforward
3. **Go merger** - Simple text list merging
4. **PyPI merger** - HTML parsing required
5. **Docker merger** - Tag list merging (JSON)
6. **Composer merger** - Similar to NPM (JSON)
7. **Delete redundant caches** - Cleanup after new system verified

## Success Criteria

1. `npm view lodash versions` returns ALL versions from ALL members
2. `go list -m -versions example.com/pkg` returns merged version list
3. `pip index versions requests` shows all available versions
4. Local package requests don't trigger upstream calls
5. Consistent results regardless of member response timing
6. No blocking operations in Vert.x event loop
7. Performance: <1ms for cached, <100ms for uncached metadata
8. Memory: <100MB per group for 100K packages

## Migration Plan

1. **Phase 1**: Add new caches alongside existing (feature flag)
2. **Phase 2**: Migrate NPM groups to new system
3. **Phase 3**: Migrate other adapters
4. **Phase 4**: Delete redundant caches
5. **Phase 5**: Remove feature flag, new system is default
