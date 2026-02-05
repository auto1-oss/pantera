# Group Metadata Merging Design

## Problem Statement

Currently, non-Maven groups use a "race" strategy where the first successful response wins.
This causes version inconsistency when different members have different versions of the same package.

## Current State

| Adapter | Metadata File | Merging Support |
|---------|---------------|-----------------|
| Maven | `maven-metadata.xml` | ✅ Yes (MavenGroupSlice) |
| NPM | `package.json`, registry API | ❌ No |
| Go | `@v/list`, `.info`, `.mod` | ❌ No |
| PyPI | `/simple/` HTML index | ❌ No |
| Docker | `/manifests/` (tags) | ❌ No |
| Gradle | Same as Maven | ✅ Yes (uses MavenGroupSlice) |
| Composer | `packages.json`, `/p2/` | ❌ No |

## Proposed Solution

### 1. Create Adapter-Specific Group Slices

```
artipie-main/src/main/java/com/artipie/group/
├── MavenGroupSlice.java      # ✅ EXISTS
├── NpmGroupSlice.java        # NEW - merge package.json
├── GoGroupSlice.java         # NEW - merge @v/list
├── PypiGroupSlice.java       # NEW - merge /simple/ index
├── DockerGroupSlice.java     # NEW - merge tag lists
├── ComposerGroupSlice.java   # NEW - merge packages.json
└── GroupMetadataCache.java   # ✅ EXISTS - reuse
```

### 2. NPM Group Metadata Merging

```javascript
// Member 1: package.json for "lodash"
{
  "versions": {
    "4.17.20": { ... },
    "4.17.21": { ... }
  }
}

// Member 2: package.json for "lodash"
{
  "versions": {
    "4.17.21": { ... },
    "4.17.22": { ... }
  }
}

// MERGED result:
{
  "versions": {
    "4.17.20": { ... },  // from member 1
    "4.17.21": { ... },  // from member 1 (priority)
    "4.17.22": { ... }   // from member 2
  }
}
```

### 3. Go Module Group Metadata Merging

```
// Member 1: @v/list
v1.0.0
v1.1.0

// Member 2: @v/list
v1.1.0
v1.2.0

// MERGED result (sorted, deduplicated):
v1.0.0
v1.1.0
v1.2.0
```

### 4. PyPI Group Metadata Merging

```html
<!-- Member 1: /simple/requests/ -->
<a href="requests-2.25.0.tar.gz">requests-2.25.0.tar.gz</a>
<a href="requests-2.25.1.tar.gz">requests-2.25.1.tar.gz</a>

<!-- Member 2: /simple/requests/ -->
<a href="requests-2.25.1.tar.gz">requests-2.25.1.tar.gz</a>
<a href="requests-2.26.0.tar.gz">requests-2.26.0.tar.gz</a>

<!-- MERGED result: -->
<a href="requests-2.25.0.tar.gz">requests-2.25.0.tar.gz</a>
<a href="requests-2.25.1.tar.gz">requests-2.25.1.tar.gz</a>
<a href="requests-2.26.0.tar.gz">requests-2.26.0.tar.gz</a>
```

### 5. Priority Resolution (Not Race)

Change from parallel race to priority-based resolution:

```java
// CURRENT (race - inconsistent)
for (Slice member : members) {
    // Start all in parallel, first success wins
}

// PROPOSED (priority - consistent)
for (Slice member : members) {
    Response resp = member.response(...).get(timeout);
    if (resp.status() == OK) {
        return resp;  // Stop at first success
    }
}
return notFound();
```

For metadata requests, still fetch from ALL members and merge.

### 6. Caching Strategy

Reuse existing `GroupMetadataCache` pattern:
- L1: In-memory (Caffeine) - 5 min TTL
- L2: Distributed (Redis/Valkey) - 1 hour TTL
- Background refresh at 80% TTL

### 7. Implementation Priority

1. **NPM** - Most requested, JSON merging is straightforward
2. **Go** - Simple text list merging
3. **PyPI** - HTML parsing required
4. **Docker** - Tag list merging (JSON)
5. **Composer** - Similar to NPM (JSON)

## Configuration

```yaml
repo:
  type: npm-group
  members:
    - npm-local      # Priority 1 (checked first for artifacts)
    - npm-proxy      # Priority 2
  metadata:
    merge: true      # Enable metadata merging (default: true)
    cache_ttl: 300   # Metadata cache TTL in seconds
    priority_resolution: true  # Use priority instead of race for artifacts
```

## Success Criteria

1. `npm view lodash versions` returns ALL versions from ALL members
2. `go list -m -versions example.com/pkg` returns merged version list
3. `pip index versions requests` shows all available versions
4. Consistent results regardless of member response timing
5. Performance: <100ms for cached metadata, <1s for cold fetch
