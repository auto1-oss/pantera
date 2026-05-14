# Pantera vs canonical architecture — gap analysis

Written 2026-05-14 after reading the canonical synthesis (`canonical-architecture.md`) and the priority Pantera hot-path files. References are `file:line` to the current 2.2.0 branch HEAD.

## Method

For each section of the canonical architecture, this document records: what the canonical says, what Pantera does, the classification, the estimated latency cost on the cold-miss path, and the severity for the 55s reproduction. Classifications follow the prompt's definitions: **missing / broken / over-engineered / inverted / divergent-by-design**.

The 4–5× cold-miss gap is the dependent variable. Each gap's severity is reasoned about relative to that.

## Executive summary

Pantera has done substantial work to align with the canonical architecture. The 2.2.0 milestones (M1–M6) closed many of the obvious gaps that earlier rounds of analysis identified. Specifically:
- Single-flight is well-implemented (M4 — `SingleFlight` over Caffeine `AsyncCache`).
- Negative cache is correctly 404-only with two tiers (L1 Caffeine + L2 Valkey).
- Cooldown HEAD on every cache hit was eliminated (M5).
- Speculative prefetch was deleted (M2).
- Rate-limit gate is reactive only, no proactive token bucket (the comment cites the 25%-throughput regression that justified removing it).
- HTTP/2 is supported via Jetty 12; per-request idle timeout is wired.
- Status fidelity is correctly mapped (W6: 404, 410, 429 + Retry-After, 5xx → 502).

The **structural** gaps that remain — the ones that materially explain the residual 4–5× gap — fall into three clusters:

1. **The non-Maven hot path serializes upstream → temp file → storage → re-read → client.** This is `BaseCachedProxySlice.cacheResponse` (`pantera-core/.../BaseCachedProxySlice.java:965-1113`). Bytes traverse the JVM twice. Reference systems tee bytes to client and storage in one pass (Verdaccio) or serve directly from the freshly-written blob (Nexus). Pantera's Maven primary path does tee correctly via `ProxyCacheWriter.streamThroughAndCommit` — but every other adapter (npm, pypi, composer, go, docker, etc.) does not, and even the Maven sidecar / metadata path goes through the non-tee code.
2. **Pantera lacks a general circuit breaker.** `RateLimitedClientSlice` (`http-client/.../RateLimitedClientSlice.java:88-117`) reacts only to 429 / 503 *with* `Retry-After`. There is no Nexus-style auto-block that trips on a single 5xx / IO / connection failure with Fibonacci backoff. During a Maven Central blip or rate-limit storm, every request still hits the wire until the upstream itself returns `Retry-After`. The amplification math is the same as Verdaccio's.
3. **No conditional requests anywhere.** `If-None-Match` / `If-Modified-Since` are never sent upstream — confirmed by the earlier reproduction grep (0 hits) and re-confirmed by reading `MetadataCache` indirectly via `CachedProxySlice.handleMetadata` (no validator passing). Every `maven-metadata.xml` refresh is a full GET. Maven Central serves `ETag` + `Last-Modified`; we ignore them.

A fourth, less severe but worth-naming cluster: **the cooldown path adds per-request DB work even on cache miss for primaries**, and the SHA1 sidecar fetch is unconditional on every cold primary (Track 4 / Phase 7 acceptance: `.md5/.sha256/.sha512` are deferred, but `.sha1` is not). That's a 2× upstream baseline before any other amplification kicks in.

## G1 — Request lifecycle (slice pipeline)

**Canonical**: 9-step pipeline (negative cache → cooldown probe → single-flight gate → upstream HTTP with auto-block → tee bytes to client + storage → digest inline → metadata DB write asynchronously → sidecar writes asynchronously → release gate). Hot-path target: ≤ 300 ms p99 cold miss; ≤ 50 ms p99 cache hit.

**Pantera**: 8-step pipeline matches the canonical at the structural level (`BaseCachedProxySlice.response` at `pantera-core/.../BaseCachedProxySlice.java:317-357` plus the Maven-specific `verifyAndServePrimary` at `maven-adapter/.../CachedProxySlice.java:615-676`). The non-Maven path (`fetchAndCache`/`cacheResponse`) does upstream → temp file → storage write → temp file delete → cache reload → serve. The Maven primary path (`fetchVerifyAndCache` → `cacheWriter.streamThroughAndCommit`) does the streaming tee.

