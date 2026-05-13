# Phase 0 — Mental model of the cold-miss path

Branch: `2.2.0` (working on `opus47-rootcause-20260513`). Date: 2026-05-13.

This document describes the actual code path a Maven client request takes
through Pantera 2.2.0 on a cold cache. It is derived from reading the source,
not from the developer guide. Where the two diverge, this document is
authoritative for the analysis that follows.

## End-to-end path: `GET /maven-group/<groupId>/<artifactId>/<version>/<file>`

Reference paths (all live in this repo):

```
pantera-main/.../VertxMain.java
pantera-main/.../api/AsyncApiVerticle.java         (REST API; not on the artifact path)
pantera-main/.../RepositorySlices.java             (factory: builds per-repo Slice chain)
pantera-main/.../group/GroupResolver.java          (group fanout engine)
pantera-main/.../group/MavenGroupSlice.java        (Maven-specific group wrapper for metadata)
pantera-core/.../http/cache/BaseCachedProxySlice.java
maven-adapter/.../http/CachedProxySlice.java       (extends BaseCachedProxySlice)
pantera-core/.../http/cache/ProxyCacheWriter.java  (tee-and-verify writer)
pantera-core/.../http/resilience/SingleFlight.java (Caffeine-backed coalescer)
pantera-core/.../http/cache/NegativeCache.java     (404 cache)
pantera-core/.../http/cache/RequestDeduplicator.java (legacy alt path)
pantera-main/.../prefetch/PrefetchDispatcher.java
pantera-main/.../prefetch/PrefetchCoordinator.java
pantera-main/.../prefetch/parser/MavenPomParser.java
http-client/.../http/client/jetty/JettyClientSlices.java
http-client/.../http/client/HttpClientSettings.java
```

### 1. HTTP ingress (Vert.x)

Inbound TCP → `VertxSliceServer` → wraps into `RequestLine` / `Headers` /
`Content` (reactive `Publisher<ByteBuffer>`). `MainSlice` dispatches on path
prefix to the per-repo slice resolved by `RepositorySlices.slice(...)`.

### 2. Group resolution (`maven-group`)

For a `maven-group`, `RepositorySlices.java:1118-1146` wraps a
`MavenGroupSlice` over a `GroupResolver`. The `MavenGroupSlice` only matters
for `maven-metadata.xml` (and its `.sha1`/`.md5`); everything else delegates
to `GroupResolver`.

`GroupResolver.response()` (line 274) hands every read request to `resolve()`,
which:

1. **Negative-cache check** (`GroupResolver.java:343-360`): builds a
   `NegativeCacheKey` from the URL path and short-circuits to 404 on hit.
   Shared cache, see `NegativeCacheRegistry`.
2. **Sibling-member pin** (`GroupResolver.java:371-386`): 60 s in-memory pin
   `artifactName -> winningMemberName`, max 50 000 entries. When the `.pom`
   resolves to member M, the subsequent `.pom.sha1` / `.jar` skip the index +
   fanout and go straight to M.
3. **Index lookup** (`locateByName`, `GroupResolver.java:392-401`). `Hit` →
   targeted local read; `Miss` → proxy-only fanout; `Timeout`/`DBFailure` →
   `Fault.IndexUnavailable` → 500.
4. **`proxyOnlyFanout`** (line 577): single-flight coalesces concurrent
   callers (`SingleFlight<String, Void>`, 5 min TTL, 10 000 keys). The leader
   runs `executeProxyFanout` → `querySequentially` over proxy members in
   declared order; followers park on a `CompletableFuture<Void>` gate and
   re-enter `response()` once the gate completes.
5. **Sequential walk** (`tryNextSequentialMember`, line 783). On 404 from
   member N, drain the body and recurse to N+1. On 2xx (or 403, an
   authoritative "blocked"), record the pin, return.

**v2.2.0 changes:** parallel fanout was removed in commit `b31369af5`
(2026-05-05). `maven-metadata.xml` fanout was changed from
union-merge-all-members to sequential-first in `73a5ea4df`. There is no
fall-back to parallel mode.

### 3. Member proxy (Maven `CachedProxySlice`)

When `GroupResolver` hits a proxy member, the request enters that member's
slice — for Maven, `maven-adapter/CachedProxySlice` (extends
`BaseCachedProxySlice`).

`BaseCachedProxySlice.response()` (line 299):

1. Empty path → `handleRootPath`.
2. **Negative-cache** (line 310): `NegativeCache.isKnown404` on the
   structured `NegativeCacheKey`. Hit → 404. Caffeine L1 + optional Valkey
   L2; default TTL 24 h, max 50 000 entries.
