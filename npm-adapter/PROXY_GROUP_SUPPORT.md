# NPM Proxy & Group Repository Support

## Current Status

### ✅ **Implemented Features**

| Feature | Local/Hosted | Proxy | Group |
|---------|--------------|-------|-------|
| **Package Download** | ✅ Yes | ✅ Yes | ✅ Yes |
| **Package Upload** | ✅ Yes | ❌ No | ❌ No |
| **User Registration** | ✅ Yes | ⚠️ N/A* | ⚠️ N/A* |
| **Authentication** | ✅ Yes | ✅ Yes | ✅ Yes |
| **Search** | ✅ Yes | ⚠️ Limited** | ⚠️ Limited** |
| **Audit** | ⚠️ Stub | ⚠️ Stub | ⚠️ Stub |

*N/A: User registration only makes sense for hosted repositories
**Limited: Only searches local index, doesn't forward to upstream

---

## 🔧 **Implementation Needed**

### 1. Audit Support (Critical - Causing 405 Error)

**Current Error:**
```
POST /-/npm/v1/security/advisories/bulk → 405 Method Not Allowed
```

**Solution:** Add route to NpmSlice:

```java
// In NpmSlice routing
new RtRulePath(
    new RtRule.All(
        new RtRule.ByMethod(RqMethod.POST),
        new RtRule.ByPath("^/-/npm/v1/security/advisories/bulk$")
    ),
    new AuditSlice(upstreamUrl) // Pass upstream URL for proxy repos
)
```

**For Proxy Repositories:**
```java
// Forward audit request to upstream
public class AuditProxySlice implements Slice {
    private final HttpClient client;
    private final String upstreamUrl;
    
    @Override
    public CompletableFuture<Response> response(...) {
        return client.request(
            Request.builder()
                .method(Method.POST)
                .uri(upstreamUrl + "/-/npm/v1/security/advisories/bulk")
                .body(body)
                .headers(headers)
                .build()
        );
    }
}
```

**For Group Repositories:**
- Forward to first configured member (typically the proxy)
- Or return 501 Not Implemented

### 2. Search Support for Proxy/Group

**Current Issue:** `InMemoryPackageIndex` only contains locally published packages.

**For Proxy Repositories:**
```java
public class ProxySearchSlice implements Slice {
    private final HttpClient client;
    private final String upstreamUrl;
    private final PackageIndex localIndex;
    
    @Override
    public CompletableFuture<Response> response(...) {
        // 1. Search local cache
        return localIndex.search(query, size, from)
            .thenCompose(localResults -> {
                if (localResults.size() >= size) {
                    return CompletableFuture.completedFuture(localResults);
                }
                // 2. Forward to upstream if not enough local results
                return client.request(
                    Request.builder()
                        .method(Method.GET)
                        .uri(upstreamUrl + "/-/v1/search?text=" + query)
                        .build()
                ).thenApply(response -> {
                    // Merge local and remote results
                    return mergeResults(localResults, response);
                });
            })
            .thenApply(results -> buildSearchResponse(results));
    }
}
```

**For Group Repositories:**
```java
public class GroupSearchSlice implements Slice {
    private final List<Slice> members;
    
    @Override
    public CompletableFuture<Response> response(...) {
        // Query all members in parallel
        final List<CompletableFuture<List<PackageMetadata>>> searches = 
            members.stream()
                .map(member -> searchMember(member, query, size, from))
                .collect(Collectors.toList());
        
        // Aggregate results
        return CompletableFuture.allOf(searches.toArray(new CompletableFuture[0]))
            .thenApply(v -> searches.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .distinct() // Remove duplicates by package name
                .limit(size)
                .skip(from)
                .collect(Collectors.toList())
            )
            .thenApply(results -> buildSearchResponse(results));
    }
}
```

### 3. User Registration (Already Correct)

User registration (`adduser`) should **only** work on hosted repositories:
- ✅ **Hosted**: Allow registration
- ❌ **Proxy**: Return 403 Forbidden (can't register users on proxy)
- ❌ **Group**: Return 403 Forbidden (can't register users on group)

```java
// Check repository type before allowing registration
if (isProxyOrGroup) {
    return ResponseBuilder.forbidden()
        .jsonBody(Json.createObjectBuilder()
            .add("error", "User registration not supported on proxy/group repositories")
            .build())
        .build();
}
```

---

## 🚀 **Quick Workaround for Current Error**

Until full implementation, you can disable npm audit:

### Option 1: Per-Project
```bash
echo "audit=false" >> .npmrc
```

### Option 2: Global
```bash
npm config set audit false
```

### Option 3: Per-Command
```bash
npm install --no-audit
```

### Option 4: Add Stub Route (Recommended)

Add this to NpmSlice to return 501 instead of 405:

```java
// Temporary audit stub
new RtRulePath(
    new RtRule.All(
        new RtRule.ByMethod(RqMethod.POST),
        new RtRule.ByPath("^/-/npm/v1/security/")
    ),
    new AuditSlice() // Returns 501 Not Implemented
)
```

This tells npm "audit is not supported" rather than "method not allowed".

---

## 📋 **Implementation Priority**

### High Priority (Fix Current Error)
1. ✅ **Create AuditSlice stub** - Return 501 instead of 405
2. **Add audit route to NpmSlice** - 5 minutes

### Medium Priority (Improve Functionality)
3. **Implement AuditProxySlice** - Forward to upstream (1 hour)
4. **Implement ProxySearchSlice** - Forward search to upstream (1 hour)

### Low Priority (Nice to Have)
5. **Implement GroupSearchSlice** - Aggregate search results (2 hours)
6. **Implement full audit handling** - Parse and cache audit data (4 hours)

---

## 🔍 **Testing Proxy/Group Features**

### Test Audit (After Adding Route)
```bash
# Should return 501 with helpful message
npm audit --registry=http://localhost:8081/artifactory/api/npm/npm_group
```

### Test Search on Proxy
```bash
# Should search local cache + forward to upstream
npm search express --registry=http://localhost:8081/artifactory/api/npm/npm_proxy
```

### Test Search on Group
```bash
# Should aggregate from all members
npm search lodash --registry=http://localhost:8081/artifactory/api/npm/npm_group
```

---

## Summary

**Immediate Fix Required:**
1. Add `AuditSlice` route to stop 405 errors
2. Return 501 Not Implemented with helpful message

**Future Enhancements:**
1. Implement audit forwarding for proxy repos
2. Implement search forwarding for proxy repos
3. Implement search aggregation for group repos

The authentication features we implemented work fine globally - the issue is specifically with audit and search on proxy/group repositories.
