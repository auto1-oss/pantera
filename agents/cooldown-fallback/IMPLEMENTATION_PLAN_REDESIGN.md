# Implementation Plan - Metadata-Based Fallback Redesign

**Date:** November 23, 2024  
**Status:** Proposed Implementation Roadmap

---

## Overview

This document outlines the implementation plan for the **metadata-based fallback mechanism** that replaces the flawed download-level fallback approach documented in the original `03_IMPLEMENTATION_PLAN.md`.

**Key Difference:** We filter versions OUT of metadata responses instead of redirecting downloads to different versions.

---

## Phase 0: Preparation (Week 1, Days 1-2)

### 0.1 Code Review and Cleanup

**Tasks:**
- Review existing `CooldownInspector` implementations
- Review existing proxy slice implementations
- Identify code that needs to be modified vs. new code
- Create feature branch: `feature/metadata-based-cooldown-fallback`

**Deliverables:**
- Code review notes
- List of files to modify
- List of new files to create

**Effort:** 8 hours

### 0.2 Dependency Analysis

**Tasks:**
- Verify Jackson version (JSON parsing)
- Add Jsoup dependency (HTML parsing for PyPI)
- Add DOM4J or JDOM2 dependency (XML parsing for Maven)
- Verify semver library availability (NPM version comparison)
- Verify Maven version comparison library

**Deliverables:**
- Updated `pom.xml` files with new dependencies
- Dependency compatibility verification

**Effort:** 4 hours

---

## Phase 1: Core Infrastructure (Week 1, Days 3-5)

### 1.0 Block Decision Storage Architecture

**Understanding the Existing System:**

The current cooldown system uses a **stateful approach** with explicit block records persisted in the database:

**Database Schema (L3 - Source of Truth):**
```sql
CREATE TABLE artifact_cooldowns (
    id BIGSERIAL PRIMARY KEY,
    repo_type VARCHAR NOT NULL,
    repo_name VARCHAR NOT NULL,
    artifact VARCHAR NOT NULL,
    version VARCHAR NOT NULL,
    reason VARCHAR NOT NULL,           -- FRESH_RELEASE, DEPENDENCY_BLOCKED
    status VARCHAR NOT NULL,            -- ACTIVE, INACTIVE
    blocked_by VARCHAR NOT NULL,        -- 'system' or admin username
    blocked_at BIGINT NOT NULL,         -- Epoch milliseconds
    blocked_until BIGINT NOT NULL,      -- Epoch milliseconds (expiration time)
    unblocked_at BIGINT,                -- Epoch milliseconds (when manually unblocked)
    unblocked_by VARCHAR,               -- Admin username who unblocked
    parent_block_id BIGINT,             -- FK to parent block (for dependency chains)
    CONSTRAINT cooldown_artifact_unique UNIQUE (repo_name, artifact, version)
);
```

**Three-Tier Cache Architecture:**

1. **L1 Cache (Caffeine in-memory):**
   - Key format: `cooldown:{repo_name}:{artifact}:{version}:block`
   - Value: `Boolean` (true = blocked, false = allowed)
   - TTL: Configured (default 5 minutes for allowed, dynamic for blocked)
   - Scope: Per-instance (not shared)

2. **L2 Cache (Valkey/Redis shared):**
   - Key format: Same as L1
   - Value: `Boolean` (true = blocked, false = allowed)
   - TTL: Dynamic based on `blocked_until` for blocked entries, configured for allowed
   - Scope: Shared across all Artipie instances

3. **L3 Database (PostgreSQL):**
   - Source of truth for all block records
   - Stores complete block metadata (reason, timestamps, dependencies)
   - Supports queries for active blocks, manual unblocking, audit trail

**Block Decision Lookup Flow:**

```
1. Check L1 cache (Caffeine)
   ├─ HIT → Return cached decision (< 1ms)
   └─ MISS → Continue to L2

2. Check L2 cache (Valkey/Redis)
   ├─ HIT → Store in L1, return decision (< 5ms)
   └─ MISS → Continue to L3

3. Query L3 database
   ├─ Existing block record found
   │  ├─ Status = ACTIVE && blocked_until > now
   │  │  └─ Cache as BLOCKED with TTL = (blocked_until - now)
   │  └─ Status = INACTIVE || blocked_until <= now
   │     └─ Cache as ALLOWED with configured TTL
   └─ No block record found
      ├─ Fetch release date from upstream (via CooldownInspector)
      ├─ Calculate: is (now - release_date) < cooldown_period?
      │  ├─ YES → Create block record in database
      │  │        Cache as BLOCKED with TTL = (release_date + cooldown_period - now)
      │  └─ NO  → Cache as ALLOWED with configured TTL
      └─ Return decision
```

