# Cooldown System - Final Implementation

## Overview

The cooldown system blocks package versions that are too fresh (recently released) to prevent supply chain attacks. Each package version is evaluated **independently** based on its own release date. There is **no automatic dependency blocking**.

## Core Principle

**Independent Evaluation**: Each artifact is evaluated solely based on its own release date. If a package is older than the configured cooldown period, it is allowed regardless of whether it's a dependency of a blocked package.

## Architecture

### 1. Cooldown Evaluation (`JdbcCooldownService`)

**Purpose**: Evaluate if a specific package version should be blocked based on its release date.

**Algorithm**:
```
1. Check if block already exists in database → return cached result
2. Fetch release date from upstream registry (via CooldownInspector)
3. If release date unavailable → ALLOW (fail-open)
4. Calculate: blocked_until = release_date + cooldown_period
5. If blocked_until > now → BLOCK and create database record
6. Otherwise → ALLOW
```

**Key Points**:
- **No dependency resolution**: Dependencies are NOT fetched or evaluated
- **No dependency blocking**: Only the explicitly requested artifact is blocked
- **Fail-open**: If release date cannot be determined, the artifact is allowed
- **Per-repo-type cooldown**: Different repository types can have different cooldown periods

### 2. Metadata Filtering (`JdbcCooldownMetadataService`)

**Purpose**: Filter package metadata to remove blocked versions before serving to clients.

**Algorithm**:
```
1. Parse package metadata (e.g., NPM package.json)
2. Extract all available versions
3. Evaluate each version in parallel using CooldownService
4. Remove blocked versions from metadata
5. Update dist-tags.latest if it points to a blocked version
6. Return filtered metadata
```

**Key Points**:
- Prevents clients from seeing blocked versions
- Updates `latest` tag to point to newest unblocked version
- Cached for performance (24-hour TTL)
- If ALL versions are blocked → return HTTP 403 error

### 3. Tarball Download Protection (`DownloadAssetSlice`)

**Purpose**: Block tarball downloads for blocked versions (defense in depth).

**Algorithm**:
```
1. Parse tarball URL to extract package name and version
2. Evaluate cooldown for that specific version
3. If blocked → return HTTP 403
4. Otherwise → proxy to upstream registry
```

**Key Points**:
- Secondary defense layer (metadata filtering is primary)
- Handles cases where client bypasses metadata or has stale cache
- Only blocks the specific version being downloaded

### 4. Release Date Lookup (`CooldownInspector`)

**Purpose**: Fetch release dates from upstream package registries.

**Interface**:
```java
public interface CooldownInspector {
    CompletableFuture<Optional<Instant>> releaseDate(String artifact, String version);
}
```

**Implementations**:
- `NpmCooldownInspector`: Fetches from NPM registry `time` object
- Future: `PyPiCooldownInspector`, `MavenCooldownInspector`, etc.

**Key Points**:
- Cached with Caffeine (50K packages, 24-hour TTL)
- Returns `Optional.empty()` if release date unavailable
- **No dependency resolution methods** (removed)

## Configuration

```yaml
meta:
  cooldown:
    enabled: true
    minimum_allowed_age: 7d  # Global default
    
    # Per-repository-type overrides
    repo_types:
      npm:
        enabled: true
        minimum_allowed_age: 3d
      maven:
        enabled: true
        minimum_allowed_age: 14d
      docker:
        enabled: false  # Disabled for Docker
```

## Database Schema

```sql
CREATE TABLE artifact_cooldowns (
    id BIGSERIAL PRIMARY KEY,
    repo_type VARCHAR(50) NOT NULL,
    repo_name VARCHAR(255) NOT NULL,
    artifact VARCHAR(512) NOT NULL,
    version VARCHAR(255) NOT NULL,
    reason VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    blocked_by VARCHAR(255) NOT NULL,
    blocked_at BIGINT NOT NULL,
    blocked_until BIGINT NOT NULL,
    unblocked_at BIGINT,
    unblocked_by VARCHAR(255),
    UNIQUE(repo_name, artifact, version)
);
```

**Note**: The `parent_block_id` column is no longer used since dependencies are not blocked.

## Removed Features

The following features were **removed** because they caused incorrect blocking of old packages:

### ❌ Automatic Dependency Blocking

**Problem**: When a fresh package was blocked, ALL of its dependencies were automatically blocked with the same expiration date, even if those dependencies were months or years old.

**Example Bug**:
- Package `@decaf-ts/decoration@0.8.0` released today → blocked for 7 days
- Dependency `reflect-metadata@0.2.2` released 8 months ago → incorrectly blocked for 7 days
- Result: `npm install` fails with "No matching version found"

