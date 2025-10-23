# S3 Cache Architecture Issues & Recommendations

## 🚨 Critical Problems with Current Per-Repo Cache

### Issue #1: Resource Multiplication
**Problem:** Each repository creates its own cache instance with dedicated resources.

**Current Behavior:**
- 1 repo with cache = 1 cleaner thread + 1 cache namespace
- 100 repos with cache = 100 cleaner threads + 100 cache namespaces
- **Thread explosion** under multi-tenant scenarios

**Example:**
```yaml
# 10 repos all pointing to same S3 bucket
repo1.yaml: cache {path: /cache, max-bytes: 10GB} → Thread #1
repo2.yaml: cache {path: /cache, max-bytes: 10GB} → Thread #2
...
repo10.yaml: cache {path: /cache, max-bytes: 10GB} → Thread #10
```

**Impact:**
- 10 threads scanning same `/cache` directory every 5 minutes
- Each thread thinks it has 10GB quota, actually shares same disk
- No coordination between threads → race conditions in cleanup

---

### Issue #2: Massive Disk Waste
**Problem:** Same content duplicated across namespace directories.

**Example Scenario:**
```
3 repositories (npm_proxy, maven_proxy, docker_proxy) all fetch from same S3 bucket
Package: lodash-4.17.21.tgz (550KB)

Actual disk usage:
/cache/
├── 7a8e9f... (npm_proxy namespace)
│   └── lodash/lodash-4.17.21.tgz (550KB)
├── b3c1d2... (maven_proxy namespace)
│   └── lodash/lodash-4.17.21.tgz (550KB) ← DUPLICATE!
└── e4f5a6... (docker_proxy namespace)
    └── lodash/lodash-4.17.21.tgz (550KB) ← DUPLICATE!

Total: 1.65MB for same 550KB file
```

**With 1000 packages:**
- Actual unique data: 1GB
- Disk usage: 3GB (3x waste)
- 10 repos: 10GB disk usage for 1GB data

---

### Issue #3: Configuration Burden
**Problem:** Must configure cache for EVERY repository.

**Current:**
```yaml
# Must repeat for every repo
repo1.yaml:
  storage:
    cache: {enabled: true, path: /cache, max-bytes: 10GB}
repo2.yaml:
  storage:
    cache: {enabled: true, path: /cache, max-bytes: 10GB}
# ... repeat 100 times
```

**Issues:**
- Configuration explosion
- Inconsistent cache settings
- Hard to manage cache budget globally
- No way to disable cache globally without editing all repos

---

### Issue #4: No Global Cache Budget
**Problem:** Cannot enforce server-wide cache limits.

**Current:**
```yaml
repo1: max-bytes: 10GB
repo2: max-bytes: 10GB
repo3: max-bytes: 10GB
# Total configured: 30GB
# Actual disk usage: 30GB (all namespaces share same path!)
# Server only has 20GB available → FAILS
```

**No way to say:** "Entire server can use 20GB for caching, distribute as needed"

---

### Issue #5: Race Conditions (Fixed but symptom of deeper issue)
**Problem:** Multiple operations update same metadata files without coordination.

**Fixed in commit:** Added synchronization and async updates
**Root cause:** Per-repo design encourages concurrent access patterns

---

## ✅ Recommended Solution: Centralized Content-Addressable Cache

### Architecture Overview

```
Single global cache for entire Artipie server:

/var/artipie/cache/
├── config.json (global cache configuration)
├── objects/
│   ├── sha256/
│   │   ├── ab/
│   │   │   └── abcd1234... (content blob)
│   │   └── ef/
│   │       └── ef123456... (content blob)
│   └── metadata/
│       ├── abcd1234.meta (hits, access time, size)
│       └── ef123456.meta
└── index/
    ├── repo1/
    │   └── package.tar.gz → ../../objects/sha256/ab/abcd1234...
    └── repo2/
        └── package.tar.gz → ../../objects/sha256/ab/abcd1234... (same blob!)
```

### Key Features

1. **Content-addressed storage** - Deduplication by checksum
2. **Single cleanup thread** - One scheduler for entire cache
3. **Global cache budget** - Server-wide limit
4. **Reference counting** - Only delete when no repos reference
5. **Atomic operations** - No race conditions

### Configuration

**Server-level (_server.yaml):**
```yaml
cache:
  enabled: true
  path: /var/artipie/cache
  max-bytes: 50GB  # Shared across ALL repos
  cleanup-interval-millis: 300000
  eviction-policy: LRU
  validate-on-read: false  # Disable for performance
```

**Repository-level (repo.yaml):**
```yaml
storage:
  type: s3
  bucket: my-bucket
  # NO cache section needed - automatically uses global cache
```

### Benefits

| Metric | Current (Per-Repo) | Proposed (Global) | Improvement |
|--------|-------------------|-------------------|-------------|
| **Disk for 1GB unique data (10 repos)** | 10GB | 1GB | **90% reduction** |
| **Cleanup threads** | 10 | 1 | **90% reduction** |
| **Configuration lines** | 50+ | 5 | **90% reduction** |
| **Cache budget clarity** | Confusing | Clear | ✅ Enforceable |
| **Race conditions** | Frequent | Rare | ✅ Atomic ops |

---

## 🔧 Implementation Plan

### Phase 1: Quick Win - Shared Cache Path (Backward Compatible)

**Allow repos to reference same cache instance:**

