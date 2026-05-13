# Cooldown Metadata Filtering

> **Audience:** Developers and operators. Describes the two-layer cooldown enforcement architecture introduced in v2.2.0.

---

## Overview

Pantera enforces artifact cooldown at two layers:

1. **Soft metadata filter** -- when a client fetches package metadata (version
   lists, index pages, packuments), blocked versions are silently removed from
   the response. The client never sees blocked versions in the first place.
   This layer covers both direct installs and transitive dependency resolution
   (see "Transitive Dependency Behaviour" below).
2. **Hard 403 / 404 response** -- if a client requests a specific blocked
   artifact by direct URL (bypassing metadata), Pantera returns a per-adapter
   error response with structured details. Most adapters return 403 Forbidden
   with a `Retry-After` header; Docker returns `404 MANIFEST_UNKNOWN` per
   registry spec so `docker pull` treats the tag as non-existent. `file-proxy`
   is covered only at this layer (timestamp-based gate), because raw files
   have no version-resolution metadata to filter.

This two-layer design prevents build tools from resolving blocked versions
while providing clear, format-appropriate error signalling when direct access
is attempted.

## Supported Adapters

The coverage matrix below is the **final state** for v2.2.0. Every proxy adapter that
has version-resolution semantics now filters both its direct version-listing endpoint
and any unbounded-latest resolution endpoint the client can query.

| Proxy adapter      | Endpoints filtered (direct & unbounded / transitive resolution) |
|--------------------|------------------------------------------------------------------|
| maven-proxy        | `maven-metadata.xml` (rewrites `<versions>`, `<latest>`, `<release>`) |
| gradle-proxy       | Same as maven-proxy (reuses Maven components) |
| npm-proxy          | `GET /{pkg}` (packument -- full and abbreviated), `GET /{pkg}/latest` (dist-tag shortcut). `dist-tags.latest` is rewritten to the highest non-blocked version; other dist-tags pointing to blocked versions are dropped. |
| pypi-proxy         | `/simple/{pkg}/` (PEP 503 HTML index), `/pypi/{pkg}/json` (JSON API). `info.version` and `urls` are rewritten to the highest non-blocked version using PEP 440 ordering. |
| docker-proxy       | `/v2/{name}/tags/list` (filters the `tags` array); `/v2/{name}/manifests/{tag}` (returns 404 `MANIFEST_UNKNOWN` when the tag resolves to a blocked digest or the tag itself is blocked). `/manifests/<digest>` continues through the existing digest-level cooldown check. |
| go-proxy           | `/{module}/@v/list` (filters the version list); `/{module}/@latest` (rewrites `Version` to the highest non-blocked version if upstream latest is blocked; preserves `Origin`; returns 403 if every version is blocked). |
| php-proxy (Composer) | `/packages/{vendor}/{pkg}.json`, `/p2/{vendor}/{pkg}.json` (per-package version filtering); `/packages.json`, `/repo.json` (root aggregation -- filters inline packages, passes through lazy-providers schemes unchanged). |
| file-proxy         | **No metadata filtering.** File / raw proxies have no version-resolution semantics -- no tags, no version lists, no packument. Cooldown applies only at the artifact-fetch layer, based on the file's cached-at / remote-modified timestamp relative to the cooldown window. See the dedicated section below. |

### Hosted-only adapters (out of scope)

`gem`, `helm`, `rpm`, `conan`, `conda`, `hexpm`, `nuget`, and `deb` have no `-proxy`
variant in Pantera and therefore never apply cooldown. Hosted repositories serve
your own published artifacts where delaying visibility makes no sense.

### file-proxy scope: artifact-fetch-layer only

`file-proxy` is deliberately excluded from metadata filtering because raw file
proxies do not participate in version resolution:

- There is no "latest version" concept -- each file is its own resource.
- There is no version list endpoint to filter.
- Clients fetch by exact URL; the package-manager resolver abstraction does not apply.

Cooldown still protects file-proxy repositories, but only at the artifact-fetch
layer: if the underlying file's cached-at / remote-modified timestamp falls
within the cooldown window, the fetch is blocked with the standard 403 envelope.
The special-case handling lives in `RepositoryEvents.java` (file / file-proxy
branch of the version-inference path) -- file proxies bypass the parser/filter
pipeline entirely and go directly through the timestamp-based gate.