3. **`preProcess` hook** (line 318) — subclass override; if returns
   `Optional.of(...)`, that response wins. Maven uses this to:
   - **`maven-metadata.xml` → `handleMetadata`** (Maven `CachedProxySlice.java:282`,
     hands to `MetadataCache.load` with stale-while-revalidate and optional
     cooldown filter).
   - **Primary artifacts (`.pom`, `.jar`, `.war`, `.aar`, `.ear`, `.zip`,
     `.module`) → `verifyAndServePrimary`** (Maven `CachedProxySlice.java:297`).
4. **Cacheability** (`isCacheable`, line 326).
5. **Cache-first** (`cacheFirstFlow`, line 332). `cache.load(key, Remote.EMPTY,
   ALWAYS)` is offline-safe (never goes upstream by itself).
6. **`evaluateCooldownAndFetch` → `fetchAndCache`** (line 704).

### 4. The actual upstream call (the hot regression site)

`BaseCachedProxySlice.fetchAndCache` (line 704-756):

```java
return this.client.response(line, this.upstreamHeaders(headers), Content.EMPTY)  // ① UPSTREAM HERE
    .thenCompose(resp -> {
        if (resp.status().code() == 404) {
            return this.handle404(resp, key, duration)
                .thenCompose(signal -> this.signalToResponse(signal, line, key, store));
        }
        if (!resp.status().success()) {
            return this.handleNonSuccess(resp, duration, key);
        }
        ...
        return this.singleFlight.load(key, () -> {                               // ② COALESCING HERE
            return this.cacheResponse(resp, key, owner, store)
                .thenApply(r -> FetchSignal.SUCCESS);
        }).thenCompose(signal -> this.signalToResponse(signal, line, key, store));
    });
```

**The single-flight is placed AFTER the upstream call.** N concurrent
callers each fire their own `client.response(...)`. The coalescer only
collapses the cache-write phase, which does not reduce upstream load or
rate-limit consumption.

The same shape is present in `master` (commit history confirms: dedup placement
predates v2.2.0 — only the class moved from `RequestDeduplicator` to
`SingleFlight` in `cf7992666`, mechanism unchanged).

Note: `BaseCachedProxySlice` is bypassed for Maven primaries — they take the
`ProxyCacheWriter` path (see §5 below). It is still active for `.sha1`,
`.sha256`, `.md5`, `.sha512`, `.asc`, `.sig`, anything not in the primary
extension set, and `maven-metadata.xml` from the metadata cache fallback.

### 5. Maven primary path (Track 4 stream-through)

Maven `CachedProxySlice.verifyAndServePrimary` (line 615) is what handles a
real `.pom` / `.jar` request once `preProcess` selects it. The cold-miss
chain:

```
verifyAndServePrimary
  └─ storage.exists(key)
       ├─ true  → serveFromCache (single storage read; ZERO upstream I/O).
       └─ false → evaluateCooldownOrProceed
                    └─ cache.load(key, EMPTY, ALWAYS)
                         ├─ present → ok (cached but no metadata)
                         └─ absent  → fetchVerifyAndCache
                                       ├─ fetchPrimaryBody  (UPSTREAM GET <primary>)
                                       └─ cacheWriter.streamThroughAndCommit
                                            ├─ tee body to (a) client response, (b) temp file + digests
                                            └─ in parallel: fetchSidecar(".sha1")  (UPSTREAM GET <primary>.sha1)
                                       └─ after commit: enqueueEventForWriter, fireOnWrite (PrefetchDispatcher)
```

**Per cold cache-miss of a `.pom` (or any primary):** Pantera fires **2
upstream GETs** to the same destination — the primary and its `.sha1` — in
parallel. This is by commit `0a14ee1ae`: "parallelise primary + .sha1 fetch on
cache miss; cuts cold pom-heavy bench from 21.1 s -> 14.4 s" (2026-05-05).
The historical alternative was 4 sidecars (.sha1/.sha256/.md5/.sha512) per
primary (3-4x amplification); commit `973ffcff5` dropped sha256/sha512 from
the cold path (same day). MD5 is now non-blocking
(`ProxyCacheWriter.NON_BLOCKING_DEFAULT`, line 146-147).

The 2× amplification (primary + .sha1) IS NOT a new regression by itself —
the Maven client would have asked for the `.sha1` anyway. But the cache writer
fires the sidecar fetch eagerly, in parallel, even when the client has not
asked for it yet, doubling the burst rate seen by Maven Central per
foreground request.

### 6. The Prefetch subsystem (newly enabled, May 4-5 2026)

After a successful primary write, `ProxyCacheWriter.fireOnWrite` (line 1247)
invokes the shared callback installed in `CacheWriteCallbackRegistry`. The
production wiring (`VertxMain.java:1001-1002`) installs
`PrefetchDispatcher::onCacheWrite`.