```yaml
# _server.yaml - Define shared cache
storage-cache:
  s3-global:
    path: /var/artipie/s3-cache
    max-bytes: 50GB
    cleanup-interval-millis: 300000

# repo1.yaml - Reference it
storage:
  type: s3
  bucket: my-bucket
  cache: s3-global  # ← Reference shared cache

# repo2.yaml - Reference same
storage:
  type: s3
  bucket: my-bucket
  cache: s3-global  # ← Shared!
```

**Benefits:**
- ✅ Single cleaner thread
- ✅ Shared budget
- ✅ Still has namespaces (no dedup yet)
- ✅ Backward compatible

**Effort:** 1-2 days

---

### Phase 2: Content Deduplication

**Add checksum-based storage layer:**

```java
class ContentAddressableCache implements Storage {
    private final Path objectsDir;  // sha256/ab/abcd...
    private final Path indexDir;    // repo/key → object
    
    @Override
    public CompletableFuture<Content> value(Key key) {
        // 1. Check index: repo/key → hash
        // 2. Load object: objects/sha256/{hash}
        // 3. Return content
    }
    
    @Override
    public CompletableFuture<Void> save(Key key, Content content) {
        // 1. Stream content, compute SHA256
        // 2. Check if object exists
        // 3. If not, save to objects/sha256/{hash}
        // 4. Create index entry: repo/key → hash
        // 5. Increment reference count
    }
}
```

**Benefits:**
- ✅ Zero duplication
- ✅ Automatic dedup across all repos
- ✅ Reference counting for safe cleanup

**Effort:** 5-7 days

---

### Phase 3: Global Cache Service

**Move cache to server-level service:**

```java
// Server startup
CacheService globalCache = new CacheService(config.cache());
globalCache.start();

// Repository creation
if (storage instanceof S3Storage && config.cache().enabled()) {
    storage = new CachedStorage(storage, globalCache);
}
```

**Benefits:**
- ✅ Single point of control
- ✅ Server-wide metrics
- ✅ Hot-swap cache config
- ✅ Graceful shutdown

**Effort:** 3-4 days

---

## 🎯 Immediate Recommendations

### For Current Codebase (Today)

**1. Disable cache validation for performance:**
```yaml
cache:
  validate-on-read: false  # ← Add this!
```
This removes the blocking `.join()` call.

**2. Use shorter cleanup interval:**
```yaml
cache:
  cleanup-interval-millis: 60000  # 1 minute instead of 5
```
Prevents cache from growing too large between cleanups.

**3. Lower cache size per repo:**
```yaml
cache:
  max-bytes: 2GB  # Instead of 10GB per repo
```
Since they share the same path, keep individual limits small.

**4. Monitor thread count:**
```bash
# Check how many cleaner threads
docker exec artipie jstack 1 | grep "disk-cache-cleaner" | wc -l

# Should equal number of repos with cache enabled
```

---

### For Production (Short Term)

**Centralize cache config using shared reference (Phase 1):**

```yaml
# _aliases.yaml (create this)
storage-caches:
  primary:
    type: disk
    path: /var/artipie/cache
    max-bytes: 20GB
    policy: LRU
    cleanup-interval-millis: 60000
    validate-on-read: false

# repo1.yaml, repo2.yaml, ... repo100.yaml
storage:
  type: s3
  bucket: bucket-name
  cache-alias: primary  # ← All use same cache
```

**Benefits:**
- Configure once, use everywhere
- Single cleanup thread
- Clear global budget

---

### For Future (Long Term)

**Implement content-addressable cache (Phase 2 + 3):**
- Zero duplication
- True deduplication
- Reference counting
- Server-level management

**Expected savings for 100 repos:**
- Disk: 90-95% reduction
- Threads: 99% reduction
- Configuration: 95% reduction

---

## 📊 Performance Impact Analysis

### Scenario: 100 Repositories, 10GB Cache Each

**Current (Per-Repo):**
- Configured cache: 1000GB (100 × 10GB)
- Actual disk usage: 1000GB (no dedup)
- Cleaner threads: 100
- Unique content: ~100GB (10x duplication typical)
- **90% disk waste**

**Proposed (Shared, No Dedup):**
- Configured cache: 50GB (single limit)
- Actual disk usage: ~200GB (namespaces still separate)
- Cleaner threads: 1
- **50% improvement**

**Proposed (Shared + Content-Addressed):**
- Configured cache: 50GB
- Actual disk usage: 50GB (perfect dedup)
- Cleaner threads: 1
- Unique content: 50GB
- **95% improvement**

---

## 🔍 Detection & Monitoring

### Check Current Cache Usage

```bash
# Find cache directories
find /var/artipie -name "*.meta" -path "*/cache/*"

# Count namespaces (= number of cache instances)
ls -d /var/artipie/cache/*/ | wc -l

# Check for duplicate content
find /var/artipie/cache -type f -exec md5sum {} \; | sort | uniq -d -w32

# Count cleaner threads
ps aux | grep disk-cache-cleaner | wc -l
```

### Monitor Issues

**Symptoms of per-repo cache problems:**
- High thread count (100+)
- Disk usage >> expected
- Multiple threads scanning same directory
- Configuration inconsistencies across repos

---

## 📝 Summary

**Current State:** ⚠️ Per-repo cache with massive waste
**Immediate Fix:** ✅ Fixed race conditions, added timeouts
**Short-term Goal:** 🎯 Shared cache reference (Phase 1)
**Long-term Goal:** 🚀 Content-addressable global cache (Phase 2+3)

**You are 100% correct:** Cache should be centralized at server level, not per-repository. The current design is overkill and wasteful.
