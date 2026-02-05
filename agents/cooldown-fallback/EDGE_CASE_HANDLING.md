# Edge Case Handling Design

**Date:** November 23, 2024  
**Purpose:** Comprehensive handling of edge cases and failure modes in metadata-based fallback

---

## 1. All Versions Blocked

### Scenario

All available versions of a package are blocked by cooldown.

**Example:**
- Package: `malicious-package`
- Versions: `1.0.0`, `1.0.1`, `1.0.2`
- All versions released within cooldown period
- All versions blocked

### Client Behavior

**NPM:**
```json
{
  "name": "malicious-package",
  "dist-tags": {},
  "versions": {},
  "time": {}
}
```
- Client sees empty `versions` object
- Error: `npm ERR! 404 Not Found - GET https://registry/malicious-package`

**PyPI:**
```html
<!DOCTYPE html>
<html>
  <body>
    <h1>Links for malicious-package</h1>
  </body>
</html>
```
- Client sees no download links
- Error: `ERROR: Could not find a version that satisfies the requirement malicious-package`

**Maven:**
```xml
<metadata>
  <groupId>com.malicious</groupId>
  <artifactId>package</artifactId>
  <versioning>
    <versions></versions>
  </versioning>
</metadata>
```
- Client sees empty `<versions>`
- Error: `Could not find artifact com.malicious:package`

### Handling Strategy

**UPDATED RECOMMENDATION: Return HTTP 403 Forbidden with Clear Error Message**

**Problem with Original Approach (Empty Metadata):**
- Returning empty metadata makes it appear the package doesn't exist
- Users cannot distinguish between "package not found" and "all versions blocked"
- Confusing because package DOES exist, versions are only temporarily blocked

**New Approach: HTTP 403 Forbidden**
- Return HTTP 403 Forbidden with clear, actionable error message
- Error message explains: package exists, all versions blocked by cooldown policy, try again later
- Semantically correct: client is not authorized to access blocked versions
- Supported by all package managers
- Users get clear explanation instead of confusing "not found" error

**See:** `ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete error handling strategy for all 6 package managers.

**Summary by Package Type:**

| Package Manager | HTTP Status | Response Format | Content-Type |
|----------------|-------------|-----------------|--------------|
| NPM | 403 Forbidden | JSON with error/reason fields | application/json |
| PyPI (PEP 503) | 403 Forbidden | HTML with error message | text/html |
| PyPI (PEP 691) | 403 Forbidden | JSON with error object | application/vnd.pypi.simple.v1+json |
| Maven | 403 Forbidden | Plain text error message | text/plain |
| Gradle | 403 Forbidden | Plain text error message | text/plain |
| Composer | 403 Forbidden | JSON with error object | application/json |
| Go | 403 Forbidden | Plain text error message | text/plain |

**Error Message Template:**
```
All versions of {package_type} '{package_name}' are currently blocked by cooldown policy.
New releases are blocked for {cooldown_period} hours to prevent supply chain attacks.
{earliest_available_message}
Please try again later or contact your administrator.
```

**Implementation:**

See `ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md` for complete implementation examples for all 6 package managers.

**Summary Code Example (NPM):**

```java
public CompletableFuture<Response> filterMetadata(
    String packageName,
    String packageType,
    JsonNode metadata,
    Set<String> blockedVersions
) {
    // Parse and filter versions
    Set<String> availableVersions = new HashSet<>();
    JsonNode versions = metadata.get("versions");

    if (versions != null) {
        versions.fieldNames().forEachRemaining(v -> {
            if (!blockedVersions.contains(v)) {
                availableVersions.add(v);
            }
        });
    }

    if (availableVersions.isEmpty()) {
        // All versions blocked - return 403 Forbidden with clear error
        return allVersionsBlockedError(packageName, packageType);
    }

    // Filter and return metadata with available versions
    return filterAndRewrite(metadata, availableVersions);
}

