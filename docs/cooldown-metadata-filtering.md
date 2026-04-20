# Cooldown Metadata Filtering

> **Audience:** Developers and operators. Describes the two-layer cooldown enforcement architecture introduced in v2.2.0.

---

## Overview

Pantera enforces artifact cooldown at two layers:

1. **Soft metadata filter** -- when a client fetches package metadata (version lists, index pages), blocked versions are silently removed from the response. The client never sees blocked versions in the first place.
2. **Hard 403 response** -- if a client requests a specific blocked artifact by direct URL (bypassing metadata), Pantera returns a per-adapter 403 Forbidden response with structured error details.

This two-layer design prevents build tools from resolving blocked versions while providing clear error messages when direct access is attempted.

## Supported Adapters

| Adapter    | Metadata Format          | Metadata Endpoint                | Content-Type        | Reuses   |
|------------|--------------------------|----------------------------------|---------------------|----------|
| Maven      | XML (`maven-metadata.xml`)| `.../{groupId}/{artifactId}/maven-metadata.xml` | `application/xml`   | --       |
| Gradle     | XML (same as Maven)      | Same as Maven                    | `application/xml`   | Maven    |
| npm        | JSON (packument)         | `/{package}`                     | `application/json`  | --       |
| PyPI       | HTML (simple index)      | `/simple/{package}/`             | `text/html`         | --       |
| Docker     | JSON (tags list)         | `/v2/{name}/tags/list`           | `application/json`  | --       |
| Go         | Plain text (version list)| `/{module}/@v/list`              | `text/plain`        | --       |
| Composer   | JSON (packages.json)     | `/packages/{vendor}/{name}.json` or `/p2/` | `application/json`  | --       |

## Per-Adapter Metadata Filtering Behaviour

### Maven / Gradle

- **Parser:** DOM-parses `maven-metadata.xml`; extracts `<version>` elements from `<versions>`.
- **Filter:** Removes `<version>X</version>` nodes where X is blocked; updates `<latest>` and `<lastUpdated>`.
- **Rewriter:** Serializes DOM back to XML bytes.
- **Detector:** Path ends with `maven-metadata.xml`.
- Gradle reuses Maven components (same metadata format).

### npm

- **Parser:** Jackson parses the JSON packument; extracts version keys from the `versions` object and release dates from the `time` object.
- **Filter:** Removes blocked version keys from `versions`, `time`, and `dist-tags` objects.
- **Rewriter:** Serializes modified JSON.
- **Detector:** Path matches the package name pattern (no file extension, no `/-/`).
- Supports pre-warming the release-date cache from the `time` field (H1 optimisation).

### PyPI

- **Parser:** Parses HTML simple index page; extracts `<a>` tags containing download links.
- **Filter:** Removes `<a>` tags for blocked versions.
- **Rewriter:** Serializes back to HTML.
- **Detector:** Path matches `/simple/`.

### Docker

- **Parser:** Jackson parses the `tags/list` JSON; extracts the `tags` array.
- **Filter:** Removes blocked tag strings from the `tags` array.
- **Rewriter:** Serializes JSON.
- **Detector:** Path matches `/v2/{name}/tags/list`.

### Go

- **Parser:** Splits plain-text response by newline; each non-empty line is a version string.
- **Filter:** Removes lines matching blocked versions.
- **Rewriter:** Joins remaining lines with newline.
- **Detector:** Path ends with `/@v/list`.

### Composer

- **Parser:** Jackson parses `packages.json`; extracts version keys from the `packages.{name}` map.
- **Filter:** Removes blocked version keys from the packages map.
- **Rewriter:** Serializes JSON.
- **Detector:** Path matches `/packages/` or `/p2/`.

## 403 Response Factories

Each adapter provides a `CooldownResponseFactory` that builds format-appropriate 403 responses:

| Adapter    | Content-Type          | Body Shape                                      |
|------------|-----------------------|-------------------------------------------------|
| Maven      | `text/plain`          | Human-readable blocked message with unblock timestamp |
| npm        | `application/json`    | npm-compatible JSON error envelope               |
| PyPI       | `text/plain`          | Human-readable message                           |
| Docker     | `application/json`    | Docker registry error spec JSON                  |
| Go         | `text/plain`          | Human-readable message with ISO-8601 timestamp   |
| Composer   | `application/json`    | Composer-compatible JSON error                   |
| Gradle     | `text/plain`          | Reuses Maven factory                             |