**Block Record Creation:**

When a version is determined to be blocked (first access during cooldown period):
1. Insert record into `artifact_cooldowns` table with:
   - `status = ACTIVE`
   - `reason = FRESH_RELEASE`
   - `blocked_until = release_date + cooldown_period`
   - `blocked_by = 'system'`
2. Cache the decision in L1 and L2 with dynamic TTL
3. Record access attempt in `artifact_cooldown_attempts` table (audit trail)

**Automatic Expiration:**

Blocks expire automatically when `blocked_until <= current_time`:
- Database record status changes to `INACTIVE`
- Cache entries expire based on TTL
- Next access will find expired block and cache as ALLOWED

**Manual Unblocking:**

Administrators can manually unblock via `CooldownService.unblock()`:
1. Update database record: `status = INACTIVE`, `unblocked_at = now`, `unblocked_by = admin`
2. Invalidate L1 and L2 cache entries
3. Invalidate inspector metadata cache (via `InspectorRegistry`)
4. Next access will find inactive block and cache as ALLOWED

**For Metadata Filtering:**

The metadata filtering implementation will use the **same three-tier cache architecture**:
- For each version in metadata, call `CooldownCache.isBlocked(repo, artifact, version)`
- This leverages existing L1/L2/L3 lookup with sub-millisecond performance for cache hits
- Batch queries can be optimized using `CompletableFuture.allOf()` for parallel checks
- No changes to database schema required

### 1.1 Metadata Service Interfaces

**Create:**
- `MetadataRequestDetector` interface
- `MetadataParser<T>` interface
- `MetadataFilter<T>` interface
- `MetadataRewriter<T>` interface
- `CooldownMetadataService` interface

**Location:** `artipie-core/src/main/java/com/artipie/cooldown/metadata/`

**Effort:** 8 hours

### 1.2 Metadata Service Implementation

**Create:**
- `JdbcCooldownMetadataService` class implementing `CooldownMetadataService`
- Integrate with existing `CooldownCache`
- Integrate with existing `JdbcCooldownService`

**Key Methods:**
```java
public <T> CompletableFuture<byte[]> filterMetadata(
    String repoName,
    String packageName,
    byte[] metadata,
    MetadataParser<T> parser,
    MetadataFilter<T> filter,
    MetadataRewriter<T> rewriter
)
```

**Location:** `artipie-core/src/main/java/com/artipie/cooldown/metadata/`

**Effort:** 16 hours

### 1.3 Metadata Cache

**Create:**
- `MetadataCache` class for caching filtered metadata
- Three-tier caching: L1 (Caffeine), L2 (Valkey), L3 (Database)
- Cache invalidation on block/unblock events

**Key Methods:**
```java
CompletableFuture<byte[]> get(String repo, String pkg)
void put(String repo, String pkg, byte[] metadata, Duration ttl)
void invalidate(String repo, String pkg)
```

**Cache Key Format:**
```
metadata:{repo_name}:{package_name}:filtered
```

**TTL Strategy:**
- Default TTL: 5-15 minutes (configurable)
- Shorter TTL (1 minute) after unblock events to ensure quick propagation
- Invalidate immediately on block/unblock operations

**Location:** `artipie-core/src/main/java/com/artipie/cooldown/metadata/`

**Effort:** 12 hours

### 1.3.1 Version Unblocking and Re-inclusion Workflow

**Unblocking Triggers:**

1. **Automatic Expiration:**
   - Occurs when `current_time >= blocked_until`
   - No explicit action needed - next access finds expired block
   - Database record remains with `status = ACTIVE` but is treated as expired
   - Optional: Background job to update `status = INACTIVE` for housekeeping

