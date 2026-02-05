# Analysis of Current Fallback Implementation

**Date:** November 23, 2024  
**Status:** Critical Issues Identified - Redesign Required

---

## Executive Summary

The current cooldown-fallback design documented in `/docs/cooldown-fallback/` has **fundamental architectural flaws** that will cause it to **fail with most package manager clients**. The approach of serving a different version than requested at the download level violates client expectations and will break dependency resolution, integrity checks, and caching mechanisms.

**Critical Finding:** The current design attempts to transparently serve fallback versions by rewriting download URLs (e.g., serving `lodash-4.17.20.tgz` when client requests `lodash-4.17.21.tgz`). This approach is **fundamentally incompatible** with how package managers work.

---

## Critical Architectural Flaws

### 1. **Download-Level Fallback Violates Client Expectations**

**Current Approach (BROKEN):**
```java
// From docs/cooldown-fallback/03_IMPLEMENTATION_PLAN.md, line 120-130
if (result.blocked()) {
    Optional<String> fallback = this.cache.getFallback(
        this.repoName, request.get().artifact(), request.get().version()
    );
    if (fallback.isPresent()) {
        String fallbackPath = tgz.replace("-" + request.get().version() + ".tgz",
            "-" + fallback.get() + ".tgz");
        return this.serveAsset(fallbackPath, rqheaders);  // ❌ WRONG
    }
}
```

**Why This Fails:**

1. **NPM Client Behavior:**
   - Client requests `lodash@4.17.21` based on metadata that lists this version
   - Client expects tarball with `package.json` containing `"version": "4.17.21"`
   - Server serves `lodash-4.17.20.tgz` with `"version": "4.17.20"` inside
   - **Result:** Version mismatch error, integrity check failure, corrupted `package-lock.json`

2. **PyPI Client Behavior:**
   - Client requests `requests-2.28.0.tar.gz` with expected SHA256 hash from metadata
   - Server serves `requests-2.27.1.tar.gz` with different hash
   - **Result:** Hash verification failure, installation aborted

3. **Maven/Gradle Client Behavior:**
   - Client requests `artifact-1.2.0.jar` based on POM dependency
   - Server serves `artifact-1.1.0.jar` with different internal manifest
   - **Result:** ClassNotFoundException, version conflict errors at runtime

4. **Composer Client Behavior:**
   - Client requests `vendor/package:2.0.0` based on `composer.json`
   - Server serves `2.0.0.zip` containing `composer.json` with `"version": "1.9.0"`
   - **Result:** Composer detects version mismatch, installation fails

5. **Go Client Behavior:**
   - Client requests `v1.5.0.zip` based on `go.mod` requirement
   - Server serves `v1.4.0.zip` with different module version
   - **Result:** `go.mod` verification failure, build breaks

### 2. **Metadata-Download Inconsistency**

**The Root Problem:**

All package managers follow a **two-phase resolution process**:

```
Phase 1: Fetch Metadata → Parse available versions → Select version
Phase 2: Download selected version → Verify integrity → Install
```

The current design only intervenes at **Phase 2** (download), but the client has already made decisions based on **Phase 1** (metadata). This creates an **irreconcilable inconsistency**.

**Example Flow (NPM):**

```
1. Client: GET /lodash (metadata request)
2. Server: Returns metadata with versions: ["4.17.21", "4.17.20", "4.17.19", ...]
3. Client: Selects 4.17.21 (latest), updates package-lock.json
4. Client: GET /lodash/-/lodash-4.17.21.tgz (download request)
5. Server: ❌ Serves lodash-4.17.20.tgz (fallback)
6. Client: ❌ Extracts tarball, finds "version": "4.17.20" in package.json
7. Client: ❌ ERROR: Version mismatch (expected 4.17.21, got 4.17.20)
```

**Correct Flow (Metadata-Based):**

```
1. Client: GET /lodash (metadata request)
2. Server: ✅ Returns FILTERED metadata with versions: ["4.17.20", "4.17.19", ...]
           (4.17.21 is REMOVED from metadata because it's blocked)
3. Client: Selects 4.17.20 (latest available), updates package-lock.json
4. Client: GET /lodash/-/lodash-4.17.20.tgz (download request)
5. Server: ✅ Serves lodash-4.17.20.tgz
6. Client: ✅ Extracts tarball, finds "version": "4.17.20" in package.json
7. Client: ✅ SUCCESS: Version matches expectation
```

### 3. **Integrity Verification Failures**

**All modern package managers verify integrity:**

| Package Manager | Integrity Mechanism | Failure Mode |
|----------------|---------------------|--------------|
| **NPM** | SHA512 in `package-lock.json` | `npm ERR! Integrity check failed` |
| **PyPI** | SHA256 in `--hash` or metadata | `ERROR: THESE PACKAGES DO NOT MATCH THE HASHES` |
| **Maven** | SHA1/MD5 checksum files | `Could not verify artifact checksum` |
| **Gradle** | SHA256/SHA512 verification | `Artifact verification failed` |
| **Composer** | SHA256 in `composer.lock` | `The checksum verification failed` |
| **Go** | `go.sum` hash verification | `verifying module: checksum mismatch` |

**Current design cannot pass integrity checks** because:
- Metadata lists hash for version X
- Server serves tarball for version Y
- Hash(X) ≠ Hash(Y)
- **Result:** Installation fails

---

## Why Metadata-Based Filtering is the ONLY Solution

### Correct Architecture

**Metadata Filtering Approach:**

