# Group Repository Cache Configuration

Group repositories aggregate multiple repositories (members) into a single virtual repository.
When clients request packages, Artipie queries members and merges the results.

Starting with v1.18.0, Artipie provides enterprise-grade caching for group repositories:
- **Package Location Index**: Tracks which members have which packages
- **Merged Metadata Cache**: Caches merged metadata responses
- **Event-driven updates**: Local repositories update indexes instantly
- **Two-tier caching**: L1 (in-memory Caffeine) + L2 (Valkey/Redis)

## Global Configuration

Configure group cache settings in `artipie.yml`:

```yaml
meta:
  group:
    index:
      remote_exists_ttl: 15m
      remote_not_exists_ttl: 5m
      local_event_driven: true
    metadata:
      ttl: 5m
      stale_serve: 1h
      background_refresh_at: 0.8
    resolution:
      upstream_timeout: 5s
      max_parallel: 10
    cache_sizing:
      l1_max_entries: 10000
      l2_max_entries: 1000000
```

## Per-Repository Override

Override global settings in individual repository configs:

```yaml
repo:
  type: maven-group
  storage:
    type: fs
    path: /var/artipie/data
  settings:
    repositories:
      - maven-local
      - maven-proxy
    group:
      index:
        remote_exists_ttl: 30m
      metadata:
        ttl: 10m
```

## Settings Reference

### Index Settings (`group.index.*`)

| Setting | Default | Description |
|---------|---------|-------------|
| `remote_exists_ttl` | `15m` | TTL for caching that a package EXISTS in a remote/proxy member |
| `remote_not_exists_ttl` | `5m` | TTL for negative cache (package NOT found in member) |
| `local_event_driven` | `true` | When true, local member indexes update instantly on publish/delete |

**Duration format**: `30s` (seconds), `15m` (minutes), `2h` (hours), `1d` (days)

**How index TTL works**:
- When a package is found in a proxy member, the location is cached for `remote_exists_ttl`
- When a 404 is returned from a proxy member, we cache the negative result for `remote_not_exists_ttl`
- Local members use event-driven updates (no TTL) when `local_event_driven: true`

### Metadata Settings (`group.metadata.*`)

| Setting | Default | Description |
|---------|---------|-------------|
| `ttl` | `5m` | How long merged metadata is cached |
| `stale_serve` | `1h` | Serve stale cache if upstream is down |
| `background_refresh_at` | `0.8` | Trigger refresh at this fraction of TTL (0.8 = 80%) |

**Stale-while-revalidate pattern**:
- At 80% of TTL, background refresh is triggered
- If upstream fails, stale data is served for up to `stale_serve` duration
- This provides high availability during upstream outages

### Resolution Settings (`group.resolution.*`)

| Setting | Default | Description |
|---------|---------|-------------|
| `upstream_timeout` | `5s` | Timeout for each upstream member request |
| `max_parallel` | `10` | Maximum parallel requests to members |

**How resolution works**:
1. Check Package Location Index for known members
2. If unknown, query all members in parallel (up to `max_parallel`)
3. Merge responses using adapter-specific merger
4. Cache merged result

### Cache Sizing (`group.cache_sizing.*`)

| Setting | Default | Description |
|---------|---------|-------------|
| `l1_max_entries` | `10000` | Maximum entries in L1 (in-memory Caffeine) cache |
| `l2_max_entries` | `1000000` | Maximum entries in L2 (Valkey/Redis) cache |

**Two-tier architecture**:
- **L1 (Caffeine)**: Fast, in-memory, per-instance
- **L2 (Valkey)**: Shared across instances, persistent

## Valkey/Redis Configuration

For distributed deployments, configure Valkey connection:

```yaml
meta:
  valkey:
    host: valkey.example.com
    port: 6379
    password: secret
    database: 0
```

When Valkey is not configured, only L1 (in-memory) cache is used.

## Adapter-Specific Behavior