2. **Manual Administrator Unblock:**
   - Admin calls `CooldownService.unblock(repoType, repoName, artifact, version, adminUsername)`
   - Immediately updates database: `status = INACTIVE`, `unblocked_at = now`, `unblocked_by = admin`
   - Triggers cache invalidation cascade

**Cache Invalidation Flow on Manual Unblock:**

```java
// In JdbcCooldownService.unblock()
public CompletableFuture<Void> unblock(
    String repoType, String repoName, String artifact, String version, String actor
) {
    return CompletableFuture.runAsync(() -> {
        // 1. Update database record
        repository.find(repoType, repoName, artifact, version)
            .ifPresent(record -> release(record, actor, Instant.now()));

        // 2. Invalidate block decision cache (L1 + L2)
        cache.invalidate(repoName, artifact, version);

        // 3. Invalidate inspector metadata cache
        //    (NPM package.json cache, Maven metadata cache, etc.)
        InspectorRegistry.instance()
            .invalidate(repoType, repoName, artifact, version);

        // 4. Publish cache invalidation event to event bus
        //    (for distributed cache invalidation across instances)
        eventBus.publish(new CacheInvalidationEvent(
            repoType, repoName, artifact, version
        ));

        // 5. Invalidate filtered metadata cache
        metadataCache.invalidate(repoName, artifact);

    }, executor);
}
```

**Event Bus for Distributed Cache Invalidation:**

```java
// Event published when version is unblocked
public class CacheInvalidationEvent {
    private final String repoType;
    private final String repoName;
    private final String artifact;
    private final String version;
    private final Instant timestamp;
}

// Event subscriber in each Artipie instance
public class CacheInvalidationSubscriber {
    public void onInvalidation(CacheInvalidationEvent event) {
        // Invalidate local L1 cache
        cache.invalidateLocal(event.repoName, event.artifact, event.version);

        // Invalidate filtered metadata cache
        metadataCache.invalidateLocal(event.repoName, event.artifact);

        // Log event
        EcsLogger.info("com.artipie.cooldown")
            .message("Cache invalidated due to unblock event")
            .eventCategory("cooldown")
            .eventAction("cache_invalidation")
            .field("package.name", event.artifact)
            .field("package.version", event.version)
            .log();
    }
}
```

**Metadata Re-inclusion Strategy:**

After unblocking, the version will be re-included in metadata responses:

1. **Immediate Invalidation:**
   - Filtered metadata cache is invalidated immediately
   - Next metadata request will re-fetch and re-filter

2. **Short TTL After Unblock:**
   - Metadata cache entries created after unblock use shorter TTL (1 minute)
   - Ensures rapid propagation even if some instances miss the event

3. **Client-Side Caching:**
   - Clients may have cached the filtered metadata (without the unblocked version)
   - Client cache TTL is typically 5-15 minutes
   - Unblocked version will appear after client cache expires
   - **Recommendation:** Document expected propagation delay (up to 15 minutes)

**Handling Race Conditions:**

```java
// Scenario: Version unblocked while metadata request is in progress

// Solution 1: Check block status at filter time (not at cache time)
public CompletableFuture<byte[]> filterMetadata(...) {
    return metadataCache.get(repo, pkg).thenCompose(cached -> {
        if (cached != null && !recentlyUnblocked(repo, pkg)) {
            return CompletableFuture.completedFuture(cached);
        }
        // Re-filter if recently unblocked
        return fetchAndFilter(repo, pkg);
    });
}

// Solution 2: Use optimistic locking with version numbers
// Cache entries include a version number that increments on invalidation
```

**Propagation Time Expectations:**

| Scenario | Expected Propagation Time |
|----------|--------------------------|
| Automatic expiration | Immediate (next request) |
| Manual unblock (same instance) | < 1 second |
| Manual unblock (other instances) | < 5 seconds (event bus) |
| Client sees unblocked version | Up to 15 minutes (client cache TTL) |

**Monitoring and Alerting:**

```java
// Prometheus metrics
Counter unblockOperations = Counter.build()
    .name("artipie_cooldown_unblock_total")
    .help("Total unblock operations")
    .labelNames("repo_type", "trigger")  // trigger: manual, automatic
    .register();

Histogram cacheInvalidationLatency = Histogram.build()
    .name("artipie_cache_invalidation_duration_seconds")
    .help("Cache invalidation latency")
    .labelNames("cache_type")  // L1, L2, metadata
    .register();
```

