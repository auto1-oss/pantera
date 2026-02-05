# All Versions Blocked - Error Handling Strategy

**Date:** November 23, 2024  
**Purpose:** Define user-visible error responses when all versions of a package are blocked by cooldown policy

---

## Problem Statement

When all versions of a package are blocked by cooldown policy, returning empty metadata makes it appear to users as if the package doesn't exist. This is confusing because:

1. The package DOES exist in the upstream registry
2. Versions are only temporarily blocked (will become available after cooldown period)
3. Users cannot distinguish between "package not found" and "all versions blocked"

**Goal:** Provide clear, actionable error messages that communicate "this package exists, but all versions are currently blocked by cooldown policy."

---

## Package Manager Error Handling Research

### NPM (npm CLI)

**Metadata Endpoint:** `GET /{package}`

**Error Response Options:**

1. **HTTP 403 Forbidden** (RECOMMENDED)
   ```json
   {
     "error": "Forbidden",
     "reason": "All versions of this package are currently blocked by cooldown policy. New releases are blocked for 72 hours to prevent supply chain attacks. Please try again later or contact your administrator."
   }
   ```
   
   **Client Behavior:**
   - npm displays the error message from the `reason` field
   - Exit code: 1
   - User sees: `npm ERR! 403 Forbidden - GET http://artipie.local/npm/lodash`
   - User sees: `npm ERR! 403 All versions of this package are currently blocked by cooldown policy...`

2. **HTTP 404 with Custom Headers** (ALTERNATIVE)
   ```
   HTTP/1.1 404 Not Found
   X-Artipie-Cooldown-Blocked: true
   X-Artipie-Cooldown-Reason: All versions blocked by cooldown policy
   X-Artipie-Cooldown-Retry-After: 259200
   
   {
     "error": "Not Found",
     "reason": "All versions of this package are currently blocked by cooldown policy (72 hour cooldown for new releases). Earliest version will be available in approximately 71 hours. Please try again later."
   }
   ```
   
   **Client Behavior:**
   - npm displays "404 Not Found" but includes the reason
   - Custom headers are not displayed to users but can be logged
   - Less clear than 403

**Recommendation:** **HTTP 403 Forbidden** with descriptive JSON error message

**Example User Experience:**
```bash
$ npm install lodash
npm ERR! code E403
npm ERR! 403 Forbidden - GET http://artipie.local/npm/lodash
npm ERR! 403 All versions of this package are currently blocked by cooldown policy. 
npm ERR! 403 New releases are blocked for 72 hours to prevent supply chain attacks. 
npm ERR! 403 Please try again later or contact your administrator.
```

---

### PyPI (pip)

**Metadata Endpoint:** `GET /simple/{package}/` (HTML) or `GET /simple/{package}/` (JSON with PEP 691)

**Error Response Options:**

1. **HTTP 403 Forbidden with HTML** (RECOMMENDED for PEP 503)
   ```html
   <!DOCTYPE html>
   <html>
     <head><title>403 Forbidden</title></head>
     <body>
       <h1>403 Forbidden</h1>
       <p>All versions of package '<strong>requests</strong>' are currently blocked by cooldown policy.</p>
       <p>New releases are blocked for 72 hours to prevent supply chain attacks.</p>
       <p>Please try again later or contact your administrator.</p>
       <p>Earliest version will be available in approximately 71 hours.</p>
     </body>
   </html>
   ```

2. **HTTP 403 Forbidden with JSON** (RECOMMENDED for PEP 691)
   ```json
   {
     "meta": {
       "api-version": "1.0"
     },
     "error": {
       "code": "cooldown_blocked",
       "message": "All versions of this package are currently blocked by cooldown policy. New releases are blocked for 72 hours to prevent supply chain attacks. Please try again later or contact your administrator.",
       "retry_after": 259200
     }
   }
   ```

**Client Behavior:**
- pip displays: `ERROR: HTTP error 403 while getting https://artipie.local/simple/requests/`
- pip may display HTML content if it's simple enough
- JSON errors are parsed and displayed

**Recommendation:** **HTTP 403 Forbidden** with HTML (PEP 503) or JSON (PEP 691)

**Example User Experience:**
```bash
$ pip install requests
ERROR: HTTP error 403 while getting https://artipie.local/simple/requests/
ERROR: All versions of package 'requests' are currently blocked by cooldown policy.
ERROR: New releases are blocked for 72 hours to prevent supply chain attacks.
ERROR: Please try again later or contact your administrator.
```

---

### Maven (mvn)

**Metadata Endpoint:** `GET /{group}/{artifact}/maven-metadata.xml`

**Error Response Options:**