**Gap classification**: divergent-by-design for the Maven primary path; **broken** for every other adapter and for the Maven metadata/sidecar path. The streaming-tee primitive exists but is not used by the majority of slices.

**Latency cost on cold-miss**: 80–250 ms per resource for non-tee paths (write + re-read of a 50 KB–2 MB blob).

**Severity for 55s**: **primary**. A mvn cold walk touches `.pom` and `.jar` primaries (which use the tee) plus `.sha1` and `maven-metadata.xml` (which do not). For a 150-resource tree split ~50/50 between primaries and others, the 75 non-tee resources × 100 ms median ≈ 7.5 s of pure write-then-read overhead.

## G2 — Cache hierarchy

**Canonical**: L1 in-memory hot metadata (Caffeine), L2 negative cache (404, 30 min default), L3 positive metadata cache (with conditional refresh), L4 binary cache (no TTL, content-addressed), optional L5 cache-fs in front of object storage.

**Pantera**: L1 in-memory metadata cache (`CachedArtifactMetadataStore`), L2 negative cache (`NegativeCache` with L1 Caffeine + L2 Valkey, `pantera-core/.../NegativeCache.java:35-90`), L3 maven-metadata cache (`MetadataCache` — implementation not read in detail but invoked from `CachedProxySlice.handleMetadata`), L4 binary cache via `Storage` abstraction (file or S3, `DispatchedStorage` for read/write/list pool routing).

**Gap**: structurally aligned. L4 is **not** content-addressed (per-repo path projection on disk, not SHA-256 dedup as Artifactory does). L5 (`DiskCacheStorage`) exists but is opt-in via S3 config.

**Classification**: divergent-by-design (path-addressed storage). The path-addressed model means cross-repo dedup is impossible; not a latency problem but an operational one (twice the disk for the same artifact in two repos).

**Severity for 55s**: **unrelated**. Storage layout doesn't affect first-fetch latency.

## G3 — Single-flight / request collapsing

**Canonical**: per-repo, per-path `ConcurrentMap<String, CompletableFuture>`. Per-key concurrency cap 100. Follower timeout 30 s; lead unbounded. Multi-instance via Valkey / Hazelcast.

