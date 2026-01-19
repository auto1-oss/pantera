# Cooldown System - Supply Chain Security

The cooldown system blocks package versions that are too fresh (recently released) to prevent supply chain attacks. This is a core security feature of the Auto1 Artipie fork.

## Overview

When a package version is requested, the cooldown system evaluates whether the version was released recently enough to be considered "risky." Fresh releases are blocked for a configurable period (default: 72 hours), giving time for the security community to identify compromised packages before they can be installed in your infrastructure.

### Design Principle

Each package version is evaluated **independently** based on its own release date. There is no automatic dependency blocking. This ensures that stable, well-established packages are never blocked just because they depend on or are dependencies of fresh packages.

## Configuration

### Basic Configuration

```yaml
meta:
  cooldown:
    enabled: true
    minimum_allowed_age: 7d  # Block versions newer than 7 days
```

### Per-Repository Type Configuration

Override settings for specific repository types:

```yaml
meta:
  cooldown:
    enabled: true
    minimum_allowed_age: 24h
    repo_types:
      maven:
        enabled: true
        minimum_allowed_age: 48h
      npm:
        enabled: true
        minimum_allowed_age: 12h
      docker:
        enabled: false  # Disable for Docker
```

### Duration Format

The `minimum_allowed_age` supports:
- Minutes: `30m`
- Hours: `24h`, `72h`
- Days: `7d`, `14d`

## Architecture

### Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `JdbcCooldownService` | artipie-main | Core evaluation engine with 3-tier caching |
| `CooldownMetadataServiceImpl` | artipie-core | Filters metadata to hide blocked versions |
| `CooldownRepository` | artipie-main | JDBC data access for PostgreSQL |
| `CooldownInspector` | artipie-core | Interface for per-adapter release date lookup |
| `CooldownCache` | artipie-core | L1/L2/L3 cache implementation |
| `CooldownSettings` | artipie-core | Configuration parsing and defaults |

### Request Flow

```
Client Request (npm install lodash@latest)
    |
    v
+-------------------+
| Metadata Filter   |  <-- Removes blocked versions from package metadata
| (JdbcCooldown     |      Client sees only allowed versions
|  MetadataService) |
+-------------------+
    |
    v
Client selects version based on filtered metadata
    |
    v
+-------------------+
| Tarball Request   |  <-- Download protection validates selected version
| (DownloadAsset    |      Returns 403 if version is blocked
|  Slice)           |
+-------------------+
    |
    v
Proxy to upstream or return cached artifact
```

### 3-Tier Cache Architecture

The cooldown system uses a hierarchical cache for performance:

| Tier | Storage | TTL | Latency |
|------|---------|-----|---------|
| L1 | In-memory (Caffeine) | 10k entries | <1ms |
| L2 | Valkey/Redis | 1 hour (allowed) | 1-5ms |
| L3 | PostgreSQL | Persistent | 10-50ms |

Cache lookup order: L1 -> L2 -> L3 -> Inspector (fetch release date)

### Database Schema

```sql
CREATE TABLE artifact_cooldowns (
    id BIGSERIAL PRIMARY KEY,
    repo_type VARCHAR(50) NOT NULL,
    repo_name VARCHAR(255) NOT NULL,
    artifact VARCHAR(500) NOT NULL,
    version VARCHAR(255) NOT NULL,
    reason VARCHAR(50) NOT NULL,        -- FRESH_RELEASE, MANUAL_BLOCK
    status VARCHAR(50) NOT NULL,        -- ACTIVE, INACTIVE, EXPIRED
    blocked_at TIMESTAMP NOT NULL,
    blocked_until TIMESTAMP NOT NULL,
    unblocked_at TIMESTAMP,
    unblocked_by VARCHAR(255),
    blocked_by VARCHAR(255),
    installed_by VARCHAR(255),
    UNIQUE(repo_type, repo_name, artifact, version)
);

CREATE INDEX idx_cooldowns_active ON artifact_cooldowns(repo_type, repo_name, status)
    WHERE status = 'ACTIVE';
```

## Evaluation Algorithm

```
evaluate(request, inspector):
    1. Check if cooldown enabled for repo_type
       - If disabled: return ALLOWED

    2. Check circuit breaker (auto-allow if service degraded)
       - If tripped: return ALLOWED

    3. Check L1 cache (in-memory)
       - If found: return cached result

    4. Check L2 cache (Valkey/Redis)
       - If found: populate L1, return cached result

    5. Check L3 (database)
       - If block exists: return BLOCKED with details

    6. Fetch release date via inspector
       - Inspector calls upstream registry for release timestamp

    7. Calculate block duration:
       blocked_until = release_date + minimum_allowed_age

    8. If blocked_until > now:
       - Create DB record (async)
       - Cache as BLOCKED
       - Return BLOCKED

    9. Cache as ALLOWED
    10. Return ALLOWED
```

## Metadata Filtering

The metadata filter processes package listings to hide blocked versions:

### Performance Optimizations

1. **Smart Version Selection**: Only evaluates versions released within the cooldown period
2. **Binary Search**: O(n log n) sort + O(log n) binary search to find evaluation cutoff
3. **Bounded Evaluation**: Evaluates at most 50 versions (configurable)
4. **Parallel Evaluation**: Versions evaluated concurrently using async API
5. **Dynamic Cache TTL**: Filtered metadata cached until earliest blocked version expires