### 1.4 Event Bus for Cache Invalidation

**Create:**
- `CacheInvalidationEvent` class
- Event publisher in `JdbcCooldownService`
- Event subscriber in `MetadataCache`

**Integration:**
- Use existing event bus if available
- Otherwise, implement simple pub/sub with Valkey

**Effort:** 8 hours

**Total Phase 1:** 44 hours (3 days)

---

## Phase 2: NPM Adapter (Week 2, Days 1-3)

### 2.1 NPM Metadata Components

**Create:**
- `NpmMetadataRequestDetector` - detect metadata requests (`^/[^/]+$`)
- `NpmMetadataParser` - parse JSON metadata
- `NpmMetadataFilter` - filter `versions` and `time` objects
- `NpmMetadataRewriter` - serialize back to JSON
- `NpmErrorResponseBuilder` - build 403 Forbidden error responses for all-versions-blocked case

**Location:** `npm-adapter/src/main/java/com/artipie/npm/proxy/metadata/`

**Error Handling:**
When all versions are blocked, return HTTP 403 Forbidden with JSON error:
```json
{
  "error": "Forbidden",
  "reason": "All versions of package 'lodash' are currently blocked by cooldown policy. New releases are blocked for 72 hours to prevent supply chain attacks. Please try again later or contact your administrator."
}
```

**See:** `docs/cooldown-fallback/ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete specification.

**Effort:** 14 hours (includes error handling)

### 2.2 Integrate with CachedNpmProxySlice

**Modify:** `npm-adapter/src/main/java/com/artipie/npm/proxy/http/CachedNpmProxySlice.java`

**Changes:**
- Add metadata request detection
- Add `handleMetadataRequest()` method
- Call `CooldownMetadataService.filterMetadata()`
- Serve filtered metadata with appropriate headers

**Effort:** 8 hours

### 2.3 NPM Tests

**Create:**
- Unit tests for parser, filter, rewriter
- Integration tests with real NPM metadata
- End-to-end tests with npm CLI

**Test Cases:**
- Normal metadata filtering
- All versions blocked
- Latest version blocked
- Large metadata (1000+ versions)

**Effort:** 12 hours

**Total Phase 2:** 32 hours (2.5 days)

---

## Phase 3: PyPI Adapter (Week 2, Days 4-5 + Week 3, Day 1)

### 3.1 PyPI Metadata Components

**Create:**
- `PyPiMetadataRequestDetector` - detect simple API requests (`^/simple/[^/]+/?$`)
- `PyPiMetadataParser` - parse HTML (PEP 503) or JSON (PEP 691) metadata
- `PyPiMetadataFilter` - filter `<a>` tags (HTML) or file entries (JSON)
- `PyPiMetadataRewriter` - serialize back to HTML or JSON
- `PyPiErrorResponseBuilder` - build 403 Forbidden error responses for all-versions-blocked case

**Location:** `pypi-adapter/src/main/java/com/artipie/pypi/http/metadata/`

**Error Handling:**
When all versions are blocked, return HTTP 403 Forbidden:

**PEP 503 (HTML):**
```html
<!DOCTYPE html>
<html>
  <head><title>403 Forbidden</title></head>
  <body>
    <h1>403 Forbidden</h1>
    <p>All versions of package '<strong>requests</strong>' are currently blocked by cooldown policy.</p>
    <p>New releases are blocked for 72 hours to prevent supply chain attacks.</p>
    <p>Please try again later or contact your administrator.</p>
  </body>
</html>
```

**PEP 691 (JSON):**
```json
{
  "meta": {"api-version": "1.0"},
  "error": {
    "code": "cooldown_blocked",
    "message": "All versions of this package are currently blocked by cooldown policy..."
  }
}
```

**See:** `docs/cooldown-fallback/ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete specification.

**Effort:** 14 hours (includes error handling for both PEP 503 and PEP 691)

### 3.2 Integrate with CachedPyProxySlice

**Modify:** `pypi-adapter/src/main/java/com/artipie/pypi/http/CachedPyProxySlice.java`

