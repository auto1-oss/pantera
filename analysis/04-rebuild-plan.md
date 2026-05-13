# Phase 6 — Rebuild plan

The findings in `03-findings.md` are addressable with targeted fixes inside
the current architecture (and the immediately-actionable per-finding fixes
are landed as separate commits on this branch). This document covers the
larger architectural changes that the findings imply for 2.2.0 and beyond.

## Principle: outbound is sacred

Pantera's core promise to its upstreams is "we will not abuse you." Every
outbound request site must funnel through one rate-aware, observable
choke point. The current code has at least four sites that issue outbound
HTTP independently:

1. `BaseCachedProxySlice.fetchAndCache` (sidecar paths, metadata, non-Maven proxies)
2. `CachedProxySlice.fetchVerifyAndCache` / `fetchSidecar` (Maven primary path)
3. `PrefetchCoordinator.fireUpstream` (speculative prefetch)
4. `MavenHeadSource.fetch` (cooldown HEAD)
5. `MavenGroupSlice.tryMembersSequentially` and `GroupResolver.querySequentially`
   (group fanout — though these delegate to (1)/(2) via member slices)

Each carries its own retry/timeout/error handling. None share a rate
limiter, a 429 backoff, or amplification metrics.

The rebuild target is **one slice decorator at the HTTP-client layer that
every outbound call must pass through**, with mandatory features:

- Per-upstream-host rate limiter (token bucket).
- Per-upstream-host 429/503 cooldown gate honouring `Retry-After`.
- Per-upstream-host circuit breaker on consecutive failures.
- Per-upstream-host outbound-request counter, by outcome.
- Per-request `caller_tag` (foreground / prefetch / cooldown / metadata
  refresh / fanout) for the amplification-ratio metric.

## What to delete outright

1. **Speculative prefetch (`com.auto1.pantera.prefetch.*`)** — delete the
   subsystem entirely. The Phase 13.5 CHANGELOG entry already documents
   that net wall-time benefit on the cold benchmark is negligible (3.21 s →
   3.68 s p50 for npm; 13.63 s → 13.34 s for Maven, both within stdev). The
   downside (rate-limit blowback) is documented. The feature is not
   worth its cost.

   Files to delete:
   - `pantera-main/src/main/java/com/auto1/pantera/prefetch/` (entire package)
   - `pantera-main/src/main/java/com/auto1/pantera/prefetch/parser/`
   - The wiring in `VertxMain.java:481-1002`.
   - `RepoConfig.prefetchEnabled`.
   - The UI Pre-fetch panel (`pantera-ui` repo Pre-fetch panel).
   - All tests under `pantera-main/src/test/java/com/auto1/pantera/prefetch/`.

   Don't replace with anything yet. If the team wants warmup behaviour
   later, do it from observed-coordinate data (the artifact event stream
   we already write) — see Finding #1 long-term fix.

2. **`ProxyCacheWriter.streamThroughAndCommit`'s eager `.sha1` fetch** —
   remove `sidecars.put(ChecksumAlgo.SHA1, ...)` from `CachedProxySlice
   .fetchVerifyAndCache` (line 692-694). The `.sha1` is served on demand
   by `ChecksumProxySlice`. The locally-computed digest in
   `DigestComputer` is the source of truth for our cache; we don't need
   the upstream `.sha1` to validate, only to serve back to the client if
   it asks. Generate the `.sha1` from our own digest when a client requests
   it and our primary is cached.

3. **`MavenSiblingPrefetcher`** — already deleted in `2a21f982c` (today).