### Latest Version Handling

When the `latest` tagged version is blocked:
1. Find the most recent **stable** unblocked version by release date
2. Exclude prereleases: alpha, beta, rc, canary, dev, snapshot
3. Update `dist-tags.latest` in filtered metadata

## Inspector Pattern

Each repository type implements `CooldownInspector` to provide release dates:

```java
public interface CooldownInspector {
    CompletableFuture<Optional<Instant>> releaseDate(
        String artifact, String version);

    CompletableFuture<Map<String, Instant>> releaseDatesBatch(
        String artifact, Collection<String> versions);
}
```

### Adapter Implementations

| Adapter | Inspector | Release Date Source |
|---------|-----------|---------------------|
| NPM | `NpmCooldownInspector` | `time` object in package metadata |
| Maven | `MavenCooldownInspector` | `lastModified` from repository index |
| PyPI | `PyPiCooldownInspector` | `upload_time` in JSON API |
| Docker | `DockerCooldownInspector` | Manifest creation timestamp |
| Go | `GoCooldownInspector` | Module version timestamp |
| Composer | `ComposerCooldownInspector` | `time` field in packages.json |

### Metadata-Aware Inspectors

Inspectors implementing `MetadataAwareInspector` can accept pre-parsed release dates from metadata, avoiding additional upstream requests:

```java
public interface MetadataAwareInspector<T> {
    void preloadReleaseDates(Map<String, Instant> dates);
}
```

## Monitoring

### Prometheus Metrics

```
# Active blocks gauge
artipie_cooldown_active_blocks{repo_type="npm"} 42

# Blocked version counter
artipie_cooldown_versions_blocked_total{repo_type="npm",repo_name="npm_proxy"} 156

# Allowed version counter
artipie_cooldown_versions_allowed_total{repo_type="npm"} 12847

# Cache hit rates
artipie_cooldown_cache_hits_total{tier="l1"} 95234
artipie_cooldown_cache_hits_total{tier="l2"} 4521
artipie_cooldown_cache_misses_total 521
```

### Log Messages (ECS JSON)

```json
{
  "@timestamp": "2026-01-19T10:30:00.000Z",
  "log.level": "INFO",
  "message": "Package version blocked by cooldown",
  "event.category": "cooldown",
  "event.action": "block",
  "event.outcome": "blocked",
  "package.name": "lodash",
  "package.version": "4.18.0",
  "cooldown.blocked_until": "2026-01-26T10:30:00.000Z",
  "cooldown.reason": "FRESH_RELEASE"
}
```

### Database Queries

```bash
# Count active blocks
docker exec artipie-db psql -U artipie -d artifacts -c \
  "SELECT repo_type, COUNT(*) FROM artifact_cooldowns
   WHERE status = 'ACTIVE' GROUP BY repo_type;"

# View recent blocks
docker exec artipie-db psql -U artipie -d artifacts -c \
  "SELECT artifact, version, blocked_at, blocked_until
   FROM artifact_cooldowns
   WHERE status = 'ACTIVE'
   ORDER BY blocked_at DESC LIMIT 10;"
```

## Operations

### Manual Unblock

To manually unblock a specific version:

```bash
# Via REST API
curl -X DELETE "http://localhost:8086/api/cooldown/npm/npm_proxy/lodash/4.18.0" \
  -H "Authorization: Bearer ${TOKEN}"
```

### Clear All Blocks

```bash
# Via REST API
curl -X DELETE "http://localhost:8086/api/cooldown/npm/npm_proxy" \
  -H "Authorization: Bearer ${TOKEN}"
```

### Cache Invalidation

```bash
# Clear Valkey cache
docker exec valkey redis-cli FLUSHALL

# Restart to clear L1 cache
docker compose restart artipie
```

## Troubleshooting

### "No matching version found"

All versions matching the requested semver range are blocked. Options:
1. Wait for cooldown period to expire
2. Use an older version range
3. Manually unblock the version (if appropriate)

### Old versions incorrectly blocked

If you upgraded from a version with dependency blocking:

```sql
-- Clear any legacy dependency blocks
DELETE FROM artifact_cooldowns WHERE reason = 'DEPENDENCY_BLOCKED';

-- Clear caches after cleanup
docker exec valkey redis-cli FLUSHALL
docker compose restart artipie
```

### Performance issues

1. Check cache hit rates in Prometheus
2. Verify Valkey connectivity
3. Check database connection pool settings
4. Review `minimum_allowed_age` - shorter periods = more evaluations

## Code References

| File | Purpose |
|------|---------|
| [JdbcCooldownService.java](../../artipie-main/src/main/java/com/artipie/cooldown/JdbcCooldownService.java) | Core evaluation engine |
| [CooldownMetadataServiceImpl.java](../../artipie-core/src/main/java/com/artipie/cooldown/metadata/CooldownMetadataServiceImpl.java) | Metadata filtering |
| [CooldownRepository.java](../../artipie-main/src/main/java/com/artipie/cooldown/CooldownRepository.java) | Database operations |
| [CooldownSettings.java](../../artipie-core/src/main/java/com/artipie/cooldown/CooldownSettings.java) | Configuration model |
| [YamlCooldownSettings.java](../../artipie-main/src/main/java/com/artipie/cooldown/YamlCooldownSettings.java) | YAML parsing |