**Changes:**
- Add metadata request detection
- Add `handleMetadataRequest()` method
- Call `CooldownMetadataService.filterMetadata()`

**Effort:** 8 hours

### 3.3 PyPI Tests

**Create:**
- Unit tests for parser, filter, rewriter
- Integration tests with real PyPI metadata
- End-to-end tests with pip

**Effort:** 12 hours

**Total Phase 3:** 32 hours (2.5 days)

---

## Phase 4: Maven Adapter (Week 3, Days 2-4)

### 4.1 Maven Metadata Components

**Create:**
- `MavenMetadataRequestDetector` - detect `maven-metadata.xml` requests
- `MavenMetadataParser` - parse XML metadata
- `MavenMetadataFilter` - filter `<version>` elements
- `MavenMetadataRewriter` - serialize back to XML
- `MavenErrorResponseBuilder` - build 403 Forbidden error responses for all-versions-blocked case

**Location:** `maven-adapter/src/main/java/com/artipie/maven/http/metadata/`

**Error Handling:**
When all versions are blocked, return HTTP 403 Forbidden with plain text error:
```
All versions of artifact 'com.example:my-library' are currently blocked by cooldown policy.
New releases are blocked for 72 hours to prevent supply chain attacks.
Please try again later or contact your administrator.
```

**Headers:**
```
HTTP/1.1 403 Forbidden
Content-Type: text/plain
X-Artipie-Cooldown-Blocked: true
X-Artipie-Cooldown-Period: 259200
```

**See:** `docs/cooldown-fallback/ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete specification.

**Effort:** 14 hours (includes error handling)

### 4.2 Integrate with CachedProxySlice

**Modify:** `maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java`

**Changes:**
- Add metadata request detection
- Add `handleMetadataRequest()` method
- Call `CooldownMetadataService.filterMetadata()`

**Effort:** 8 hours

### 4.3 Maven Tests

**Create:**
- Unit tests for parser, filter, rewriter
- Integration tests with real Maven metadata
- End-to-end tests with mvn

**Effort:** 12 hours

**Total Phase 4:** 32 hours (2.5 days)

---

## Phase 5: Gradle Adapter (Week 3, Day 5)

### 5.1 Gradle Metadata Components

**Reuse:** Maven components (Gradle uses Maven format for `maven-metadata.xml`)

**Create:**
- `GradleModuleMetadataParser` - parse `.module` files (JSON)
- `GradleModuleMetadataFilter` - filter variants
- `GradleModuleMetadataRewriter` - serialize back to JSON
- `GradleErrorResponseBuilder` - build 403 Forbidden error responses (reuse Maven error builder)

**Location:** `gradle-adapter/src/main/java/com/artipie/gradle/http/metadata/`

**Error Handling:**
Same as Maven - return HTTP 403 Forbidden with plain text error message.

**See:** `docs/cooldown-fallback/ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete specification.

**Effort:** 8 hours (error handling reuses Maven implementation)

### 5.2 Integrate with CachedProxySlice

**Modify:** `gradle-adapter/src/main/java/com/artipie/gradle/http/CachedProxySlice.java`

**Changes:**
- Reuse Maven integration
- Add `.module` file handling

**Effort:** 4 hours

### 5.3 Gradle Tests

**Create:**
- Unit tests for `.module` parser
- Integration tests with gradle

**Effort:** 8 hours

**Total Phase 5:** 20 hours (1.5 days)

---

## Phase 6: Composer Adapter (Week 4, Days 1-2)

### 6.1 Composer Metadata Components

**Create:**
- `ComposerMetadataRequestDetector` - detect `/p2/{vendor}/{package}.json`
- `ComposerMetadataParser` - parse JSON metadata
- `ComposerMetadataFilter` - filter `packages` object
- `ComposerMetadataRewriter` - serialize back to JSON
- `ComposerErrorResponseBuilder` - build 403 Forbidden error responses for all-versions-blocked case

**Location:** `composer-adapter/src/main/java/com/artipie/composer/http/metadata/`

**Error Handling:**
When all versions are blocked, return HTTP 403 Forbidden with JSON error:
```json
{
  "error": {
    "code": "cooldown_blocked",
    "message": "All versions of package 'vendor/package' are currently blocked by cooldown policy. New releases are blocked for 72 hours to prevent supply chain attacks. Please try again later or contact your administrator."
  }
}
```