All responses include:
- `Retry-After` header (seconds until block expires)
- `X-Pantera-Cooldown: blocked` header

## Performance Characteristics

### H1: Pre-warmed Release-Date Cache

When metadata is fetched and parsed, release dates embedded in the metadata (e.g., npm's `time` field) are extracted and used to pre-warm the `CooldownCache` L1. Versions older than the cooldown period are guaranteed allowed, so the L1 cache is populated with `false` (allowed) immediately -- avoiding a DB/Valkey round-trip on the hot path for the majority of versions.

### H2: Parallel Bounded Version Evaluation

Version cooldown evaluation runs in parallel on a dedicated 4-thread executor pool, bounded to a maximum of 50 versions per request. Versions are dispatched via `CompletableFuture.allOf()` for concurrent evaluation, reducing end-to-end latency for metadata with many recent versions.

### H3: Stale-While-Revalidate (SWR) on FilteredMetadataCache

When a cached metadata entry expires, the stale bytes are returned immediately to the caller while a background task re-evaluates the metadata. This eliminates tail latency spikes at cache expiry boundaries. The SWR grace period is 5 minutes beyond the logical TTL.

### H4: L1 Cache Capacity (50K entries)

The `FilteredMetadataCache` L1 (Caffeine) defaults to 50,000 entries. Configurable via the `PANTERA_COOLDOWN_METADATA_L1_SIZE` environment variable.

### H5: Inflight-Map Memory Leak Fix

The `CooldownCache` inflight deduplication map now guarantees removal on both success and exceptional completion, preventing memory leaks when DB queries fail. A 30-second `orTimeout` safety net prevents zombie entries from lingering.

## Architecture: Adapter Bundle Registration

Each adapter registers a `CooldownAdapterBundle<T>` at startup via `CooldownAdapterRegistry`:

```java
public record CooldownAdapterBundle<T>(
    MetadataParser<T> parser,
    MetadataFilter<T> filter,
    MetadataRewriter<T> rewriter,
    MetadataRequestDetector detector,
    CooldownResponseFactory responseFactory
) {}
```

When a metadata request arrives, the proxy layer:
1. Looks up the bundle by `repoType`
2. Uses `detector.isMetadataRequest(path)` to confirm it is a metadata request
3. Routes through `MetadataFilterService.filterMetadata(...)` with the bundle's parser/filter/rewriter
4. On direct artifact request for a blocked version, uses `responseFactory.forbidden(block)` for the 403 response

## Admin Operations

### Unblock a Specific Version

```bash
curl -X POST "http://pantera:8086/api/v1/cooldown/unblock" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"repo_type":"npm","repo_name":"npm-proxy","package":"lodash","version":"4.18.0"}'
```

On unblock:
- The DB record is updated first
- `FilteredMetadataCache` L1 + L2 are invalidated for the package
- `CooldownCache` L1 + L2 are invalidated for the specific version
- All invalidation futures complete synchronously before the 200 response

### Policy Change (Duration Update)

When the cooldown duration is changed (e.g., 30d to 7d):
- `FilteredMetadataCache.clearAll()` is called to flush all cached filtered metadata
- Subsequent requests re-evaluate all versions against the new policy

### Cache Invalidation

Manual full cache invalidation:
```bash
curl -X POST "http://pantera:8086/api/v1/cooldown/invalidate" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"repo_type":"npm","repo_name":"npm-proxy"}'
```

## Retention & history

Expired and manually unblocked cooldown entries are archived to
`artifact_cooldowns_history` rather than hard-deleted. This preserves an
audit trail of past blocks for compliance and incident review.

- `archive_reason` is one of `EXPIRED` (cron or Vertx-fallback auto-cleanup),
  `MANUAL_UNBLOCK` (admin unblock action), or `ADMIN_PURGE` (reserved for
  future bulk-purge actions).
- `archived_by` is the authenticated user's subject (for manual unblocks)
  or `"system"` (for automatic expirations).
- History is purged daily at ~03:00 UTC for entries older than
  `history_retention_days` (DB setting, default 90, bounds `(0, 3650]`).
  Edit via the admin UI "Cooldown settings" dialog or
  `PUT /api/v1/cooldown/config`.

## Cleanup execution modes

Two mutually-exclusive cleanup paths run on a 10-minute cadence:

1. **pg_cron (preferred)** -- scheduled by migration V121. Uses the
   `_cooldown_batch_limit()` / `_cooldown_retention_days()` Postgres helper
   functions that read live from the `settings` JSON blob, so admin UI
   changes take effect on the next cron tick without restart.
2. **Vertx fallback** -- activates automatically at startup if pg_cron is
   not installed or the cleanup job is not registered. Runs the same SQL
   on the same cadence via `vertx.setPeriodic`, reading the shared
   `CooldownSettings` object on each tick. Blocking JDBC dispatched via
   `HandlerExecutor` -- never the event loop.

At startup `VertxMain` queries `pg_extension` and `cron.job`; the log line
identifies the chosen mode:

- `pg_cron cleanup job is scheduled; skipping Vertx fallback`, or
- `pg_cron cleanup job not scheduled; starting Vertx fallback`

History retention is enforced daily by either mechanism (pg_cron job
`purge-cooldown-history` or the fallback's hourly check-gated purge).

## Permission model

Two API-level permissions gate cooldown features:

- `api_cooldown_permissions`
  - `read` -- access to `GET /cooldown/blocked`, `GET /cooldown/overview`,
    `GET /cooldown/config`.
  - `write` -- access to unblock actions and `PUT /cooldown/config`.
- `api_cooldown_history_permissions`
  - `read` -- access to `GET /cooldown/history` and the UI history toggle.

Per-repo row filtering is layered on top of both `/blocked` and `/history`
via the existing `AdapterBasicPermission(repoName, "read")`. A user with
the API-level permission still sees only rows for repos they have
repo-level read on.

## Admin UI

The cooldown admin view (`/admin/cooldown`) exposes:

- Free-text search + repo name + repo type dropdown filters (combine with AND).
- Active / History toggle (visible only when `api_cooldown_history_permissions.read` is granted).
- Gear icon opening the cooldown settings dialog (visible only when
  `api_cooldown_permissions.write` is granted). Edits: enabled toggle,
  minimum allowed age, history retention days, cleanup batch limit.

## Configuration

### Global Cooldown Duration

Set in `pantera.yaml`:
```yaml
meta:
  cooldown:
    enabled: true
    minimum_allowed_age: 72h    # default: 72 hours
```

### Per-Repo-Type Override

```yaml
meta:
  cooldown:
    repo_types:
      npm:
        enabled: true
        minimum_allowed_age: 168h   # 7 days for npm
      maven:
        enabled: true
        minimum_allowed_age: 72h
```

### Per-Repo-Name Override (highest priority)

```yaml
meta:
  cooldown:
    repos:
      my-internal-npm:
        enabled: false              # disable for this specific repo
```

### L1 Cache Size

Environment variable:
```bash
export PANTERA_COOLDOWN_METADATA_L1_SIZE=50000   # default
```

### FilteredMetadataCache (YAML)

```yaml
meta:
  caches:
    cooldown-metadata:
      ttl: 24h
      maxSize: 50000
      valkey:
        enabled: true
        l1MaxSize: 500
        l1Ttl: 5m
        l2Ttl: 24h
```

## Package Structure

```
pantera-core/.../cooldown/
  api/         CooldownService, CooldownInspector, CooldownRequest, CooldownResult,
               CooldownBlock, CooldownReason
  cache/       CooldownCache, CooldownCacheConfig, CooldownCacheMetrics
  metadata/    CooldownMetadataService, MetadataParser, MetadataFilter,
               MetadataRewriter, MetadataRequestDetector, MetadataFilterService,
               FilteredMetadataCache, VersionComparators
  response/    CooldownResponseFactory, CooldownResponseRegistry
  config/      CooldownSettings, CooldownAdapterBundle, CooldownAdapterRegistry,
               InspectorRegistry
  metrics/     CooldownMetrics
  impl/        DefaultCooldownService, CachedCooldownInspector, NoopCooldownService

{adapter}/cooldown/
  {Adapter}MetadataParser, {Adapter}MetadataFilter, {Adapter}MetadataRewriter,
  {Adapter}MetadataRequestDetector, {Adapter}CooldownResponseFactory
```

## Testing

- **235+ unit tests** across all 7 adapters (parser, filter, rewriter, detector, response factory)
- **13 integration tests** (MetadataFilterServiceIntegrationTest, CooldownAdapterRegistryTest)
- **2 chaos tests** (CooldownConcurrentFilterStampedeTest: 100-concurrent stampede dedup)
- **Performance tests** (VersionEvaluationParallelTest, CooldownMetadataServicePerformanceTest)
- **SWR tests** (FilteredMetadataCacheSWRTest)