`PrefetchDispatcher.onCacheWrite` (`PrefetchDispatcher.java:231-326`):

1. Global kill-switch — `tuning.enabled()`. **Default: `true`** (`PrefetchTuning.defaults()`).
2. Per-repo flag — `repoPrefetchEnabled`. **Default: `true`**
   (`RepoConfig.prefetchEnabled` from `a7cfb89ab`).
3. Parser registry — `MavenPomParser`, `NpmCompositeParser` (for `npm-proxy`).
4. `parser.appliesTo(urlPath)` — was added in commit `fc1416605` (May 12) to
   skip `.jar` / `.war` / `.aar` / `.module`. Before then, Maven POM parser
   was attempting to parse every Maven primary write — including JAR files —
   and burning ~80 WARN-level events per cold `mvn dependency:resolve -U`.
5. Snapshot the bytes-on-disk to a dispatcher-owned temp file (Maven uses
   caller-owns-snapshot; npm uses zero-copy passthrough via `NpmCacheWriteBridge`).
6. Hand off to a `ThreadPoolExecutor(core=2, max=4, queue=1024, abort-policy)` —
   a per-event drop is logged WARN, not propagated.

`PrefetchCoordinator.submit` (line 273) checks circuit breaker, in-flight dedup
key (`repoName|coord.path()`), enqueues on a per-tuning `ArrayBlockingQueue`
(default 2048 capacity). Workers (default 8 threads) drain the queue:

1. **NegativeCache check** on `NegativeCacheKey(repo, type, name, version)`.
2. **Cooldown gate** with 2 s timeout, fail-open.
3. **Per-host semaphore** — Maven/Gradle cap **16**, npm cap **32**.
4. **Global semaphore** — cap **64**.
5. **Upstream GET** with 30 s timeout via injected `UpstreamCaller` (Jetty
   client).

The upstream call is made **with Pantera credentials, not the original client
credentials**. The result (HTTP status) determines whether the negative cache
is populated (404 → cached) or the upstream is treated as a circuit-breaker
drop signal (5xx). The actual response body is discarded — the implementation
relies on the upstream call also going through the proxy's normal cache-write
path so the bytes land in cache.

### 7. The Prefetch upstream caller — the amplification multiplier

`PrefetchTuning.defaults()` (line 38-44):

```java
new PrefetchTuning(
    /* enabled            */ true,
    /* globalConcurrency  */ 64,
    /* perUpstreamConc.   */ 16,
    /* perEcosystem       */ Map.of("maven", 16, "gradle", 16, "npm", 32),  // npm raised from 4 on May 5
    /* queueCapacity      */ 2048,
    /* workerThreads      */ 8
)
```

For Maven this means: every cached `.pom` writes a `CacheWriteEvent`, the
dispatcher parses it, extracts N direct deps (non-test, non-optional,
non-`<dependencyManagement>`, scope `compile`/`runtime`/default), and submits N
prefetch tasks. The coordinator fires those upstream GETs at up to 16
concurrent per host, 64 globally. **Each prefetched POM triggers MORE
prefetches when it caches.** The recursion is bounded only by the in-flight
dedup set and the negative cache.

For a typical Spring Boot starter dependency tree (~200 unique POMs), this
translates to ~200 cache-write events triggering up to 200 × 10 = 2 000
upstream POM GETs of which roughly half (~1 000) are net-new after dedup. All
of them hit `repo1.maven.org` within a small time window.

### 8. The HTTP client

`JettyClientSlices` (`http-client/.../jetty/JettyClientSlices.java`):

- HTTP/2 with HTTP/1.1 fallback via ALPN by default.
- Connection pool: `maxConnectionsPerDestination = 64` (default),
  `maxRequestsQueuedPerDestination = 256`.
- `idleTimeout = 30 s`, `connectTimeout = 15 s`,
  `connectionAcquireTimeout = 30 s`.
- `setUserAgentField(null)` — no Jetty default UA; per-request UA comes from
  `BaseCachedProxySlice.upstreamHeaders` which forwards the inbound client's
  UA verbatim (falls back to `EcosystemUserAgents.defaultFor(repoType)`).
- `setFollowRedirects(true)`.
- `WWWAuthenticationProtocolHandler` removed.

Connection pooling is healthy. There is no fresh-TCP-handshake-per-request
problem. Maven Central uses HTTP/2 so 64 connections × N streams each is
massive parallelism if Pantera wants it.

**Critical: there is no rate limiter, no token bucket, no per-host call/sec
cap on the outbound side.** The semaphore in `PrefetchCoordinator` caps
*concurrent* in-flight prefetches, not *requests per second*. With a 100 ms
upstream RTT and 16 concurrent per-host slots, the steady-state outbound rate
to Maven Central is up to ~160 req/s from prefetch alone.