**See:** `docs/cooldown-fallback/ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete specification.

**Effort:** 14 hours (includes error handling)

### 6.2 Integrate with CachedProxySlice

**Modify:** `composer-adapter/src/main/java/com/artipie/composer/http/proxy/CachedProxySlice.java`

**Changes:**
- Add metadata request detection
- Add `handleMetadataRequest()` method
- Call `CooldownMetadataService.filterMetadata()`

**Effort:** 8 hours

### 6.3 Composer Tests

**Create:**
- Unit tests for parser, filter, rewriter
- Integration tests with composer

**Effort:** 12 hours

**Total Phase 6:** 32 hours (2.5 days)

---

## Phase 7: Go Adapter (Week 4, Days 3-4)

### 7.1 Go Metadata Components

**Create:**
- `GoMetadataRequestDetector` - detect `/@v/list` requests
- `GoMetadataParser` - parse text list
- `GoMetadataFilter` - filter lines
- `GoMetadataRewriter` - serialize back to text
- `GoErrorResponseBuilder` - build 403 Forbidden error responses for all-versions-blocked case

**Location:** `go-adapter/src/main/java/com/artipie/http/metadata/`

**Error Handling:**
When all versions are blocked, return HTTP 403 Forbidden with plain text error:
```
All versions of module 'example.com/module' are currently blocked by cooldown policy.
New releases are blocked for 72 hours to prevent supply chain attacks.
Please try again later or contact your administrator.
```

**Note:** Using 403 Forbidden (not 410 Gone) allows fallback to other proxies in GOPROXY list if configured.

**See:** `docs/cooldown-fallback/ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete specification.

**Effort:** 10 hours (includes error handling)

### 7.2 Integrate with Go Proxy Slice

**Modify:** Go proxy slice implementation

**Changes:**
- Add metadata request detection
- Add `handleMetadataRequest()` method
- Call `CooldownMetadataService.filterMetadata()`

**Effort:** 8 hours

### 7.3 Go Tests

**Create:**
- Unit tests for parser, filter, rewriter
- Integration tests with go get

**Effort:** 12 hours

**Total Phase 7:** 28 hours (2 days)

---

## Phase 8: Group Repository Support (Week 4, Day 5)

### 8.1 Group Metadata Merging

**Create:**
- `GroupMetadataMerger` - merge metadata from multiple upstreams
- Support for NPM, PyPI, Maven, Composer, Go

**Key Method:**
```java
<T> T mergeMetadata(List<T> metadataList, MetadataParser<T> parser)
```

**Effort:** 12 hours

### 8.2 Integrate with Group Slices

**Modify:** Group repository slices for each adapter

**Changes:**
- Fetch metadata from all members
- Merge metadata
- Filter merged metadata
- Serve to client

**Effort:** 8 hours

**Total Phase 8:** 20 hours (1.5 days)

---

## Phase 9: Performance Optimization (Week 5, Days 1-2)

### 9.1 Streaming Parsers

**Create:**
- `StreamingNpmMetadataFilter` - for large NPM packages
- Benchmark and compare with standard parser

**Effort:** 12 hours

### 9.2 Parallel Version Checks

**Optimize:**
- Batch database queries for version checks
- Parallel CompletableFuture execution

**Effort:** 8 hours

### 9.3 Cache Warming

**Create:**
- Cache warming on startup for popular packages
- Background refresh for frequently accessed metadata

**Effort:** 8 hours

**Total Phase 9:** 28 hours (2 days)

---

## Phase 10: Testing and Validation (Week 5, Days 3-5)

### 10.1 Integration Testing

**Test:**
- All 6 package managers with real clients
- Block/unblock scenarios
- Group repository scenarios
- Edge cases from EDGE_CASE_HANDLING.md

**Effort:** 16 hours

### 10.2 Performance Benchmarking

**Benchmark:**
- Metadata parsing latency
- Filtering latency
- End-to-end request latency
- Cache hit rates
- Memory usage

**Effort:** 12 hours

### 10.3 Documentation

**Create:**
- Operator documentation
- API documentation
- Migration guide from old approach

**Effort:** 12 hours

**Total Phase 10:** 40 hours (3 days)