private CompletableFuture<Response> allVersionsBlockedError(
    String packageName,
    String packageType
) {
    long cooldownHours = config.getCooldownPeriod().toHours();

    String errorMessage = String.format(
        "All versions of package '%s' are currently blocked by cooldown policy. " +
        "New releases are blocked for %d hours to prevent supply chain attacks. " +
        "Please try again later or contact your administrator.",
        packageName, cooldownHours
    );

    // Log the event
    EcsLogger.warn("com.artipie.cooldown")
        .message("All versions blocked - returning 403 Forbidden")
        .eventCategory("cooldown")
        .eventAction("all_versions_blocked")
        .eventOutcome("failure")
        .field("package.name", packageName)
        .field("package.type", packageType)
        .field("http.response.status_code", 403)
        .log();

    // Return JSON error for NPM/Composer, plain text for Maven/Gradle/Go
    if ("npm".equals(packageType) || "composer".equals(packageType)) {
        JsonObject error = Json.createObjectBuilder()
            .add("error", "Forbidden")
            .add("reason", errorMessage)
            .build();

        return CompletableFuture.completedFuture(
            new RsWithStatus(
                new RsWithHeaders(
                    new RsWithBody(error.toString(), StandardCharsets.UTF_8),
                    new Header("Content-Type", "application/json"),
                    new Header("X-Artipie-Cooldown-Blocked", "true")
                ),
                RsStatus.FORBIDDEN
            )
        );
    } else {
        return CompletableFuture.completedFuture(
            new RsWithStatus(
                new RsWithHeaders(
                    new RsWithBody(errorMessage, StandardCharsets.UTF_8),
                    new Header("Content-Type", "text/plain"),
                    new Header("X-Artipie-Cooldown-Blocked", "true")
                ),
                RsStatus.FORBIDDEN
            )
        );
    }
}
```

**Expected User Experience:**

```bash
# NPM
$ npm install lodash
npm ERR! code E403
npm ERR! 403 Forbidden - GET http://artipie.local/npm/lodash
npm ERR! 403 All versions of package 'lodash' are currently blocked by cooldown policy.
npm ERR! 403 New releases are blocked for 72 hours to prevent supply chain attacks.
npm ERR! 403 Please try again later or contact your administrator.

# PyPI
$ pip install requests
ERROR: HTTP error 403 while getting https://artipie.local/simple/requests/
ERROR: All versions of package 'requests' are currently blocked by cooldown policy.

# Maven
$ mvn install
[ERROR] Could not transfer artifact com.example:my-library:pom:1.0.0: status code 403
[ERROR] All versions of artifact 'com.example:my-library' are currently blocked by cooldown policy.
```

---

## 2. Latest Version Blocked

### Scenario

The latest version is blocked, but older versions are available.

**Example:**
- Package: `lodash`
- Latest: `4.17.21` (blocked)
- Available: `4.17.20`, `4.17.19`, ...

### Client Behavior

**NPM with `^4.17.0`:**
- Selects `4.17.20` (highest unblocked)
- ✅ Works correctly

**NPM with `latest` tag:**
- Expects `dist-tags.latest` to point to highest available
- If we don't update `latest`, client may fail

### Handling Strategy

**Update `dist-tags.latest` to highest unblocked version:**

```java
public JsonNode updateLatest(JsonNode metadata, String newLatest) {
    ObjectNode root = (ObjectNode) metadata;
    ObjectNode distTags = (ObjectNode) root.get("dist-tags");
    if (distTags != null) {
        distTags.put("latest", newLatest);
        
        // Also update other tags that point to blocked version
        Iterator<Map.Entry<String, JsonNode>> fields = distTags.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String tag = entry.getKey();
            String version = entry.getValue().asText();
            if (blockedVersions.contains(version)) {
                // Update tag to point to newLatest
                distTags.put(tag, newLatest);
            }
        }
    }
    return root;
}
```

**Log the change:**

```java
EcsLogger.info("com.artipie.cooldown")
    .message("Latest version blocked, updated dist-tags")
    .field("repository.name", repoName)
    .field("package.name", packageName)
    .field("package.version.blocked", blockedLatest)
    .field("package.version.new_latest", newLatest)
    .log();