4. **The class Javadoc claim in `BaseCachedProxySlice` line 78** ("step 4:
   Deduplicate concurrent requests for same path") — either fix the
   implementation (Finding #2 short-term fix) or update the doc. We do
   both: fix the impl AND update the doc.

## New request lifecycle (cold-miss Maven primary)

```
client GET /maven-group/groupId/artifactId/x.y/foo-x.y.pom
  │
  ▼  (Vert.x event loop)
GroupResolver.resolve
  ├─ negative-cache check     (Caffeine, microsecond)
  ├─ memberPin check          (Caffeine, microsecond)
  ├─ ArtifactIndexCache lookup (Caffeine L1; if miss, async DB query)
  ├─ if Hit: targeted local read (sequential walk over hit-set members)
  ├─ if Miss: proxy-only fanout, gated by SingleFlight<artifactName>
  │     └─ sequential walk over proxy members
  │           └─ member.response → CachedProxySlice (Maven)
  │
  ▼
CachedProxySlice.verifyAndServePrimary
  ├─ storage.exists?  → YES → serveFromCache  (zero upstream I/O, DONE)
  │                     │
  │                     ▼
  │                     evaluateCooldownOrProceed (CACHE_ONLY mode)
  │                       └─ if no cached publish-date: defer cooldown
  │                          to post-fetch (use upstream Last-Modified)
  │                       └─ if cached: evaluate locally, pure-local
  │
  ▼ (cache miss)
SingleFlight<repoName,Key>.load    ◀── new placement
  │
  ▼  (one loader per (repo, key); concurrent callers all share)
RateLimitedClient.fetch(upstreamHost, path)  ◀── new decorator
  ├─ token-bucket acquire (host=repo1.maven.org, 20 req/s default)
  ├─ if 429-gate-open: complete with 503 + Retry-After, skip upstream
  ├─ HTTP/2 stream via Jetty
  ├─ outcome metric (status / latency / amplification-tag=foreground)
  │
  ▼
ProxyCacheWriter.streamThroughAndCommit
  ├─ tee body to (a) client response (b) temp file + digests
  ├─ NO eager sidecar fetch (deleted)
  ├─ on stream completion: persist primary; write sidecars derived from
  │  digests; record artifact event with `Last-Modified` from upstream
  └─ commit
```

For metadata refresh:

```
metadata refresh request
  └─ MetadataCache.load (SWR)
       └─ on soft-stale: background refresh via SingleFlight<repoName,metaPath>
            └─ RateLimitedClient.conditionalFetch(upstreamHost, path, lastModified)
                 ├─ sets If-Modified-Since: <last-seen>
                 ├─ if upstream 304: extend stored entry's freshness, no body
                 ├─ if upstream 200: overwrite stored entry
                 └─ if upstream 429: skip refresh, keep stale entry served, log
```

For cooldown:

```
publish-date lookup
  └─ DbPublishDateRegistry (CACHE_ONLY mode preferred)
       └─ if miss + first-fetch: extract from primary's response Last-Modified
          AFTER the body arrives; reject pre-commit if blocked
       (no HEAD source; deleted MavenHeadSource if cooldown-strict deployments
        don't need it)
```

## Component choices

For 2.2.0 GA (rebuild scope kept tight):

- **HTTP server:** Vert.x 4.5 (unchanged).
- **HTTP client:** Jetty 12 + `RateLimitedClient` decorator (new). The
  decorator owns the token bucket + 429 gate + outcome metrics.
- **Async runtime:** `CompletableFuture` primary, RxJava2 retired from
  hot paths (already in-progress).
- **Storage abstraction:** unchanged.
- **Dedup primitive (single-flight):** `SingleFlight` (already exists),
  used at the **request-path key** level — one entry per `(repoName,
  key)` not at the cache-write key level. One instance per `Slice`
  hierarchy; shared between group-fanout and proxy-fetch sites so they
  converge.
- **Caches:** Caffeine + Valkey two-tier, unchanged.
- **Retry/backoff strategy:** opt-in retry per outbound site, never on
  prefetch. Foreground retries: at most 1 retry on 5xx with 100 ms
  jitter; never retry 429 (let the client retry after `Retry-After`).
- **Conditional requests:** `ConditionalRequestSlice` decorator
  (new) at the HTTP-client layer; reads/writes a small Caffeine cache
  of `(URL → Last-Modified, ETag)` keyed per upstream host. Applied to
  every metadata path uniformly.
- **Observability:** `pantera_upstream_requests_total{upstream_host,
  caller_tag, outcome}`, `pantera_upstream_amplification_ratio` recording
  rule, `pantera_proxy_429_total` counter. Wire into the existing
  Prometheus + Grafana boards.

## Migration plan (for the in-flight 2.2.0 release)

Order of operations matters: each step measures the metric the previous
step relied on.

1. **Land the observability metrics first** (Finding #8 short-term fix).
   Without them, the subsequent fixes cannot be verified.
2. **Disable prefetch by default** (Finding #1 short-term fix #1). Set
   `RepoConfig.prefetchEnabled = false`. Operators who want it enable
   per-repo with a documented warning.
3. **Land the rate limiter + 429 gate** (Finding #3 + Finding #9 short-term
   fixes). Default 20 req/s per upstream for Maven Central; 30 for npm.
4. **Fix the single-flight placement** (Finding #2 short-term fix).
5. **Remove the eager `.sha1` fetch** (Finding #4 short-term fix).
6. **Land conditional requests** (Finding #5 short-term fix).
7. **Audit** the cooldown HEAD path (Finding #6). Pick the policy: either
   keep the cooldown HEAD-per-first-fetch (status quo, document the
   trade-off explicitly) or switch to header-based extraction
   (long-term fix).
8. **Delete the prefetch subsystem** as a follow-up release (2.2.1 or
   2.3.0), once the rate limiter has caught any latent amplification.
9. **Update the Developer Guide** (Finding #10).

The first six steps fit in one focused release pass. Steps 8–9 are
follow-ups.

## Perf-test plan that would have caught this

See `analysis/05-perf-harness.md`.

## What we are NOT changing in 2.2.0

- The slice composition pattern.
- Vert.x event-loop topology.
- The L1/L2 cache strategy.
- The Quartz + JDBC cluster scheme.
- The auth subsystem.
- The cooldown DB schema.

These are correctly designed and out of scope for the cold-miss / 429
fixes. Future major versions may revisit them.