---

## Summary

| Phase | Description | Effort (hours) | Duration (days) |
|-------|-------------|----------------|-----------------|
| 0 | Preparation | 12 | 1 |
| 1 | Core Infrastructure | 44 | 3 |
| 2 | NPM Adapter | 32 | 2.5 |
| 3 | PyPI Adapter | 32 | 2.5 |
| 4 | Maven Adapter | 32 | 2.5 |
| 5 | Gradle Adapter | 20 | 1.5 |
| 6 | Composer Adapter | 32 | 2.5 |
| 7 | Go Adapter | 28 | 2 |
| 8 | Group Repository | 20 | 1.5 |
| 9 | Performance | 28 | 2 |
| 10 | Testing & Docs | 40 | 3 |
| **Total** | **320 hours** | **~40 days** | **~8 weeks** |

**Assumptions:**
- 1 developer working full-time (8 hours/day)
- No major blockers or dependencies
- Existing codebase is well-structured

**Recommended Team:**
- 2 developers → 4 weeks
- 3 developers → 3 weeks

---

## Rollout Strategy

### Phase 1: Staging Deployment

**Deploy to staging environment:**
1. Deploy to staging with full monitoring enabled
2. Run automated integration tests with all 6 package managers
3. Run load tests to validate performance targets
4. Monitor for 24-48 hours
5. Fix any issues found before production deployment

**Staging validation checklist:**
- [ ] All package manager clients work correctly
- [ ] P99 latency < 200ms
- [ ] Cache hit rate > 90%
- [ ] No memory leaks
- [ ] No errors in logs
- [ ] All edge cases handled correctly

### Phase 2: Production Deployment

**Blue-Green Deployment Strategy:**

1. **Prepare Green Environment:**
   - Deploy new version with metadata filtering to green environment
   - Warm up caches with popular packages
   - Verify health checks pass

2. **Canary Testing (10% traffic):**
   - Route 10% of traffic to green environment
   - Monitor metrics for 2-4 hours:
     - Metadata filtering latency
     - Cache hit rates
     - Error rates
     - Client compatibility
   - Compare metrics between blue (old) and green (new)

3. **Gradual Traffic Shift:**
   - If canary successful, increase to 25% traffic
   - Monitor for 2 hours
   - Increase to 50% traffic
   - Monitor for 2 hours
   - Increase to 100% traffic

4. **Blue Environment Standby:**
   - Keep blue environment running for 24 hours
   - Ready for immediate rollback if issues detected

5. **Decommission Blue:**
   - After 24 hours of stable operation, decommission blue environment

**Alternative: Rolling Deployment (if blue-green not available):**

1. Deploy to one instance at a time
2. Wait 15 minutes between instances
3. Monitor each instance for errors
4. Rollback if any instance shows issues

### Phase 3: Monitoring

**Monitor:**
- Metadata filtering latency (P50, P95, P99)
- Cache hit rates
- Error rates
- Client compatibility issues

**Alerts:**
- P99 latency > 200ms
- Error rate > 1%
- Cache hit rate < 80%

### Phase 4: Rollback Plan

**If issues detected during deployment:**

**Immediate Rollback (< 5 minutes):**
1. Route 100% traffic back to blue environment (or previous version)
2. Investigate issues in green environment
3. Fix issues and re-test in staging
4. Retry deployment when fixed

**Rollback Triggers:**
- Error rate > 1%
- P99 latency > 300ms (50% above target)
- Client compatibility issues reported
- Memory leak detected
- Cache hit rate < 80%

**Post-Rollback Actions:**
1. Analyze logs and metrics to identify root cause
2. Create bug fix
3. Test fix in staging
4. Re-deploy when validated

---

## Success Criteria

1. ✅ All 6 package managers work correctly with metadata filtering
2. ✅ P99 latency < 200ms for metadata requests
3. ✅ Cache hit rate > 90%
4. ✅ No client compatibility issues
5. ✅ All edge cases handled correctly
6. ✅ Comprehensive test coverage (> 80%)
7. ✅ Documentation complete

---

## Next Steps

1. Get approval for implementation plan
2. Create feature branch
3. Start Phase 0 (Preparation)
4. Begin Phase 1 (Core Infrastructure)