```

---

## 3. Metadata Parse Failure

### Scenario

Upstream metadata is malformed or corrupted.

**Example:**
- Upstream returns invalid JSON
- Upstream returns truncated XML
- Upstream returns HTML error page instead of metadata

### Handling Strategy

**Fail fast and return error to client:**

```java
public <T> CompletableFuture<byte[]> filterMetadata(...) {
    try {
        T parsed = parser.parse(metadata);
        // ... continue filtering ...
    } catch (MetadataParseException e) {
        EcsLogger.error("com.artipie.cooldown")
            .message("Failed to parse metadata from upstream")
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("error.type", e.getClass().getName())
            .field("error.message", e.getMessage())
            .field("error.stack_trace", getStackTrace(e))
            .log();
        
        // Return 502 Bad Gateway (upstream sent invalid data)
        throw new UpstreamMetadataException("Upstream metadata is invalid", e);
    }
}
```

**Don't cache failed parses:**

```java
// Only cache successfully filtered metadata
if (parseSuccessful) {
    this.metadataCache.put(cacheKey, filtered, ttl);
}
```

---

## 4. Upstream Timeout

### Scenario

Upstream registry is slow or unresponsive.

**Example:**
- npmjs.org takes > 30 seconds to respond
- Connection timeout
- Read timeout

### Handling Strategy

**Set reasonable timeouts:**

```java
HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();

HttpRequest request = HttpRequest.newBuilder()
    .uri(upstreamUri)
    .timeout(Duration.ofSeconds(30))
    .build();
```

**Return cached metadata if available:**

```java
public CompletableFuture<byte[]> fetchMetadata(...) {
    return this.fetchFromUpstream(uri)
        .exceptionally(ex -> {
            if (ex instanceof TimeoutException) {
                EcsLogger.warn("com.artipie.cooldown")
                    .message("Upstream timeout, checking cache")
                    .field("repository.name", repoName)
                    .field("package.name", packageName)
                    .field("url.original", uri.toString())
                    .log();
                
                // Try to serve stale cache
                byte[] stale = this.metadataCache.getStale(cacheKey);
                if (stale != null) {
                    EcsLogger.info("com.artipie.cooldown")
                        .message("Serving stale metadata due to upstream timeout")
                        .field("repository.name", repoName)
                        .field("package.name", packageName)
                        .log();
                    return stale;
                }
            }
            throw new CompletionException(ex);
        });
}
```

---

## 5. Group Repository with Multiple Upstreams

### Scenario

Group repository aggregates multiple proxy repositories.

**Example:**
- Group: `npm-group`
- Members: `npm-proxy-1` (npmjs.org), `npm-proxy-2` (internal registry)
- Package exists in both upstreams with different versions

### Handling Strategy

**Merge metadata from all upstreams:**

```java
public CompletableFuture<byte[]> filterGroupMetadata(
    String groupName,
    String packageName,
    List<String> memberRepos
) {
    // Fetch metadata from all members in parallel
    List<CompletableFuture<JsonNode>> fetches = memberRepos.stream()
        .map(repo -> this.fetchAndParseMetadata(repo, packageName))
        .collect(Collectors.toList());
    
    return CompletableFuture.allOf(fetches.toArray(new CompletableFuture[0]))
        .thenApply(v -> {
            // Merge all metadata
            JsonNode merged = this.mergeMetadata(
                fetches.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            );
            
            // Filter merged metadata
            return this.filterMetadata(groupName, packageName, merged);
        });
}