**Pantera**: `SingleFlight<K, V>` (`pantera-core/.../http/resilience/SingleFlight.java`) — Caffeine `AsyncCache`-backed coalescer. Defaults wired in `BaseCachedProxySlice`: `PANTERA_DEDUP_MAX_AGE_MS=300_000` (5 min) inflight TTL, `maxInFlight=10000`, executor `ForkJoinPool.commonPool()` via `ContextualExecutor`. Followers re-enter `cacheFirstFlow` on gate completion. The Maven primary path uses the `coalesceUpstream` protected helper that ties gate completion to `StreamedArtifact.verificationOutcome` (so followers only retry after the cache write is durable). Per-key cap is implicit (the gate's `CompletableFuture` collects all waiters into one terminal value).

**Gap**: **alignment is strong**, arguably better than Verdaccio (which has none) and comparable to Nexus's `Cooperation2`. The one missing feature is an explicit `threadsPerKey` cap that fast-fails the N+1th waiter; Pantera relies on `maxInFlight` (count of distinct keys, not waiters per key) and the overall request queue. Multi-instance coordination via Valkey is not implemented; per-process dedup only.

**Classification**: divergent-by-design (no per-key cap; per-process only).

**Severity for 55s**: **unrelated to the cold-miss median** (single-flight only matters under concurrency); could be contributing under burst traffic but the reproduction is a single-mvn `dependency:resolve`, not a parallel burst.

## G4 — Negative cache

**Canonical**: 404-only, 30 min default, per-repo per-path, separate from binary cache. Skip while circuit breaker is tripped. Invalidate on 2xx for same path.

**Pantera**: `NegativeCache` (`pantera-core/.../NegativeCache.java`). Strictly 404-only enforced by the caller contract documented at `NegativeCache.java:138-148`. L1 Caffeine + optional L2 Valkey. `NegativeCacheKey` is `scope:repoType:artifactName:artifactVersion` (URL-encoded). Default TTL via `NegativeCacheConfig`. Invalidation by exact key, by artifact name (with parent-prefix matching for Go), and by batch.

**Gap**: structurally aligned and arguably more sophisticated than the canonical (two-tier, with Valkey distribution for free across cluster nodes). One gap: there is no explicit "skip negative cache while circuit-breaker tripped" check, because there is no general circuit breaker (see G6). The reactive rate-limit gate doesn't poison the negative cache because `cacheNotFound` is only called on a true upstream 404, not on the gate-synthesized 429.

**Classification**: aligned.

**Severity for 55s**: **unrelated** for cold misses on real artifacts. Helps on typo'd dependencies (no amplification penalty).

## G5 — Conditional requests

**Canonical**: never for release JARs / POMs (immutable). Always conditional for metadata (`If-None-Match` + `If-Modified-Since`). 304 → bump `lastVerified` timestamp, no blob rewrite.

**Pantera**: **never anywhere**. Confirmed by `grep -rn 'If-Modified-Since\|If-None-Match' pantera-core/src/main maven-adapter/src/main http-client/src/main` returning 0 hits (`analysis/01-reproduction.md:152-161`). `MetadataCache.load` in `handleMetadata` (`CachedProxySlice.java:415-444`) takes a `Supplier<CompletableFuture<Optional<Content>>>` for the upstream fetch; the supplier calls `client().response(line, Headers.EMPTY, Content.EMPTY)` with empty headers — no validators forwarded even when a previous metadata fetch's ETag could have been stored. Maven Central serves both `ETag` and `Last-Modified` per the Maven Central study; we ignore both.

**Gap classification**: **missing**.

**Latency cost on cold-miss**: 0 ms for cold (no validator to send), 60–90 ms per `maven-metadata.xml` refresh after the soft TTL. A typical mvn cold walk fetches ~5–15 metadata files (one per groupId/artifactId in the closure); each is a full GET of typically 1–10 KB.

**Severity for 55s**: **contributing**. ~0.5–1.5 s of unconditional metadata transfer on a cold tree.

For release JARs: Pantera does NOT revalidate cached releases (the `verifyAndServePrimary` cache-hit branch returns immediately without an upstream call — Track 5 Phase 1A inversion at `CachedProxySlice.java:619-621`). This is correct alignment with the canonical's "never revalidate releases" axiom.

## G6 — Upstream HTTP client

**Canonical**: Apache HttpClient 5.x or Jetty HttpClient. HTTP/2 preferred. 50–200 connections per route. 30 s socket timeout. **Circuit breaker decorator that trips on single 5xx / IO / 401 / 407 with Fibonacci backoff, with a HEAD probe at every block-expiry instant.**

**Pantera**:
- Jetty 12 HttpClient (`JettyClientSlices`, `JettyClientSlice`). HTTP/2 with HTTP/1.1 fallback via ALPN.
- Pool sizing: `maxConnectionsPerDestination` default 64 (per CLAUDE.md), `idleTimeout` default 30 s.
- Per-request idle timeout wired (`JettyClientSlice.java:368-371`); 120 s no-data abort in the streaming demander (`StreamingDemander.run` at line 433-447).
- Rate-limit decorator: `RateLimitedClientSlice` (`http-client/.../RateLimitedClientSlice.java`) wraps every per-host Jetty slice. Reactive only — trips the gate only on upstream `429` or `503 with Retry-After`. Other 5xx, IOExceptions, connection failures: **pass through with no gate trip**.
- There is a per-repo bulkhead (`RepoBulkhead`, `pantera-core/.../RepoBulkhead.java`) but it is wired only for the group resolver path (`RepositorySlices.java:205-208, 1407-1426`), NOT for the proxy-slice direct path. A request hitting `/maven_proxy/...` directly bypasses the bulkhead.

**Gaps**:
- **No general circuit breaker.** Missing primitive. (`RateLimitedClientSlice.response` at line 88-117 has no "if first 5xx → block" branch.)
- **Bulkhead not wired into proxy slice path.** Inverted relative to canonical, because the bulkhead's purpose is per-repo blast radius which is most useful on the direct slice path.
- **HTTP client pool sizing is OK** (64 per route matches Artifactory's 50; HTTP/2 multiplexing helps further).

**Classification (circuit breaker)**: **missing**.
**Classification (bulkhead wiring)**: **inverted** — defined but not where it would help.

**Latency cost on cold-miss**:
- Circuit-breaker absence: 0 ms for the happy path, catastrophic during outages (every concurrent request keeps hitting upstream, fueling more 429s, fueling escalating block durations).
- Bulkhead absence on proxy path: 0 ms on the happy path, no per-repo bounded queue means burst traffic from one repo can starve another.

**Severity for 55s**: **primary during 429 storms** (which the reproduction hits). The mvn cold walk against `sonar-maven-plugin` reportedly triggers Maven Central 429s; without a circuit breaker we keep hammering through the storm.

## G7 — Streaming vs buffering

**Canonical**: tee bytes from upstream to client and storage in one pipeline (Verdaccio pattern). Lead's perceived first-byte latency = upstream TTFB.

**Pantera**:
- **Maven primary path**: `ProxyCacheWriter.streamThroughAndCommit` (`pantera-core/.../ProxyCacheWriter.java`, called from `CachedProxySlice.fetchVerifyAndCache` at line 724-728) — does tee. Returns `StreamedArtifact` whose body is what the client gets streamed.
- **Non-Maven path (npm, pypi, composer, go, docker, etc.)**: `BaseCachedProxySlice.cacheResponse` at line 965-1113. Body subscribed and written to a temp file (line 999-1047), THEN saved to storage (line 1048-1052), THEN metadata saved (line 1060), THEN sidecars written (line 1062-1089), THEN event enqueued, THEN onCacheWrite fired, THEN temp file deleted (line 1097). After all this, `signalToResponse(SUCCESS, ...)` at line 904-926 does `cache.load(key)` and serves from the cached bytes. **The bytes traverse the JVM twice.**
- **Maven metadata path**: `MetadataCache.load` returns `Optional<Content>` after the upstream fetch completes; the body is buffered for re-serve. Streaming behavior not confirmed by direct read but the API shape (`Optional<Content>`) implies the body is materialised before return.

**Gaps**:
- For non-Maven adapters: **broken**. Two-pass byte path is the most direct explanation for the per-resource latency tax.
- The fsync is correctly disabled (line 1034: "Intentionally NO fsync... fsync per primary added 5-10s wall on macOS APFS").

**Classification**: **broken** for non-Maven; aligned for Maven primary; **broken** for Maven metadata if confirmed.

**Latency cost on cold-miss**: 50–200 ms per non-tee resource (depending on size). For a 50 KB POM: ~50 ms extra. For a 1 MB JAR: ~150 ms extra.

**Severity for 55s**: **primary**. The dominant explanation for the per-resource overhead in the reproduction. A Maven cold walk has ~50% non-tee resources (metadata, sidecars).

## G8 — Metadata handling

**Canonical**: stale-while-revalidate. Soft TTL 30 s, hard TTL 2 h. Conditional fetches in the background; foreground serves stale-but-cached. 304 fast path. Group merges from all members in parallel.

**Pantera**: `MetadataCache` (`maven-adapter/.../MetadataCache.java`). Invoked from `CachedProxySlice.handleMetadata` at line 415-444. The loader signature `Supplier<CompletableFuture<Optional<Content>>>` and the use of `Headers.EMPTY` for the upstream fetch (line 420) confirms unconditional GET on miss. Stale-while-revalidate flag exists at `ProxyCacheConfig.staleWhileRevalidateEnabled()` (`BaseCachedProxySlice.java:1387`) but is for stale-on-upstream-error, not stale-while-fresh-fetch.

**Gaps**:
- No conditional GET on refresh.
- Stale-while-revalidate is wired only for "upstream errored, serve old" — not for "TTL elapsed, serve old while background refresh."

**Classification**: **missing** (no conditional refresh, no SWR-on-fresh).

**Latency cost on cold-miss**: same as G5 — 60–90 ms per metadata file × 5–15 per cold walk.

**Severity for 55s**: **contributing**.

## G9 — Storage layout

**Canonical**: content-addressed (SHA-256). Cross-repo dedup. Soft-delete + GC.

**Pantera**: path-addressed via `Storage`/`SubStorage`/`DispatchedStorage`. Per-repo paths on disk. No SHA-256 dedup at the storage layer.

**Gap**: divergent-by-design.

**Severity for 55s**: **unrelated** (no first-fetch latency impact).

## G10 — Group / virtual repository resolution

**Canonical**: serial first-match for artifacts; parallel merge for metadata. Skip auto-blocked members. Cache miss across N members generates ≤ N upstream calls.

**Pantera**: `MavenGroupSlice`, `GroupResolver`, `GroupResolver.proxyOnlyFanout` (referenced from `BaseCachedProxySlice.java:176`). Per `analysis/01-reproduction.md`: `MavenGroupSlice.tryMembersSequentially` for metadata at `MavenGroupSlice.java:438`; `GroupResolver.proxyOnlyFanout` with single-flight gate at line 577-639; merge fanout via `MavenGroupSlice.mergeMetadata` at line 321-388 (also single-flight gated).

The group-layer single-flight is correctly in place per the reproduction analysis. The artifact-fetch single-flight that *was* misplaced has now been fixed (M4, per `BaseCachedProxySlice.fetchAndCache:739-762` and the `coalesceUpstream` API).

**Gap**: alignment is strong. No "skip auto-blocked members" because there's no auto-block (G6). Otherwise structurally correct.

**Classification**: aligned (modulo G6).

**Severity for 55s**: **unrelated** for the reproduction (a single `dependency:resolve` against a `maven_group` that contains `maven_proxy` → Maven Central).

## G11 — Observability minimum

**Canonical**: per-repo per-second counters for cache hits, upstream requests, 429s, single-flight followers, circuit breaker state, HTTP pool gauges.

**Pantera**: `MicrometerMetrics` is extensively wired throughout `BaseCachedProxySlice` (recordProxyPhase, recordProxyMetric, recordCacheHit/Miss, recordUpstream429, recordHttp2Negotiation, recordOutboundRequest, recordOutboundRateLimited). The recent M6 perf-gate workflow (commit `e985769f2`) added CI enforcement of three recording rules.

**Gap**: aligned and arguably more thorough than the canonical baseline. The phase-bucket counters (`recordProxyPhase("cache_first_flow", ...)` etc.) are useful for diagnosing exactly which phase is slow — better than what either Artifactory or Nexus expose by default.

**Classification**: aligned.

**Severity for 55s**: not applicable (this is meta, not causal).

## G12 — Throttling resilience

**Canonical**: 8-layer defence: immutable cache → single-flight → negative cache → circuit breaker → bounded queues → 429-aware client → streaming → per-route concurrency cap.

**Pantera**: 6 of 8 layers in place:
1. ✅ Immutable cache for releases (Track 5 Phase 1A inversion at `CachedProxySlice.java:619-621`).
2. ✅ Single-flight (`SingleFlight`).
3. ✅ Negative cache (`NegativeCache`).
4. ❌ **No general circuit breaker** (G6).
5. ⚠️ Bounded queue only at the group layer (`RepoBulkhead`) — not on the proxy slice direct path.
6. ✅ 429-aware client (`RateLimitedClientSlice` parses Retry-After, gates).
7. ⚠️ Streaming only for Maven primary; non-Maven and Maven metadata buffer.
8. ✅ Per-route HTTP pool cap (Jetty 64-per-destination).

**Gap classification**: **broken** for layer 4 (missing); **broken** for layers 5 and 7 (partial).

**Severity for 55s**: **primary**. The combination of (4) and (5) means a Maven Central 429 storm causes Pantera to keep hammering the upstream until the upstream's escalating block durations dominate the wall-clock. The reproduction's "55s when throttled, 13s when not" pattern matches this exactly: when not throttled, the only excess cost is the per-resource buffering (G7) and the unconditional metadata refresh (G5/G8); when throttled, no defence stops the amplification.

## Cross-cutting observations

### The Maven-primary path is qualitatively different from every other path

Both the slice (`CachedProxySlice extends BaseCachedProxySlice`) and the cache writer (`ProxyCacheWriter`) have a streaming-tee implementation that is genuinely well-aligned with the canonical architecture. The Track 4/Track 5 work clearly absorbed the right lessons. **But this work was done only for Maven primary artifacts (`*.pom`, `*.jar`, etc.) — every other adapter inherits the base class's sequential write-then-read path.**

This is the single highest-leverage observation in the document: **the gap is not "Pantera is structurally wrong"; the gap is "Pantera fixed it for Maven primaries and never extended the fix to anything else."** Generalising the tee primitive to all proxy slices closes G7. Generalising it to the Maven metadata path closes G7 for `maven-metadata.xml` too.

### The reactive rate-limiter is the right shape but the wrong target

`RateLimitedClientSlice` reacts to upstream 429 / 503 + Retry-After. That's the canonical "honor the upstream's signal" behaviour. But it does not react to:
- A single 5xx (transient breakage, the canonical circuit-breaker trip event).
- Repeated IOExceptions (DNS, connection reset, TLS).
- Failed TCP connects.

The justification for removing the proactive token bucket (the comment at `RateLimitedClientSlice.java:51-58`) is correct: a proactive cap can throttle ourselves below what the upstream allows. But removing the cap left a gap: when the upstream genuinely is broken (5xx with no Retry-After), we keep retrying every concurrent request. Nexus's `BlockingHttpClient` trips on the *first* such failure and re-tests with a single HEAD probe at expiring intervals. That's the missing piece.

### The cooldown path has correctly become cache-miss-only

Track 5 Phase 1A is the right move: `verifyAndServePrimary` checks storage first; only on cache miss does it run `evaluateCooldownOrProceed`. For cached artifacts: zero cooldown overhead. For cache-miss: one DB lookup via `CooldownService.evaluate` plus the inspector check.

The reproduction's "55s when throttled" presumably includes the cooldown DB call on every cache miss × N misses. With 50–150 misses on a cold walk that's 50–500 ms of DB latency at typical PG round-trips. Not the dominant factor.

### Pantera's observability is genuinely good

`MicrometerMetrics.recordProxyPhase` instruments every step of the request lifecycle with a per-phase nanosecond timer, broken out by repo. This is better than what either Artifactory or Nexus exposes by default. **An operator with the phase histogram in Grafana can localise the dominant phase in the 55s without needing to add more code.** This should be the first action item before any code change: pull the M6 perf-gate dashboard and see which `proxy_phase_duration_seconds` is the biggest bucket. If it's `cache_first_flow`, then G7 is confirmed primary. If it's `cooldown_and_fetch_miss` and the cooldown service is slow, that changes the priority order.

## Summary table

| Gap | Area | Class | Cost (per resource) | 55s severity |
|-----|------|-------|---------------------|--------------|
| G1 | Slice pipeline | divergent | 80–250 ms (non-Maven) | primary |
| G2 | Cache hierarchy | divergent | 0 | unrelated |
| G3 | Single-flight | aligned | 0 | unrelated |
| G4 | Negative cache | aligned | 0 | unrelated |
| G5 | Conditional metadata fetch | **missing** | 60–90 ms × 5–15 | contributing |
| G6 | Upstream HTTP client / circuit breaker | **missing** | 0 happy, catastrophic in 429 storm | primary |
| G7 | Streaming vs buffering | **broken** (non-Maven), aligned (Maven primary) | 50–200 ms per non-tee resource | primary |
| G8 | Metadata stale-while-revalidate | **missing** | (subsumed by G5) | contributing |
| G9 | Storage layout | divergent | 0 | unrelated |
| G10 | Group resolution | aligned | 0 | unrelated |
| G11 | Observability | aligned | 0 | unrelated |
| G12 | Throttling defence stack | **broken** (layer 4, 5, 7 incomplete) | catastrophic in 429 storm | primary |

The four gaps marked **primary** in the 55s severity column — G1/G7 (sequential write-then-read on non-Maven paths), G6 (no circuit breaker), G12 (defence stack incomplete) — are tightly correlated. The dominant root cause is the absence of a circuit breaker combined with the residual amplification from per-resource overhead on non-Maven paths. Fix those and the reproduction's wall-clock should fall into the 15–20 s range; tightening conditional requests (G5/G8) and uniform streaming (G7 generalisation) closes the remaining gap to direct mvn (~13 s).