1. **Intercept metadata requests** (not download requests)
2. **Parse metadata** (JSON/XML depending on package type)
3. **Filter out blocked versions** from version lists
4. **Rewrite metadata** to exclude blocked versions
5. **Serve filtered metadata** to client
6. **Client selects from available (unblocked) versions**
7. **Download proceeds normally** with correct version

**Implementation Points:**

| Package Manager | Metadata Endpoint | Format | Filtering Strategy |
|----------------|-------------------|--------|-------------------|
| **NPM** | `GET /{package}` | JSON | Remove versions from `versions` object and `time` object |
| **PyPI** | `GET /simple/{package}/` | HTML | Remove `<a>` tags for blocked versions |
| **Maven** | `GET /{path}/maven-metadata.xml` | XML | Remove `<version>` elements from `<versions>` |
| **Gradle** | Same as Maven | XML | Same as Maven (uses Maven format) |
| **Composer** | `GET /p2/{vendor}/{package}.json` | JSON | Remove versions from `packages` object |
| **Go** | `GET /{module}/@v/list` | Text | Remove lines for blocked versions |

---

## Performance Implications of Metadata Filtering

### Current Design Performance Claims (INVALID)

The current design claims:
- "P99 latency < 200ms" ✅
- "Minimal overhead" ✅
- "Cache hit rate > 90%" ✅

**These claims are IRRELEVANT** because the design doesn't work.

### Metadata-Based Design Performance (REALISTIC)

**Overhead Sources:**

1. **Metadata Parsing:**
   - NPM: Parse 10KB-5MB JSON (1-50ms)
   - PyPI: Parse HTML index (5-20ms)
   - Maven: Parse XML (1-10ms)
   - Composer: Parse JSON (1-20ms)
   - Go: Parse text list (< 1ms)

2. **Version Filtering:**
   - Check each version against cooldown database
   - O(n) where n = number of versions
   - Typical: 10-100 versions per package
   - Cost: 1-10ms with caching

3. **Metadata Rewriting:**
   - Reconstruct JSON/XML/HTML without blocked versions
   - Cost: 1-10ms

**Total Overhead:** 5-80ms per metadata request (acceptable)

**Caching Strategy:**
- Cache filtered metadata for 1 hour
- Invalidate on block/unblock events
- **Cache hit rate:** > 95% (metadata requests are less frequent than downloads)

---

## Missing Components in Current Design

### 1. **No Metadata Interception**

Current design only intercepts **download requests**, not **metadata requests**.

**Required Changes:**
- Add metadata request detection to all proxy slices
- Implement metadata parsing for each package type
- Implement metadata filtering logic
- Implement metadata rewriting logic

### 2. **No Block/Unblock Metadata Synchronization**

When a version is blocked or unblocked, the metadata cache must be invalidated.

**Current Design:** No mechanism for this
**Required:** Event-driven cache invalidation

### 3. **No Streaming Metadata Parsing**

Large metadata files (NPM packages with 1000+ versions) can be 5-10MB.

**Current Design:** No consideration for memory usage
**Required:** Streaming JSON/XML parsers to avoid loading entire file into memory

### 4. **No Group Repository Support**

Group repositories aggregate multiple proxy repositories.

**Current Design:** No mention of how filtering works across multiple upstreams
**Required:** Merge and filter metadata from all group members

---

## Compatibility Issues with Package Manager Clients

### NPM Client Issues

**Problem:** NPM caches metadata aggressively
- Metadata cached in `~/.npm/_cacache`
- Cache invalidation based on `Cache-Control` headers
- If server serves inconsistent metadata, client cache becomes corrupted

**Solution:** Serve consistent `Cache-Control` headers with filtered metadata

### PyPI Client Issues

**Problem:** pip uses `--hash` mode for security
- `pip install --require-hashes` fails if hash doesn't match
- Hash is computed from downloaded file, not metadata

**Solution:** Filter metadata to exclude blocked versions BEFORE client sees them

### Maven/Gradle Client Issues

**Problem:** Maven Central metadata is immutable
- Clients expect `maven-metadata.xml` to be stable
- Changing metadata breaks reproducible builds

**Solution:** Use short TTL for filtered metadata, document non-reproducibility during cooldown

### Composer Client Issues

**Problem:** Composer uses `composer.lock` for exact version pinning
- Lock file contains exact version and hash
- Changing version breaks lock file

**Solution:** Filter metadata so client never sees blocked versions

### Go Client Issues

**Problem:** Go uses `go.sum` for cryptographic verification
- Sum file contains hash of every module version
- Changing version breaks sum verification

**Solution:** Filter `@v/list` endpoint to exclude blocked versions

---

## Conclusion

**The current cooldown-fallback design is fundamentally flawed and must be completely redesigned.**

**Required Changes:**

1. ✅ **Abandon download-level fallback** (current approach)
2. ✅ **Implement metadata-level filtering** (correct approach)
3. ✅ **Add metadata parsing for all 6 package types**
4. ✅ **Add metadata rewriting for all 6 package types**
5. ✅ **Add cache invalidation on block/unblock events**
6. ✅ **Add streaming parsers for large metadata files**
7. ✅ **Add group repository metadata merging**
8. ✅ **Add comprehensive client compatibility testing**

**Next Steps:**

1. Research package manager client behavior (detailed analysis)
2. Design metadata-based filtering architecture
3. Select appropriate parsers (JSON/XML/HTML)
4. Implement metadata filtering for each package type
5. Test with real package manager clients
6. Benchmark performance impact