1. **HTTP 403 Forbidden with XML** (RECOMMENDED)
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <error>
     <code>403</code>
     <message>All versions of this artifact are currently blocked by cooldown policy. New releases are blocked for 72 hours to prevent supply chain attacks. Please try again later or contact your administrator.</message>
     <artifact>com.example:my-library</artifact>
     <retryAfter>259200</retryAfter>
   </error>
   ```

2. **HTTP 403 Forbidden with Plain Text**
   ```
   HTTP/1.1 403 Forbidden
   Content-Type: text/plain
   
   All versions of artifact 'com.example:my-library' are currently blocked by cooldown policy.
   New releases are blocked for 72 hours to prevent supply chain attacks.
   Please try again later or contact your administrator.
   ```

**Client Behavior:**
- Maven displays: `[ERROR] Failed to read artifact descriptor for com.example:my-library:jar:1.0.0`
- Maven displays: `[ERROR] Could not transfer artifact ... from/to artipie (...): status code: 403, reason phrase: Forbidden`
- Maven may display response body if it's text

**Recommendation:** **HTTP 403 Forbidden** with plain text message

**Example User Experience:**
```bash
$ mvn install
[ERROR] Failed to read artifact descriptor for com.example:my-library:jar:1.0.0
[ERROR] Could not transfer artifact com.example:my-library:pom:1.0.0 from/to artipie (http://artipie.local/maven): status code: 403, reason phrase: Forbidden
[ERROR] All versions of artifact 'com.example:my-library' are currently blocked by cooldown policy.
[ERROR] New releases are blocked for 72 hours to prevent supply chain attacks.
```

---

### Gradle

**Metadata Endpoint:** `GET /{group}/{artifact}/maven-metadata.xml` (Maven format)

**Error Response:** Same as Maven (Gradle uses Maven repository format)

**Recommendation:** **HTTP 403 Forbidden** with plain text message

**Example User Experience:**
```bash
$ gradle build
> Could not resolve com.example:my-library:1.0.0.
   > Could not get resource 'http://artipie.local/maven/com/example/my-library/maven-metadata.xml'.
      > Could not GET 'http://artipie.local/maven/com/example/my-library/maven-metadata.xml'. Received status code 403 from server: Forbidden
      > All versions of artifact 'com.example:my-library' are currently blocked by cooldown policy.
```

---

### Composer (PHP)

**Metadata Endpoint:** `GET /p2/{vendor}/{package}.json`

**Error Response Options:**

1. **HTTP 403 Forbidden with JSON** (RECOMMENDED)
   ```json
   {
     "error": {
       "code": "cooldown_blocked",
       "message": "All versions of package 'vendor/package' are currently blocked by cooldown policy. New releases are blocked for 72 hours to prevent supply chain attacks. Please try again later or contact your administrator.",
       "package": "vendor/package",
       "retryAfter": 259200
     }
   }
   ```

**Client Behavior:**
- Composer displays: `[Composer\Downloader\TransportException]`
- Composer displays: `The "http://artipie.local/p2/vendor/package.json" file could not be downloaded (HTTP/1.1 403 Forbidden)`
- Composer may parse and display JSON error message

**Recommendation:** **HTTP 403 Forbidden** with JSON error

**Example User Experience:**
```bash
$ composer require vendor/package
  [Composer\Downloader\TransportException]
  The "http://artipie.local/p2/vendor/package.json" file could not be downloaded (HTTP/1.1 403 Forbidden)
  All versions of package 'vendor/package' are currently blocked by cooldown policy.
  New releases are blocked for 72 hours to prevent supply chain attacks.
```

---

### Go (go get)

**Metadata Endpoint:** `GET /{module}/@v/list` (text list of versions)

**Error Response Options:**

1. **HTTP 410 Gone** (RECOMMENDED per Go module proxy protocol)
   ```
   HTTP/1.1 410 Gone
   Content-Type: text/plain
   
   All versions of module 'example.com/module' are currently blocked by cooldown policy.
   New releases are blocked for 72 hours to prevent supply chain attacks.
   Please try again later or contact your administrator.
   ```
   
   **Per Go specification:** 
   - 410 Gone indicates the module is not available and will not become available
   - However, this is semantically incorrect for temporary blocks

2. **HTTP 403 Forbidden** (ALTERNATIVE - more semantically correct)
   ```
   HTTP/1.1 403 Forbidden
   Content-Type: text/plain
   
   All versions of module 'example.com/module' are currently blocked by cooldown policy.
   New releases are blocked for 72 hours to prevent supply chain attacks.
   Please try again later or contact your administrator.
   ```

**Client Behavior:**
- `go get` displays: `go: example.com/module@latest: reading https://proxy.local/example.com/module/@v/list: 403 Forbidden`
- `go get` displays response body if it's plain text
- 410 Gone causes go to stop trying and not fall back to other proxies
- 403 Forbidden may cause go to try other proxies in GOPROXY list

**Recommendation:** **HTTP 403 Forbidden** (allows fallback to other proxies if configured)

**Example User Experience:**
```bash
$ go get example.com/module
go: example.com/module@latest: reading https://artipie.local/example.com/module/@v/list: 403 Forbidden
go: All versions of module 'example.com/module' are currently blocked by cooldown policy.
go: New releases are blocked for 72 hours to prevent supply chain attacks.
```

---

## Unified Error Response Strategy

### HTTP Status Code: **403 Forbidden**

**Rationale:**
- Semantically correct: Client is not authorized to access blocked versions
- Distinguishes from 404 Not Found (package doesn't exist)
- Supported by all package managers
- Allows custom error messages in response body

### Response Body Format (by package type):

| Package Type | Content-Type | Format |
|-------------|--------------|--------|
| NPM | `application/json` | JSON with `error` and `reason` fields |
| PyPI (PEP 503) | `text/html` | HTML with error message |
| PyPI (PEP 691) | `application/vnd.pypi.simple.v1+json` | JSON with `error` object |
| Maven | `text/plain` | Plain text error message |
| Gradle | `text/plain` | Plain text error message |
| Composer | `application/json` | JSON with `error` object |
| Go | `text/plain` | Plain text error message |

### Error Message Template:

```
All versions of {package_type} '{package_name}' are currently blocked by cooldown policy.
New releases are blocked for {cooldown_period} hours to prevent supply chain attacks.
{earliest_available_message}
Please try again later or contact your administrator.
```

**Variables:**
- `{package_type}`: "package" (NPM, PyPI, Composer), "artifact" (Maven, Gradle), "module" (Go)
- `{package_name}`: Full package identifier
- `{cooldown_period}`: Configured cooldown period in hours (e.g., "72")
- `{earliest_available_message}`: "Earliest version will be available in approximately X hours." (if calculable)

### Optional Headers:

```
X-Artipie-Cooldown-Blocked: true
X-Artipie-Cooldown-Policy: fresh_release
X-Artipie-Cooldown-Period: 259200
X-Artipie-Retry-After: 255600
```

---

## Implementation

### Code Example (NPM):

```java
public class NpmMetadataFilter {
    public CompletableFuture<Response> filterMetadata(
        String packageName,
        JsonNode metadata,
        Set<String> blockedVersions
    ) {
        JsonNode versions = metadata.get("versions");
        if (versions == null || versions.size() == 0) {
            return allVersionsBlockedError(packageName);
        }
        
        Set<String> availableVersions = new HashSet<>();
        versions.fieldNames().forEachRemaining(v -> {
            if (!blockedVersions.contains(v)) {
                availableVersions.add(v);
            }
        });
        
        if (availableVersions.isEmpty()) {
            return allVersionsBlockedError(packageName);
        }
        
        // Filter and return metadata
        return filterAndRewrite(metadata, availableVersions);
    }
    
    private CompletableFuture<Response> allVersionsBlockedError(String packageName) {
        JsonObject error = Json.createObjectBuilder()
            .add("error", "Forbidden")
            .add("reason", String.format(
                "All versions of package '%s' are currently blocked by cooldown policy. " +
                "New releases are blocked for 72 hours to prevent supply chain attacks. " +
                "Please try again later or contact your administrator.",
                packageName
            ))
            .build();
        
        return CompletableFuture.completedFuture(
            new RsWithStatus(
                new RsWithBody(error.toString(), StandardCharsets.UTF_8),
                RsStatus.FORBIDDEN
            )
        );
    }
}
```

---

## Testing Strategy

### Test Cases:

1. **NPM:** Verify 403 response with JSON error when all versions blocked
2. **PyPI:** Verify 403 response with HTML/JSON error when all versions blocked
3. **Maven:** Verify 403 response with plain text error when all versions blocked
4. **Gradle:** Verify 403 response with plain text error when all versions blocked
5. **Composer:** Verify 403 response with JSON error when all versions blocked
6. **Go:** Verify 403 response with plain text error when all versions blocked

### Integration Tests with Real Clients:

```bash
# NPM
npm install blocked-package 2>&1 | grep "cooldown policy"

# PyPI
pip install blocked-package 2>&1 | grep "cooldown policy"

# Maven
mvn dependency:get -Dartifact=com.example:blocked:1.0.0 2>&1 | grep "cooldown policy"

# Composer
composer require vendor/blocked-package 2>&1 | grep "cooldown policy"

# Go
go get example.com/blocked-module 2>&1 | grep "cooldown policy"
```

---

## Summary

| Package Manager | HTTP Status | Content-Type | Error Format |
|----------------|-------------|--------------|--------------|
| NPM | 403 | application/json | JSON with error/reason |
| PyPI (PEP 503) | 403 | text/html | HTML with message |
| PyPI (PEP 691) | 403 | application/vnd.pypi.simple.v1+json | JSON with error object |
| Maven | 403 | text/plain | Plain text message |
| Gradle | 403 | text/plain | Plain text message |
| Composer | 403 | application/json | JSON with error object |
| Go | 403 | text/plain | Plain text message |

**All responses include:**
- Clear indication that package exists but versions are blocked
- Explanation of cooldown policy purpose (supply chain attack prevention)
- Actionable guidance (try again later or contact admin)
- Optional: Estimated time until earliest version available

