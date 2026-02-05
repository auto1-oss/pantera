# Metadata-Based Fallback Design

**Date:** November 23, 2024  
**Status:** Proposed Architecture

---

## Executive Summary

This document describes the **correct** architecture for implementing cooldown fallback in Artipie: **metadata-level version filtering** instead of download-level version substitution.

**Key Principle:** Filter blocked versions OUT of metadata responses so clients never see them, rather than trying to serve different versions than clients expect.

---

## Architecture Overview

### High-Level Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ Client Request Flow (e.g., npm install lodash@^4.17.0)         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 1. Metadata Request: GET /lodash                                │
│    Client asks: "What versions are available?"                  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. Artipie Proxy: Intercept Metadata Request                   │
│    - Detect this is a metadata request (not tarball)           │
│    - Fetch metadata from upstream or cache                     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. Parse Metadata                                               │
│    - Parse JSON/XML/HTML based on package type                 │
│    - Extract list of all versions                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. Evaluate Cooldown for Each Version                          │
│    - For each version in metadata:                             │
│      • Check if blocked (cooldown database)                    │
│      • Check if in cache (L1/L2)                               │
│    - Build list of blocked versions                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. Filter Metadata                                              │
│    - Remove blocked versions from metadata                     │
│    - Update "latest" tag if latest is blocked                  │
│    - Preserve all other metadata (hashes, URLs, etc.)          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. Rewrite Metadata                                             │
│    - Reconstruct JSON/XML/HTML with filtered versions          │
│    - Preserve format and structure                             │
│    - Add cache headers                                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 7. Serve Filtered Metadata to Client                           │
│    - Client sees only unblocked versions                       │
│    - Client selects highest unblocked version (4.17.20)        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 8. Download Request: GET /lodash/-/lodash-4.17.20.tgz          │
│    Client downloads the version it selected from metadata      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ 9. Serve Tarball (No Cooldown Check Needed)                    │
│    - Version was already allowed in metadata                   │
│    - Serve tarball normally                                    │
│    - Client verifies hash matches metadata                     │
│    - ✅ SUCCESS                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Component Design

### 1. Metadata Request Detection

**Interface:**
```java
public interface MetadataRequestDetector {
    /**
     * Determine if this request is for package metadata.
     * @param path Request path
     * @return true if metadata request, false if artifact download
     */
    boolean isMetadataRequest(String path);
    
    /**
     * Extract package name from metadata request.
     * @param path Request path
     * @return Package name or empty if not metadata request
     */
    Optional<String> extractPackageName(String path);
}
```

**Implementations:**

| Package Type | Metadata Pattern | Example |
|-------------|------------------|---------|
| NPM | `^/[^/]+$` (no slashes except leading) | `/lodash` |
| PyPI | `^/simple/[^/]+/?$` | `/simple/requests/` |
| Maven | `.*/maven-metadata\.xml$` | `/org/springframework/spring-core/maven-metadata.xml` |
| Gradle | Same as Maven | Same as Maven |
| Composer | `^/p2/[^/]+/[^/]+\.json$` | `/p2/vendor/package.json` |
| Go | `^/.+/@v/list$` | `/github.com/google/uuid/@v/list` |

### 2. Metadata Parser

**Interface:**
```java
public interface MetadataParser<T> {
    /**
     * Parse metadata from bytes.
     * @param bytes Raw metadata bytes
     * @return Parsed metadata object
     */
    T parse(byte[] bytes) throws MetadataParseException;
    
    /**
     * Extract all versions from metadata.
     * @param metadata Parsed metadata
     * @return List of all versions
     */
    List<String> extractVersions(T metadata);
    
    /**
     * Get the "latest" version tag if present.
     * @param metadata Parsed metadata
     * @return Latest version or empty
     */
    Optional<String> getLatestVersion(T metadata);
}
```

**Implementations:**

- **NpmMetadataParser:** Parse JSON, extract from `versions` object
- **PyPiMetadataParser:** Parse HTML, extract from `<a>` tags
- **MavenMetadataParser:** Parse XML, extract from `<version>` elements
- **ComposerMetadataParser:** Parse JSON, extract from `packages` object
- **GoMetadataParser:** Parse text, split by newlines

### 3. Metadata Filter