## Thread pools, locks, caches on the hot path

| Component | Type | Bound | Notes |
|---|---|---|---|
| Vert.x event loop | bounded | CPU × 2 | Non-blocking only |
| `pantera-handler` | `ThreadPoolExecutor(THREADS, ArrayBlockingQueue(1000), AbortPolicy)` | `THREADS` ≈ CPU × 4 | API blocking work |
| `StorageExecutors.READ` | bounded | CPU × 4 | `exists`, `value`, `metadata` |
| `StorageExecutors.WRITE` | bounded | CPU × 2 | `save`, `move`, `delete` |
| `StorageExecutors.LIST` | bounded | CPU × 1 | `list` |
| `pantera-prefetch-dispatch` | `ThreadPoolExecutor(2, 4, ArrayBlockingQueue(1024), AbortPolicy)` | 4 | Parses cached file, submits tasks |
| `pantera-prefetch-*` workers | `Executors.newFixedThreadPool(workerThreads)` | 8 | Pulls from queue, fires upstream |
| `dedup-cleanup` daemon | 1 | — | Legacy `RequestDeduplicator` zombie sweep |
| `inFlightFanouts` (GroupResolver) | `SingleFlight` Caffeine | 10 000 keys | 5 min TTL |
| `inFlightMetadataFetches` (MavenGroupSlice) | `SingleFlight` Caffeine | 10 000 keys | 5 min TTL |
| `singleFlight` (BaseCachedProxySlice) | `SingleFlight` Caffeine | 10 000 keys | TTL = `PANTERA_DEDUP_MAX_AGE_MS` (300 000 ms) |
| `negativeCache` L1 | Caffeine | 50 000 entries | 24 h TTL (default) |
| `memberPin` (GroupResolver) | Caffeine | 50 000 entries | 60 s TTL |
| `metadataCache` (Maven) | Caffeine + Valkey | varies | Stale-while-revalidate |
| Jetty HttpClient | ALPN + multiplexing | 64 conn × destination | 256 queued requests |

## Observability gaps

- **No outbound request-rate metric** to `repo1.maven.org`. We have
  per-repository counters (success / error / 404 / client_error / exception)
  and Jetty connection pool metrics, but nothing that says "we sent N requests
  to maven.org in the last second."
- **No request amplification ratio** metric (`pantera_upstream_requests_total
  / pantera_client_requests_total` per upstream host).
- **`pantera_proxy_phase_seconds`** histograms exist (Phase 7.5 profiler)
  tagged with `repo_name` + `phase`. Good for measuring our internal stages
  but says nothing about request multiplication.
- **PrefetchMetrics** counts dispatched / completed / dropped per
  repo+ecosystem+outcome — but there is no aggregate "prefetch upstream
  requests sent" counter that an alert could trip when prefetch is the
  dominant outbound source. The dropped counter is buried at WARN level.
- **No 429-from-upstream counter**. Inspection of the code: 429 is treated
  as a generic 4xx, propagated verbatim, never logged at WARN. (See
  `BaseCachedProxySlice.handleNonSuccess` line 1219-1221: `client_error`
  metric, no distinction between 429 and other 4xx.) Operators have no
  alert hook for "we are being rate-limited."

## What the documentation says vs. what the code does

The Developer Guide §7.1 (BaseCachedProxySlice 7-step pipeline) lists step
6 as "**Deduplicated** upstream fetch — Only one in-flight request per
artifact key." The code does not deduplicate the upstream fetch. It
deduplicates only the cache-write step that follows the fetch. This is a
documentation/implementation mismatch present in both `master` and HEAD; the
wording was not updated to match the actual placement.

The Developer Guide does not mention the PrefetchDispatcher at all. The
prefetch subsystem is a v2.2.0 addition (commits 62b1d50fa, 8cfb242ff,
e083b3927 — 2026-05-04) that was enabled by default with no public
documentation page in the developer guide. The CHANGELOG references it but
the architecture page is not updated.

## Summary

- **Cold-miss path for a Maven `.pom`:** 2 upstream GETs (primary + .sha1) +
  N prefetched dep POMs (N = direct dependencies of the cached POM).
- **Single-flight coalescing** is placed AFTER the upstream call in
  `BaseCachedProxySlice.fetchAndCache` — concurrent client requests for the
  same uncached path produce N upstream calls, not 1.
- **Prefetch subsystem** (new May 4-5) recursively fans out from every
  primary cache write, with per-host concurrency 16 (Maven) / 32 (npm) and
  no rate cap.
- **No request-amplification observability** (outbound req-rate per upstream,
  amplification ratio, 429 counter).

Phases 1-3 quantify each of these against the two reported problems.