private JsonNode mergeMetadata(List<JsonNode> metadataList) {
    ObjectNode merged = JsonNodeFactory.instance.objectNode();
    
    // Merge versions from all sources
    ObjectNode versions = merged.putObject("versions");
    ObjectNode time = merged.putObject("time");
    
    for (JsonNode metadata : metadataList) {
        JsonNode versionsNode = metadata.get("versions");
        if (versionsNode != null) {
            versionsNode.fields().forEachRemaining(entry -> {
                versions.set(entry.getKey(), entry.getValue());
            });
        }
        
        JsonNode timeNode = metadata.get("time");
        if (timeNode != null) {
            timeNode.fields().forEachRemaining(entry -> {
                time.set(entry.getKey(), entry.getValue());
            });
        }
    }
    
    // Set latest to highest version across all sources
    String latest = versions.fieldNames().stream()
        .max(versionComparator)
        .orElse(null);
    if (latest != null) {
        merged.putObject("dist-tags").put("latest", latest);
    }
    
    return merged;
}
```

---

## 6. Version Unblocked During Client Request

### Scenario

Version is unblocked between metadata fetch and download.

**Timeline:**
1. Client fetches metadata (version X is blocked, not in metadata)
2. Admin unblocks version X
3. Client tries to download version Y (selected from filtered metadata)
4. Another client fetches metadata (version X now appears)

### Handling Strategy

**This is acceptable behavior:**
- Metadata has short TTL (5-15 minutes)
- Clients will see unblocked version on next metadata refresh
- No data corruption or errors

**Log the unblock event:**

```java
public void onVersionUnblocked(String repo, String pkg, String version) {
    // ... update database and cache ...
    
    // Invalidate metadata cache
    String cacheKey = String.format("metadata:%s:%s:filtered", repo, pkg);
    this.metadataCache.invalidate(cacheKey);
    
    EcsLogger.info("com.artipie.cooldown")
        .message("Version unblocked, metadata cache invalidated")
        .field("repository.name", repo)
        .field("package.name", pkg)
        .field("package.version", version)
        .log();
}
```

---

## 7. Metadata Too Large

### Scenario

Metadata file is extremely large (> 10 MB).

**Example:**
- NPM package with 5000+ versions
- Metadata JSON is 15 MB

### Handling Strategy

**Option 1: Use streaming parser (recommended for NPM)**

```java
if (metadata.length > 10 * 1024 * 1024) {  // > 10 MB
    // Use streaming parser
    return this.filterLargeMetadata(metadata, blockedVersions);
}
```

**Option 2: Reject with 413 Payload Too Large**

```java
if (metadata.length > 50 * 1024 * 1024) {  // > 50 MB
    EcsLogger.error("com.artipie.cooldown")
        .message("Metadata too large to process")
        .field("repository.name", repoName)
        .field("package.name", packageName)
        .field("http.response.body.bytes", metadata.length)
        .log();
    
    throw new MetadataTooLargeException("Metadata exceeds 50 MB limit");
}
```

---

## 8. Concurrent Block/Unblock Operations

### Scenario

Multiple admins block/unblock versions simultaneously.

**Example:**
1. Admin A blocks version 1.0.0
2. Admin B unblocks version 1.0.0 (at same time)
3. Which operation wins?

### Handling Strategy

**Use database transactions with optimistic locking:**

```java
@Transactional
public void blockVersion(String repo, String pkg, String version, Instant until) {
    // Check current state
    Optional<CooldownBlock> existing = this.db.findBlock(repo, pkg, version);
    
    if (existing.isPresent()) {
        // Already blocked - update until time if later
        if (until.isAfter(existing.get().getUntil())) {
            this.db.updateBlock(repo, pkg, version, until);
        }
    } else {
        // Insert new block
        this.db.insertBlock(repo, pkg, version, until);
    }
    
    // Invalidate cache
    this.cache.invalidate(repo, pkg, version);
    this.metadataCache.invalidate(repo, pkg);
}

@Transactional
public void unblockVersion(String repo, String pkg, String version) {
    // Delete block
    this.db.deleteBlock(repo, pkg, version);
    
    // Invalidate cache
    this.cache.invalidate(repo, pkg, version);
    this.metadataCache.invalidate(repo, pkg);
}
```

**Log all operations:**

```java
EcsLogger.info("com.artipie.cooldown")
    .message("Version block state changed")
    .field("repository.name", repo)
    .field("package.name", pkg)
    .field("package.version", version)
    .field("cooldown.action", "block")  // or "unblock"
    .field("user.name", adminUser)
    .field("event.created", Instant.now().toString())
    .log();