## Per-Adapter Metadata Filtering Behaviour

### Maven / Gradle

- **Parser:** DOM-parses `maven-metadata.xml`; extracts `<version>` elements from `<versions>`.
- **Filter:** Removes `<version>X</version>` nodes where X is blocked; updates `<latest>` and `<lastUpdated>`.
- **Rewriter:** Serializes DOM back to XML bytes.
- **Detector:** Path ends with `maven-metadata.xml`.
- Gradle reuses Maven components (same metadata format).

### npm

- **Packument endpoint (`GET /{pkg}`):** Jackson parses the JSON packument; blocked
  version keys are removed from `versions`, `time`, and `dist-tags`.
  `dist-tags.latest` is rewritten to the highest non-blocked version; other
  dist-tags pointing to blocked versions are dropped.
- **dist-tag shortcut (`GET /{pkg}/latest`):** served through the same filter
  path so unbounded `npm install foo` resolutions see only allowed versions.
- Supports both the full packument and the abbreviated (`application/vnd.npm.install-v1+json`) variant.
- Supports pre-warming the release-date cache from the `time` field (H1 optimisation).

### PyPI

- **Simple index (`/simple/{pkg}/`):** Parses HTML; removes `<a>` tags for blocked versions.
- **JSON API (`/pypi/{pkg}/json`):** Filters `releases` by version; rewrites
  `info.version` and the top-level `urls` array to reflect the highest
  non-blocked version using PEP 440 ordering.
- Both endpoints are covered because package managers and browsers resolve
  unbounded `pip install foo` through different paths.

### Docker

- **Tags list (`/v2/{name}/tags/list`):** Jackson parses the `tags` array; blocked
  tags are removed.
- **Manifest by tag (`/v2/{name}/manifests/{tag}`):** When the tag resolves to a
  blocked digest, or the tag itself is blocked, returns a registry-spec
  `MANIFEST_UNKNOWN` 404 so `docker pull` behaves as if the tag does not exist.
- **Manifest by digest (`/v2/{name}/manifests/sha256:...`):** Continues through
  the existing digest-level cooldown check (unchanged).

### Go

- **Version list (`/{module}/@v/list`):** Splits plain-text response by newline;
  blocked versions are removed.
- **Latest endpoint (`/{module}/@latest`):** If the upstream `Version` is
  blocked, the response is rewritten to the highest non-blocked version while
  preserving the `Origin` block. If every version is blocked, returns 403 via
  the adapter's `CooldownResponseFactory`.
- Covering `@latest` closes the unbounded-resolution gap for `go get`.

### Composer (PHP)

- **Per-package (`/packages/{vendor}/{pkg}.json`, `/p2/{vendor}/{pkg}.json`):**
  Jackson parses the `packages.{name}` map; blocked version keys are removed.
- **Root aggregation (`/packages.json`, `/repo.json`):** Filters inline packages
  embedded in the root document. Lazy-providers schemes (`providers-url`,
  `metadata-url`) are passed through unchanged so Composer continues to fetch
  per-package documents -- which are filtered as above.

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

---

## Transitive Dependency Behaviour

When a client resolves dependencies end-to-end -- `npm install`,
`mvn dependency:resolve`, `go mod download`, `pip install -r requirements.txt`,
`composer update`, etc. -- the package manager queries the metadata endpoint of
each dependency, **including transitively-required ones**. Pantera's filter
removes blocked versions from every metadata response before the client sees
them. The resolver then picks compatible versions from the filtered set, as if
blocked versions never existed upstream.

The observable behaviour from the client's perspective:

- **Unbounded resolution** (e.g. `npm install foo` with no version constraint):
  the resolver falls back to the highest non-blocked version via the rewritten
  `dist-tags.latest` / `<latest>` / `@latest` / `info.version`.
- **Transitive dependency with a constraint that has at least one non-blocked match**
  (e.g. a dependency requires `^2.0.0` and versions 2.3.1..2.3.4 exist, with only
  2.3.4 blocked): the resolver transparently picks the next-best allowed
  version. The user's build succeeds with no indication that cooldown was
  involved.
- **Transitive dependency whose only matching version is blocked** (e.g. the
  sole version satisfying `^2.0.0` is blocked): the resolver fails with its
  normal "no matching version" error, indistinguishable from upstream genuinely
  not having a match. This is the intended **fail-closed** behaviour.