**Solution**: Removed all dependency blocking logic. Each package is evaluated independently.

### ❌ Dependency Resolution

**Problem**: Resolving dependencies required fetching and parsing additional metadata, adding latency and complexity.

**Solution**: Removed dependency resolution from `CooldownInspector` interface and all implementations.

## Performance Characteristics

### Metadata Filtering
- **Cache hit**: < 1ms (L1 Caffeine cache)
- **Cache miss**: 50-200ms (parallel version evaluation)
- **Typical package**: 20-50 versions evaluated in parallel

### Release Date Lookup
- **Cache hit**: < 1ms (Caffeine cache)
- **Cache miss**: 100-500ms (upstream registry fetch)
- **Cache size**: 50,000 packages (~2.5GB memory)
- **Cache TTL**: 24 hours

### Database Operations
- **Block check**: < 5ms (indexed query)
- **Block insert**: < 10ms (single row insert)
- **Connection pool**: 50 connections

## Error Handling

### Release Date Unavailable
- **Behavior**: Allow the package (fail-open)
- **Reason**: Better to allow an old package than block legitimate usage
- **Log level**: DEBUG

### All Versions Blocked
- **Behavior**: Return HTTP 403 Forbidden
- **Response**: JSON with error details and blocked versions
- **Log level**: WARN

### Upstream Registry Timeout
- **Behavior**: Allow the package (fail-open)
- **Timeout**: 5 seconds
- **Log level**: WARN

## Security Considerations

### Supply Chain Attack Protection
- Blocks newly released versions for configured period (e.g., 7 days)
- Gives time for community to discover malicious packages
- Prevents zero-day supply chain attacks

### Fail-Open vs Fail-Closed
- **Choice**: Fail-open (allow when uncertain)
- **Rationale**: Availability > security for old packages
- **Trade-off**: May allow some fresh packages if metadata unavailable

### Defense in Depth
1. **Primary**: Metadata filtering (prevents client from seeing blocked versions)
2. **Secondary**: Tarball download blocking (catches bypasses)
3. **Tertiary**: Database expiration (automatic unblocking)

## Migration Notes

### From Previous Implementation

If upgrading from a version that had dependency blocking:

1. **Clear dependency blocks**:
   ```sql
   DELETE FROM artifact_cooldowns WHERE parent_block_id IS NOT NULL;
   ```

2. **Remove parent_block_id column** (optional):
   ```sql
   ALTER TABLE artifact_cooldowns DROP COLUMN parent_block_id;
   ```

3. **Clear caches**:
   ```bash
   # Redis/Valkey
   redis-cli FLUSHALL
   
   # Application restart
   docker compose restart artipie
   ```

## Testing

### Unit Tests
- `JdbcCooldownServiceTest`: Core cooldown logic
- `JdbcCooldownMetadataServiceTest`: Metadata filtering
- `NpmCooldownInspectorTest`: Release date lookup

### Integration Tests
- `NpmProxyIT`: End-to-end NPM proxy with cooldown
- Test scenarios:
  - Fresh version blocked
  - Old version allowed
  - Metadata filtering
  - Tarball download blocking

### Manual Testing
```bash
# Test fresh version blocked
npm install --registry=http://localhost:8081/npm_proxy/ typescript@latest

# Test old version allowed
npm install --registry=http://localhost:8081/npm_proxy/ lodash@4.17.21

# Check metadata filtering
curl http://localhost:8081/npm_proxy/typescript | jq '.versions | keys'
```

## Monitoring

### Metrics
- `cooldown.evaluate.duration`: Time to evaluate cooldown
- `cooldown.metadata.filter.duration`: Time to filter metadata
- `cooldown.cache.hit_rate`: Cache hit rate
- `cooldown.blocks.active`: Number of active blocks

### Logs
- `event.action=evaluate`: Cooldown evaluation result
- `event.action=metadata_filter`: Metadata filtering result
- `event.outcome=blocked`: Version blocked
- `event.outcome=allowed`: Version allowed

### Alerts
- High cache miss rate (> 20%)
- High database latency (> 100ms)
- All versions blocked (indicates misconfiguration)

## Future Enhancements

### Planned
- PyPI cooldown inspector
- Maven cooldown inspector
- Docker cooldown inspector
- Configurable fail-open vs fail-closed per repo type

### Not Planned
- ❌ Automatic dependency blocking (removed by design)
- ❌ Transitive dependency resolution (too complex, not needed)
- ❌ Version range blocking (not granular enough)

## Conclusion

The cooldown system now operates on a simple principle: **each package version is evaluated independently based on its own release date**. This eliminates the complexity and bugs associated with dependency blocking while still providing effective supply chain attack protection.