```

---

## 9. Cache Inconsistency Across Instances

### Scenario

Multiple Artipie instances with different cached metadata.

**Example:**
1. Instance A caches filtered metadata (version X blocked)
2. Admin unblocks version X
3. Instance B fetches fresh metadata (version X unblocked)
4. Clients see different metadata depending on which instance they hit

### Handling Strategy

**Use shared L2 cache (Valkey/Redis):**

```java
public CompletableFuture<byte[]> getFilteredMetadata(String repo, String pkg) {
    String cacheKey = String.format("metadata:%s:%s:filtered", repo, pkg);
    
    // Check L1 (Caffeine - local)
    byte[] l1 = this.l1Cache.get(cacheKey);
    if (l1 != null) {
        return CompletableFuture.completedFuture(l1);
    }
    
    // Check L2 (Valkey - shared)
    return this.l2Cache.get(cacheKey).thenCompose(l2 -> {
        if (l2 != null) {
            // Populate L1
            this.l1Cache.put(cacheKey, l2);
            return CompletableFuture.completedFuture(l2);
        }
        
        // Fetch and filter from upstream
        return this.fetchAndFilter(repo, pkg).thenApply(filtered -> {
            // Populate L2 and L1
            this.l2Cache.put(cacheKey, filtered, Duration.ofMinutes(15));
            this.l1Cache.put(cacheKey, filtered);
            return filtered;
        });
    });
}
```

**Publish cache invalidation events:**

```java
public void onVersionUnblocked(String repo, String pkg, String version) {
    // ... update database ...
    
    // Publish event to all instances
    this.eventBus.publish(new CacheInvalidationEvent(repo, pkg));
}

@Subscribe
public void onCacheInvalidation(CacheInvalidationEvent event) {
    String cacheKey = String.format("metadata:%s:%s:filtered", event.repo, event.pkg);
    
    // Invalidate L1 (local)
    this.l1Cache.invalidate(cacheKey);
    
    // Invalidate L2 (shared)
    this.l2Cache.invalidate(cacheKey);
    
    EcsLogger.debug("com.artipie.cooldown")
        .message("Metadata cache invalidated")
        .field("repository.name", event.repo)
        .field("package.name", event.pkg)
        .log();
}
```

---

## 10. Malformed Version Strings

### Scenario

Package has non-standard version strings.

**Example:**
- NPM: `1.0.0-beta.1+build.123`
- Maven: `1.0-SNAPSHOT`
- PyPI: `2.0.0rc1`

### Handling Strategy

**Use package-type-specific version comparators:**

```java
// NPM - use semver library
Comparator<String> npmComparator = (v1, v2) -> {
    try {
        return Semver.parse(v1).compareTo(Semver.parse(v2));
    } catch (Exception e) {
        // Fallback to string comparison
        return v1.compareTo(v2);
    }
};

// Maven - use Maven version comparator
Comparator<String> mavenComparator = (v1, v2) -> {
    return new ComparableVersion(v1).compareTo(new ComparableVersion(v2));
};

// PyPI - use PEP 440 version comparator
Comparator<String> pypiComparator = (v1, v2) -> {
    return PEP440Version.parse(v1).compareTo(PEP440Version.parse(v2));
};
```

**Handle parse failures gracefully:**

```java
try {
    String latest = versions.stream()
        .max(versionComparator)
        .orElse(null);
} catch (Exception e) {
    EcsLogger.warn("com.artipie.cooldown")
        .message("Failed to compare versions, using string comparison")
        .field("repository.name", repo)
        .field("package.name", pkg)
        .field("error.message", e.getMessage())
        .log();
    
    // Fallback to lexicographic comparison
    String latest = versions.stream()
        .max(String::compareTo)
        .orElse(null);
}
```

---

## Summary

| Edge Case | Handling Strategy | Impact |
|-----------|------------------|--------|
| All versions blocked | Return empty metadata | Client sees "not found" error |
| Latest version blocked | Update dist-tags to new latest | Client selects highest unblocked |
| Metadata parse failure | Return 502 Bad Gateway | Client retries or fails |
| Upstream timeout | Serve stale cache if available | Degraded but functional |
| Group repository | Merge metadata from all members | Clients see all available versions |
| Version unblocked during request | Cache invalidation + short TTL | Eventually consistent |
| Metadata too large | Use streaming parser or reject | Prevents OOM |
| Concurrent block/unblock | Database transactions | Last write wins |
| Cache inconsistency | Shared L2 cache + event bus | Eventually consistent |
| Malformed versions | Package-specific comparators | Graceful degradation |

---

## Next Steps

1. Implement edge case handling in metadata service
2. Add comprehensive error logging
3. Add integration tests for each edge case
4. Document expected behavior for operators