- **Direct fetch of a blocked version by URL** (e.g. `docker pull foo:1.2.3`
  where `1.2.3` is blocked): the adapter returns its format-appropriate 403 or
  404 (Docker: `MANIFEST_UNKNOWN`) so the client sees a clean negative result
  rather than serving the binary.

The same logic applies at every level of the dependency tree: Pantera filters
metadata per request, so a transitively-required version is blocked exactly the
same way a top-level dependency is.

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

## Architecture: Handler-Dispatched Filtering

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

The bundle provides the parser/filter/rewriter/detector/response-factory building
blocks for a single metadata format.

### Handler dispatch in the adapter's proxy slice

All filtering is **handler-dispatched in the adapter's proxy slice, before the
generic upstream fetch path**. Each filterable endpoint owns its own request
lifecycle in a dedicated handler:

- `GoListHandler` -- `/{module}/@v/list`
- `GoLatestHandler` -- `/{module}/@latest`
- `PypiSimpleHandler` -- `/simple/{pkg}/`
- `PypiJsonHandler` -- `/pypi/{pkg}/json`
- `DockerTagsListHandler` -- `/v2/{name}/tags/list`
- `DockerManifestTagHandler` -- `/v2/{name}/manifests/{tag}`
- `ComposerPackageMetadataHandler` -- `/packages/...`, `/p2/...`
- `ComposerRootHandler` -- `/packages.json`, `/repo.json`
- (npm / Maven follow the same pattern for packument / `maven-metadata.xml`.)

A handler consumes the `CooldownAdapterBundle` components it needs (typically
the parser/filter/rewriter pair) and short-circuits the request **before**
control reaches the generic upstream fetch/cache path in `BaseCachedProxySlice`.

### Why handler dispatch, not one central `MetadataFilterService.filterMetadata(...)` call

The earlier design routed every adapter through a single
`MetadataFilterService.filterMetadata(...)` call using the bundle looked up by
`repoType`. That dispatch point remains in the codebase as a **legacy partial
abstraction** and still backs the single-metadata-endpoint cases, but it is
no longer the sole dispatch mechanism.

The SPI supports exactly **one detector+filter pair per repo type**. Adapters
with more than one metadata endpoint cannot be expressed through it:

- Go has `@v/list` + `@latest`
- PyPI has `/simple/` + `/pypi/{pkg}/json`
- Docker has `/tags/list` + `/manifests/{tag}`
- Composer has per-package + root aggregation

These adapters use per-endpoint handlers in the proxy slice's `response(...)`
method. Each handler owns its endpoint's parser, filter, rewriter, and error
path. The common SPI still supplies reusable pieces (response factories, the
block-reason inspector, release-date extraction) but the fan-out is per
handler, not a single central call.

### Adding a new metadata endpoint

1. Implement parser, filter, rewriter, and a matching detector for the new
   endpoint (format-specific).
2. Create a handler in the adapter's `http` package -- use `GoListHandler` as
   the canonical reference implementation.
3. Wire the handler into the proxy slice's `response(...)` method **before**
   the generic upstream fetch path. Use the detector to select the handler.
4. Register a format-appropriate `CooldownResponseFactory` via `CooldownWiring`.
5. Do **not** attempt to extend `MetadataFilterService` to dispatch multiple
   endpoints per repo type -- the SPI's one-detector-per-type limit is
   intentional, and per-endpoint handlers are the supported extension point.

On a direct artifact request for a blocked version the adapter resolves the
block via `CooldownResponseRegistry.getOrThrow(repoType).forbidden(block)` for
the 403/404 response (format-appropriate).

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

### Canonical test layout for a new metadata endpoint

When adding a new metadata endpoint (per the "Adding a new metadata endpoint"
checklist in Architecture above), aim for the following tests:

1. **Parser test** -- golden-file parse of a real upstream response.
2. **Filter test** -- inputs with known blocked versions; assert filtered output.
3. **Rewriter test** -- round-trip parse/rewrite stability, and verify
   format-specific invariants (e.g., `info.version` matches highest remaining
   version for PyPI JSON).
4. **Handler test** -- happy path + fully-blocked path (expect 403/404) + no-op
   path (nothing blocked).
5. **Integration test** -- full proxy slice against a fake upstream serving
   canned metadata, asserting end-to-end that blocked versions do not appear in
   the response.