**Interface:**
```java
public interface MetadataFilter<T> {
    /**
     * Filter metadata to remove blocked versions.
     * @param metadata Parsed metadata
     * @param blockedVersions Set of versions to remove
     * @return Filtered metadata
     */
    T filter(T metadata, Set<String> blockedVersions);
    
    /**
     * Update "latest" tag if latest version is blocked.
     * @param metadata Filtered metadata
     * @param newLatest New latest version (highest unblocked)
     * @return Metadata with updated latest tag
     */
    T updateLatest(T metadata, String newLatest);
}
```

### 4. Metadata Rewriter

**Interface:**
```java
public interface MetadataRewriter<T> {
    /**
     * Serialize filtered metadata back to bytes.
     * @param metadata Filtered metadata object
     * @return Serialized bytes
     */
    byte[] rewrite(T metadata) throws MetadataRewriteException;
    
    /**
     * Get content type for metadata.
     * @return Content-Type header value
     */
    String getContentType();
}
```

### 5. Cooldown Metadata Service

**New Service:**
```java
public interface CooldownMetadataService {
    /**
     * Evaluate cooldown for all versions in metadata and return filtered metadata.
     * @param repoName Repository name
     * @param packageName Package name
     * @param metadata Raw metadata bytes
     * @param parser Parser for this package type
     * @param filter Filter for this package type
     * @param rewriter Rewriter for this package type
     * @return Filtered metadata bytes
     */
    <T> CompletableFuture<byte[]> filterMetadata(
        String repoName,
        String packageName,
        byte[] metadata,
        MetadataParser<T> parser,
        MetadataFilter<T> filter,
        MetadataRewriter<T> rewriter
    );
}
```

**Implementation:**
```java
public class JdbcCooldownMetadataService implements CooldownMetadataService {
    
    @Override
    public <T> CompletableFuture<byte[]> filterMetadata(...) {
        // 1. Parse metadata
        T parsed = parser.parse(metadata);
        List<String> allVersions = parser.extractVersions(parsed);
        
        // Preload release dates into repository-specific inspector when supported
        // (e.g. npm: MetadataAwareInspector + ReleaseDateProvider)
        preloadReleaseDatesIfSupported(repoType, packageName, parsed, parser);
        
        // 2. Evaluate cooldown for a bounded subset of the newest versions (parallel)
        List<CompletableFuture<Boolean>> checks = allVersions.stream()
            .map(version -> this.isBlocked(repoName, packageName, version))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                // 3. Collect blocked versions
                Set<String> blocked = new HashSet<>();
                for (int i = 0; i < allVersions.size(); i++) {
                    if (checks.get(i).join()) {
                        blocked.add(allVersions.get(i));
                    }
                }
                
                // 4. Filter metadata
                T filtered = filter.filter(parsed, blocked);
                
                // 5. Update latest if needed
                Optional<String> latest = parser.getLatestVersion(parsed);
                if (latest.isPresent() && blocked.contains(latest.get())) {
                    // Find new latest (highest unblocked version)
                    String newLatest = allVersions.stream()
                        .filter(v -> !blocked.contains(v))
                        .max(versionComparator)
                        .orElse(null);
                    if (newLatest != null) {
                        filtered = filter.updateLatest(filtered, newLatest);
                    }
                }
                
                // 6. Rewrite metadata
                return rewriter.rewrite(filtered);
            });
    }
}
```

---

## Integration with Proxy Slices

### NPM Proxy Slice

**Current:**
```java
// CachedNpmProxySlice.java
public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
    final String path = line.uri().getPath();
    final Key key = new KeyFromPath(path);
    
    // Check negative cache
    if (this.negativeCache.isNotFound(key)) {
        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
    }
    
    // Serve cached or fetch
    return this.fetchAndCache(line, headers, body, key);
}
```

**New (with metadata filtering):**
```java
public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
    final String path = line.uri().getPath();
    final Key key = new KeyFromPath(path);
    
    // Check if this is a metadata request
    if (this.metadataDetector.isMetadataRequest(path)) {
        return this.handleMetadataRequest(line, headers, body, path);
    }
    
    // Check negative cache for artifacts
    if (this.negativeCache.isNotFound(key)) {
        return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
    }
    
    // Serve cached artifact or fetch
    return this.fetchAndCache(line, headers, body, key);
}

private CompletableFuture<Response> handleMetadataRequest(
    RequestLine line, Headers headers, Content body, String path
) {
    final String packageName = this.metadataDetector.extractPackageName(path).orElseThrow();
    
    // Fetch metadata from upstream or cache
    return this.fetchMetadata(line, headers, body).thenCompose(metadata -> {
        // Filter metadata based on cooldown
        return this.cooldownMetadataService.filterMetadata(
            this.repoName,
            packageName,
            metadata,
            new NpmMetadataParser(),
            new NpmMetadataFilter(),
            new NpmMetadataRewriter()
        ).thenApply(filtered -> 
            ResponseBuilder.ok()
                .header("Content-Type", "application/json")
                .header("Cache-Control", "public, max-age=300")  // 5 minutes
                .body(new Content.From(filtered))
                .build()
        );
    });
}
```