### Maven Groups

Maven groups merge `maven-metadata.xml` files, combining version information.

```yaml
repo:
  type: maven-group
  settings:
    repositories:
      - maven-releases
      - maven-snapshots
      - central-proxy
```

### NPM Groups

NPM groups merge package.json metadata, combining versions and dist-tags.

```yaml
repo:
  type: npm-group
  url: http://localhost:8080/npm-group
  settings:
    repositories:
      - npm-local
      - npmjs-proxy
```

### PyPI Groups

PyPI groups merge `/simple/` HTML index pages, deduplicating package links.

```yaml
repo:
  type: pypi-group
  settings:
    repositories:
      - pypi-local
      - pypi-proxy
```

### Go Groups

Go groups merge `@v/list` version lists with semantic version sorting.

```yaml
repo:
  type: go-group
  settings:
    repositories:
      - go-local
      - goproxy-io
```

### Docker Groups

Docker groups merge manifest lists for multi-architecture images.

```yaml
repo:
  type: docker-group
  settings:
    repositories:
      - docker-local
      - dockerhub-proxy
```

### Composer Groups

Composer groups merge `packages.json` metadata.

```yaml
repo:
  type: php-group
  url: http://localhost:8080/php-group
  settings:
    repositories:
      - php-local
      - packagist-proxy
```

## Performance Tuning

### High Traffic Scenarios

For 1000+ requests/second:

```yaml
meta:
  group:
    index:
      remote_exists_ttl: 30m      # Longer TTL reduces upstream calls
      remote_not_exists_ttl: 10m  # Balance between freshness and load
    metadata:
      ttl: 10m
      background_refresh_at: 0.7  # Earlier refresh for smoother load
    resolution:
      upstream_timeout: 3s        # Fail fast
      max_parallel: 20            # Handle more members
    cache_sizing:
      l1_max_entries: 50000       # More memory for hot packages
      l2_max_entries: 5000000     # Scale up distributed cache
```

### Development/Testing

For development with frequent package updates:

```yaml
meta:
  group:
    index:
      remote_exists_ttl: 1m
      remote_not_exists_ttl: 30s
    metadata:
      ttl: 30s
      stale_serve: 0s             # No stale serving
      background_refresh_at: 0.5  # Refresh sooner
```

## Local vs Proxy Member Behavior

**Local members** (hosted repositories):
- Index updates instantly via events (publish/delete)
- No TTL applied when `local_event_driven: true`
- Changes are immediately visible in group

**Proxy members** (remote repositories):
- Index uses TTL-based expiration
- Negative cache prevents repeated 404 requests
- Cooldown policy may apply (see [Cooldown settings](./Configuration))

## Monitoring

Group cache metrics are available at the metrics endpoint:

| Metric | Description |
|--------|-------------|
| `artipie_group_cache_hit_total` | Cache hits by type (index, metadata) |
| `artipie_group_cache_miss_total` | Cache misses by type |
| `artipie_group_merge_duration_seconds` | Time to merge metadata |
| `artipie_group_upstream_request_duration_seconds` | Upstream request latency |

## Troubleshooting

### Stale packages in group

1. Check if local member event publishing is working
2. Verify `local_event_driven: true` is set
3. Check Valkey connectivity for distributed cache

### Slow group responses

1. Increase `l1_max_entries` for better hit rate
2. Reduce `upstream_timeout` for faster failure
3. Check upstream member health

### Missing packages

1. Check negative cache TTL (`remote_not_exists_ttl`)
2. Verify member repository is accessible
3. Check Package Location Index state via REST API

## REST API

Manage group cache via REST API:

```bash
# Invalidate cache for a package
DELETE /api/v1/repository/{repo}/cache/{package}

# Get cache statistics
GET /api/v1/repository/{repo}/cache/stats

# Force refresh from upstreams
POST /api/v1/repository/{repo}/cache/refresh
```