---

## Caching Strategy

### Metadata Cache

**Key:** `metadata:{repo}:{package}:filtered`

**Value:** Filtered metadata bytes

**TTL:** 5-60 minutes (configurable per package type)

**Invalidation:**
- On block event: Invalidate metadata for affected package
- On unblock event: Invalidate metadata for affected package
- On manual refresh: Invalidate all metadata for repository

### Block Decision Cache

**Key:** `cooldown:{repo}:{package}:{version}:block`

**Value:** `true` (blocked) or `false` (allowed)

**TTL:** 
- Blocked: Until unblock time
- Allowed: 1 hour (configurable)

### Cache Consistency

**Problem:** Multiple instances may have different filtered metadata

**Solution:**
1. Use Valkey L2 cache for filtered metadata (shared across instances)
2. Short TTL (5-15 minutes) ensures quick convergence
3. Publish cache invalidation events on block/unblock

---

## Performance Optimization

### 1. Parallel Version Checks

```java
// Check all versions in parallel
List<CompletableFuture<Boolean>> checks = allVersions.parallelStream()
    .map(version -> this.cache.isBlocked(repo, package, version, () -> dbCheck(version)))
    .collect(Collectors.toList());
```

### 2. Batch Database Queries

```java
// Instead of N queries, use 1 query with IN clause
SELECT version, blocked FROM cooldown_blocks 
WHERE repo = ? AND package = ? AND version IN (?, ?, ?, ...)
```

### 3. Streaming Parsers

For large metadata files (NPM packages with 1000+ versions):

```java
// Use Jackson streaming API instead of loading entire JSON into memory
JsonParser parser = jsonFactory.createParser(inputStream);
while (parser.nextToken() != null) {
    if (parser.getCurrentName().equals("versions")) {
        // Process versions incrementally
    }
}
```

### 4. Metadata Cache Warming

```java
// Pre-warm cache for popular packages on startup
List<String> popularPackages = loadPopularPackages();
for (String pkg : popularPackages) {
    this.warmMetadataCache(pkg);
}
```

---

## Block/Unblock Event Handling

### Block Event

```java
public void onVersionBlocked(String repo, String pkg, String version, Instant until) {
    // 1. Update database
    this.db.insertBlock(repo, pkg, version, until);
    
    // 2. Update cache
    this.cache.putBlocked(repo, pkg, version, until);
    
    // 3. Invalidate metadata cache
    this.metadataCache.invalidate(repo, pkg);
    
    // 4. Publish event to other instances
    this.eventBus.publish(new VersionBlockedEvent(repo, pkg, version));
    
    // 5. Log for audit
    EcsLogger.info("com.artipie.cooldown")
        .message("Version blocked")
        .field("repository.name", repo)
        .field("package.name", pkg)
        .field("package.version", version)
        .field("cooldown.until", until.toString())
        .log();
}
```

### Unblock Event

```java
public void onVersionUnblocked(String repo, String pkg, String version) {
    // 1. Update database
    this.db.deleteBlock(repo, pkg, version);
    
    // 2. Update cache
    this.cache.unblock(repo, pkg, version);
    
    // 3. Invalidate metadata cache
    this.metadataCache.invalidate(repo, pkg);
    
    // 4. Publish event to other instances
    this.eventBus.publish(new VersionUnblockedEvent(repo, pkg, version));
    
    // 5. Log for audit
    EcsLogger.info("com.artipie.cooldown")
        .message("Version unblocked")
        .field("repository.name", repo)
        .field("package.name", pkg)
        .field("package.version", version)
        .log();
}
```

---

## Next Steps

1. Implement metadata parsers for each package type
2. Implement metadata filters for each package type
3. Implement metadata rewriters for each package type
4. Integrate with proxy slices
5. Add cache invalidation logic
6. Add performance benchmarks
7. Test with real package manager clients

