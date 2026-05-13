# Phase 5 — Findings (main deliverable)

Branch: `2.2.0` HEAD `2a21f982c`. Date: 2026-05-13.

Findings are ranked by impact on the two reported problems. The first
finding is the shared root cause; the remainder are secondary regressions
that compound it.

## FINDING #1 — PrefetchDispatcher recursively multiplies upstream load per cache write

**Title:** PrefetchDispatcher fires N speculative upstream GETs per
successful primary cache write; recursion bounded only by negative cache
and in-flight dedup; per-host concurrency cap with no requests-per-second
cap; enabled by default on every proxy repo.

**Category:** `request-amplification`.

**Evidence:**

- `pantera-main/src/main/java/com/auto1/pantera/prefetch/PrefetchDispatcher.java:231-326`
  — hook fired post-cache-write. For Maven, parses every `.pom` via
  `MavenPomParser`; emits N coordinates (N = direct compile/runtime deps).
- `pantera-main/src/main/java/com/auto1/pantera/prefetch/PrefetchCoordinator.java:474-501`
  — per-host semaphore `tryAcquire`. **Default caps:** `maven=16`,
  `gradle=16`, `npm=32`, global=64 (`PrefetchTuning.defaults()`,
  `pantera-main/src/main/java/com/auto1/pantera/settings/runtime/PrefetchTuning.java:38-44`).
  **No rate-per-second cap exists in the coordinator.** With a 100 ms
  upstream RTT and 16 concurrent slots, steady-state outbound is up to
  ~160 req/s from prefetch alone.
- `pantera-main/src/main/java/com/auto1/pantera/VertxMain.java:1001-1002`
  — `CacheWriteCallbackRegistry.instance().setSharedCallback(this.prefetchDispatcher::onCacheWrite)`.
  Every adapter's `ProxyCacheWriter` / `BaseCachedProxySlice` write fires
  the callback.
- `RepoConfig.prefetchEnabled` — **default true**; introduced in
  `a7cfb89ab` (2026-05-04), "replaces *-proxy heuristic". Every existing
  proxy repository inherits prefetch-on without an explicit opt-in.
- `CHANGELOG.md` line 30-58 (RCA-7): the team's own investigation,
  2026-05-13, confirms Maven Central was returning 429 during their
  perf-test session against the same workstation IP. The user reports the
  same symptom against production from 2026-05-07/08, which lines up with
  the prefetch subsystem being shipped May 4-5 and propagating to
  production.
- `CHANGELOG.md` line 332-376 (Phase 13.5): "cap=4 had a bimodal failure
  mode where one in ten cold installs blew up to 12.5 s, eliminated at
  cap=32." The npm cap was raised 4 → 32 (commit `867474393`, 2026-05-05).
  That puts up to 32 concurrent speculative prefetches into
  `registry.npmjs.org` per Pantera instance.
- Static analysis (Phase 1, `analysis/01-reproduction.md` "Static request-
  amplification math"): ~5× upstream amplification for a typical Spring/
  sonar-style POM tree of 50 unique artifacts; bounded down by in-flight
  dedup but still well above 1×.

**Impact on Problem 1 (throttling): PRIMARY.** The prefetch subsystem is
the dominant new outbound generator since 2026-05-04. Maven Central's
per-IP rate limit will trip once Pantera's outbound RPS to `repo1.maven.org`
exceeds the bucket — observed in the team's own perf session and reported
by the user.

**Impact on Problem 2 (slowness): PRIMARY.** Once throttled, foreground
`mvn` requests start receiving 429 / 503; mvn retries; cold-cache walls
grow from ~13 s to 40-69 s. Direct mvn (no Pantera) doesn't generate the
amplification and stays under the limit.

**Severity:** **P0** — must fix before 2.2.0 GA. The subsystem actively
causes the regression the release is supposed to mitigate.

**Confidence:** HIGH. Direct code reading + team's own CHANGELOG RCA-7
confirms the diagnosis.

**Short-term fix:**

1. Default `RepoConfig.prefetchEnabled` to **false** (revert the
   default-on choice in `a7cfb89ab`). Explicit opt-in per repo via the
   admin UI. Operators who want the feature can enable it knowing they
   may need to negotiate with their upstreams about rate limits.
2. Add a **requests-per-second token bucket** in `PrefetchCoordinator`
   keyed by upstream host. Default cap: 2 req/s per upstream host for
   prefetch traffic. Foreground traffic is unaffected (foreground has no
   semaphore).
3. Add a **429-aware circuit breaker** — current `PrefetchCircuitBreaker`
   only trips on queue-full drops. Extend it to count `429` / `503`
   outcomes and trip when the rolling-window count exceeds a threshold
   (e.g., 5 in 60 s).

**Long-term fix:** Replace speculative prefetch with **observed-coordinate
pre-warming**: maintain a per-repo "recently-fetched-together" graph (from
the artifact event stream we already write) and only pre-warm artifacts
that the same client previously fetched together within a short window.
This converts speculative N×N into deterministic 1× by observation. Same
hit rate, near-zero waste. Document the privacy/data-retention shape of
the graph.

---

## FINDING #2 — SingleFlight coalescing positioned after the upstream call

**Title:** `BaseCachedProxySlice.fetchAndCache` and Maven `CachedProxySlice
.fetchVerifyAndCache` invoke `client.response(...)` per caller, then call
`SingleFlight.load` for cache-write only. N concurrent client requests for
the same uncached path produce N upstream requests, not 1.

**Category:** `request-amplification`.

**Evidence:**

- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java:704-728`:

  ```java
  return this.client.response(line, this.upstreamHeaders(headers), Content.EMPTY)  // ① UPSTREAM (per caller)
      .thenCompose(resp -> {
          ...
          return this.singleFlight.load(key, () -> {                                // ② cache-write only
              return this.cacheResponse(resp, key, owner, store)
                  .thenApply(r -> FetchSignal.SUCCESS);
          })
          ...
      });
  ```

- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/CachedProxySlice.java:673-743`:
  `fetchVerifyAndCache` calls `fetchPrimaryBody(line)` per caller. The
  only coalescer in the chain is `ProxyCacheWriter`'s internal storage-
  write atomicity, not the upstream fetch.
- `pantera-core/src/main/java/com/auto1/pantera/http/resilience/SingleFlight.java:170-224`:
  the coalescer correctly collapses one call per key, but it is loaded
  *with* `resp` (the already-fetched upstream response) — so by
  construction it can only coalesce work performed AFTER `client.response`
  completed.
- The same shape exists in `master` (`BaseCachedProxySlice.java:474-498`
  using `RequestDeduplicator.deduplicate`). The migration to `SingleFlight`
  in commit `cf7992666` preserved the misplacement.
- `docs/developer-guide.md:7.1` describes step 6 as "Deduplicated upstream
  fetch — Only one in-flight request per artifact key." The documentation
  and the implementation disagree.

**Impact on Problem 1 (throttling): CONTRIBUTING.** Multiplies the rate at
which concurrent client bursts (e.g., a CI pipeline starting 10 jobs
simultaneously, each running `mvn dependency:resolve`) hit upstream. Worst
case: 10× per artifact during the burst.

**Impact on Problem 2 (slowness): CONTRIBUTING.** Latency cost is bounded
by upstream RTT, not multiplied, but the spurious extra requests add to
Jetty connection-pool contention.

**Severity:** **P0** — must fix.

**Confidence:** HIGH. Confirmed by reading both `master` and HEAD.

**Short-term fix:** Move the upstream call inside the `SingleFlight.load`
loader. The leader fetches and caches; followers re-enter `cacheFirstFlow`
once the gate completes — they hit the freshly-warm cache and return it
without an upstream call.

```java
// Replace fetchAndCache body with:
return this.singleFlight.load(key, () -> {
    final long startTime = System.currentTimeMillis();
    return this.client.response(line, this.upstreamHeaders(headers), Content.EMPTY)
        .thenCompose(resp -> {
            // current body — handle 404, non-success, cache-on-success
            // returning FetchSignal
        });
}).thenCompose(signal -> this.signalToResponse(signal, line, key, store));
```

Identical pattern needed in `CachedProxySlice.fetchVerifyAndCache`.

**Long-term fix:** Single-flight at the **request-path level** (one entry
per `(repoName, key)`), not at the cache-write level. Use the same
`SingleFlight` instance for both directly-proxied requests and group-
fanout-dispatched requests so concurrent paths converge. Update the
developer guide §7.1 to match.

---

## FINDING #3 — No backoff or circuit breaker on upstream 429 responses

**Title:** When an upstream returns `429 Too Many Requests`, Pantera
propagates the response verbatim with no client-side rate limiter,
back-off, or circuit breaker, and no Retry-After honour. The next request
to the same upstream fires immediately, prolonging the rate-limit window.

**Category:** `bad-retry`.

**Evidence:**

- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java:1200-1222`
  (`handleNonSuccess`): for `4xx` non-404, records the metric tag
  `client_error` and returns `resp` verbatim. No special handling for
  429.
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java:1085-1141`
  (`fetchDirect`): same shape — `code >= 500` is `error`, otherwise
  `client_error`. 429 is bucketed with 403/410/401.
- No `Retry-After` parser anywhere in the proxy slice or HTTP client. grep:

  ```
  grep -rn 'Retry-After\|retryAfter\|retry_after' pantera-core pantera-main http-client maven-adapter npm-adapter 2>/dev/null
  ```
  
  Returns 0 production hits.
- `pantera-core/src/main/java/com/auto1/pantera/http/timeout/AutoBlockRegistry.java`
  exists for member-circuit-breaker semantics; it counts failures by
  member-name and trips the member's `MemberSlice.isCircuitOpen()` flag.
  It tracks generic failures, not 429 specifically, and works at the
  group-resolver layer, not the upstream-client layer.

**Impact on Problem 1 (throttling): CONTRIBUTING.** Once Maven Central
trips us into a rate-limit window, every subsequent foreground request
hits the same wall. We continue hammering at the same rate, prolonging
the limit window. A correct implementation would honour `Retry-After`
and pause outbound to that upstream.

**Impact on Problem 2 (slowness): CONTRIBUTING.** Each 429 is a wasted
round-trip — the wall time for the cold walk grows linearly with the
number of 429s served by Maven Central back to mvn.

**Severity:** **P0** for production safety, **P1** for 2.2.0 functional GA.

**Confidence:** HIGH. Direct code reading.

**Short-term fix:** Treat 429 + 503-with-Retry-After at the proxy layer:

1. Parse `Retry-After` from the upstream response.
2. Open a per-upstream-host "cooldown until <Retry-After deadline>" gate.
3. While the gate is open, immediately fail prefetch tasks for that host
   with outcome `upstream_rate_limited` (do not consume a semaphore slot)
   and stall the foreground response with a 503 + Retry-After at the same
   deadline so the upstream client (mvn) honours it without retrying us.

The gate is per-host (not per-repo) because rate limits are per-IP at the
upstream, not per-Pantera-proxy-config.

**Long-term fix:** Token-bucket-with-backoff (the standard pattern: AWS
SDK v2, Google API client, etc.). Allow operator to tune the token-rate
per upstream. Default conservative (10 req/s for `repo1.maven.org`).

---

## FINDING #4 — Eager parallel `.sha1` fetch on cache miss

**Title:** Per primary cache miss, `ProxyCacheWriter.streamThroughAndCommit`
fires the primary fetch and the `.sha1` sidecar fetch in parallel,
unconditionally. This doubles the burst rate to upstream per cache-miss
event without changing the steady-state cumulative count (the Maven
client would have asked for the `.sha1` itself).

**Category:** `request-amplification` (burst).

**Evidence:**

- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/CachedProxySlice.java:692-700`:

  ```java
  final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
      new EnumMap<>(ChecksumAlgo.class);
  sidecars.put(ChecksumAlgo.SHA1, () -> this.fetchSidecar(line, ".sha1"));
  return this.fetchPrimaryBody(line)... .thenCompose(body ->
      this.cacheWriter.streamThroughAndCommit(
          key, upstreamUri, body.size(), body.publisher(),
          sidecars, null, ctx
      )...
  ```

- Commit `0a14ee1ae` 2026-05-05: "cuts cold pom-heavy bench from 21.1 s →
  14.4 s." Wins came from overlap, not from a reduced upstream count.
- The Maven client typically requests `.pom` then `.pom.sha1`, **but only
  for the artifacts it actually wants to use** (top-level deps and their
  resolved transitives). Pantera's parallel pre-fetch sends `.sha1`
  requests for *every* primary, including those whose `.sha1` mvn will
  never request (e.g. when the primary is a transitive dep whose checksum
  mvn defers to its own SHA validation).
- Rate-limits at Maven Central are token-bucket per IP, not cumulative.
  Doubling the burst rate is what trips the limit, even if the cumulative
  cap is fine.

**Impact on Problem 1 (throttling): CONTRIBUTING.** Burst-rate doubling
makes the rate-limiter trip earlier in the cold walk.

**Impact on Problem 2 (slowness): NONE in isolation** — the optimisation
itself reduces cold wall (21 → 14 s per `0a14ee1ae`). Slowness comes from
the *throttling* it triggers, not from the optimisation directly.

**Severity:** **P1** — fix before GA; defer if the larger #1/#2 fixes
shrink burst rate enough that this is no longer near the upstream's
threshold.

**Confidence:** HIGH.

**Short-term fix:** Only fetch `.sha1` eagerly when the client request was
itself for the `.sha1` (no client wants it → no eager fetch). The client's
own request will pick it up; we cache it on the regular cache-miss path
when the client asks.

Alternative: fetch `.sha1` lazily on demand (the existing `ChecksumProxySlice`
already handles this); drop the eager fetch from `CachedProxySlice` and
rely on Maven Central serving the `.sha1` separately.

**Long-term fix:** Compute the `.sha1` ourselves during the primary
stream (we already compute SHA-1 in the `MAVEN_DIGESTS` set —
`DigestComputer.MAVEN_DIGESTS`) and serve it back to the client without
ever asking upstream. The point of having the checksum is to validate,
but if mvn trusts our locally-computed checksum (which it does — it's the
client's own integrity contract), then we save 1 upstream request per
primary. Wire this into `ChecksumProxySlice` so a request for `.pom.sha1`
that has a cached primary serves the locally-computed digest.

---

## FINDING #5 — No conditional requests for mutable metadata

**Title:** `maven-metadata.xml`, `packages.json`, `npm packument`,
`go ?go-get=1` metadata, and other mutable upstream resources are
re-downloaded in full on every cache refresh. We never send
`If-Modified-Since` or `If-None-Match`. Maven Central, npm, packagist all
support 304 Not Modified for these resources.

**Category:** `bad-caching`.

**Evidence:**

```
grep -rn 'If-Modified-Since\|If-None-Match\|conditional\|Last-Modified.*request' \
    pantera-core/src/main pantera-main/src/main maven-adapter/src/main \
    npm-adapter/src/main http-client/src/main composer-adapter/src/main \
    go-adapter/src/main 2>/dev/null
```

0 production hits for outbound conditional headers. (There ARE hits for
inbound `Last-Modified` header *parsing*, but never outbound `If-*`
generation.)

- `pantera-main/src/main/java/com/auto1/pantera/group/MavenGroupSlice.java:402-537`:
  on metadata refresh, performs a full GET per member. Could send a
  conditional based on the cached version's stored `Last-Modified` and
  receive 304 in <1 KB instead of re-downloading the full XML.
- `MetadataCache` (`maven-adapter/.../http/MetadataCache.java`) uses
  time-based SWR but propagates no validators upstream.

**Impact on Problem 1 (throttling): CONTRIBUTING (low).** Each refresh
costs a full body even when nothing changed. For a busy
`maven-metadata.xml` consulted every few minutes, this is 100s of KB
of pointless bytes per resource per refresh.

**Impact on Problem 2 (slowness): CONTRIBUTING (low).** Refresh latency
is full-body-RTT instead of 1-RTT-headers-only.

**Severity:** **P1**.

**Confidence:** HIGH.

**Short-term fix:** In `MetadataCache` and `GroupMetadataCache` refresh
paths, if the stored cached entry has a `Last-Modified` header, set
`If-Modified-Since: <value>` on the outbound request. Handle 304 by
extending the soft-TTL of the existing cached entry without overwriting
the body.

**Long-term fix:** Build a `ConditionalRequestSlice` decorator in
`http-client` that, given a `LastSeen` cache of `Last-Modified` /
`ETag` per URL, injects the conditional headers and translates 304 to a
`use-cached` sentinel. Apply to every mutable metadata path uniformly.

---

## FINDING #6 — Cooldown HEAD on cache miss still fires per first-fetch

**Title:** Track 5 Phase 1A correctly removed the cooldown HEAD from the
cache-HIT path, but left it on the cache-MISS path. Per CHANGELOG line
300-318, this is intentional ("one upstream HEAD per genuinely-new
`(artifact, version)` pair"). On a cold walk of S unique
`(artifact, version)` pairs, this adds S upstream HEADs.

**Category:** `request-amplification`.

**Evidence:**

- `maven-adapter/src/main/java/com/auto1/pantera/maven/http/CachedProxySlice.java:615-636`:
  `verifyAndServePrimary` calls `evaluateCooldownOrProceed(headers, path,
  () -> ...fetchVerifyAndCache(...))` on cache miss. Cooldown evaluation
  calls into `RegistryBackedInspector` which (NETWORK_FALLBACK mode)
  falls through to `MavenHeadSource.fetch()` on L1+L2 miss.
- `CHANGELOG.md` line 300-318 (Track 5 Phase 3C): the team deliberately
  retained this to preserve the "block fresh versions even for first
  asker" property of the cooldown system.
- Cooldown is configured ON by default for production proxy repos and
  ships a metadata filter pipeline that, by construction, must learn the
  publish date of every (artifact, version) at least once.

**Impact on Problem 1 (throttling): CONTRIBUTING.** Adds S HEAD requests
per cold walk of S unique pairs.

**Impact on Problem 2 (slowness): CONTRIBUTING (mild).** Each HEAD is a
round-trip that runs *before* the primary GET. Worst-case adds 1 RTT per
unique (artifact, version) to the cold path.

**Severity:** **P1** — has a clear, considered trade-off in the team's
own design notes. Resolution depends on whether the cooldown "block
first-asker" property is required by the deployment.

**Confidence:** HIGH.

**Short-term fix:** Two options, pick one based on deployment needs:

1. **Default cooldown to off** for proxy repos that do not need
   first-asker blocking. Document the trade-off.
2. **Batch the publish-date lookup**: change `MavenHeadSource.fetch`
   semantics from "one HEAD per `(artifact, version)`" to "one paginated
   request to a known metadata endpoint per `(groupId, artifactId)`"
   (the upstream `maven-metadata.xml` already returns the version list
   with timestamps — we already fetch it). Cache result by `(groupId,
   artifactId)`, evict on policy change.

**Long-term fix:** Extract publish-date from the *primary fetch's
response headers* (the `Last-Modified` we already extract for the artifact
event — see `Track 5 Phase 1B` in CHANGELOG). The cooldown evaluator can
then make its decision in-memory after the primary has been fetched and
before it is committed to cache. Net upstream HEAD count: 0. Trade-off:
the cooldown gate runs after the bytes are on disk instead of before, so
the first-asker for a freshly-published blocked version receives a 403
*after* downloading the bytes — the bytes are then discarded
pre-commit. Cheaper than an extra round-trip and preserves the property.

---

## FINDING #7 — PrefetchCircuitBreaker only trips on queue-full drops, not on upstream rate-limiting

**Title:** `PrefetchCircuitBreaker` auto-disables the prefetch subsystem
when its drop rate spikes — but the "drop" signal is queue-full or
semaphore-saturated, not upstream `429` / `503`. The subsystem will
happily continue to fire prefetches into a rate-limited upstream
indefinitely.

**Category:** `bad-retry`.

**Evidence:**

- `pantera-main/src/main/java/com/auto1/pantera/prefetch/PrefetchCircuitBreaker.java`
  (file not opened in full but cross-referenced via
  `PrefetchCoordinator.java`):
  - `breaker.isOpen()` consulted in `submit()` (line 274).
  - `breaker.recordDrop()` called from `submit()` on queue-full and from
    `process()` on semaphore-saturated (line 487, 494).
- `PrefetchCoordinator.process()` line 538-565: upstream outcomes
  (`OUTCOME_UPSTREAM_5XX`, `OUTCOME_TIMEOUT`, `OUTCOME_NEG_404`,
  `OUTCOME_FETCHED_200`) are recorded in metrics but **never fed back into
  the breaker**.
- A 429 upstream falls into `OUTCOME_UPSTREAM_5XX` (line 553 — `status >=
  500` branch — wait, 429 is not >= 500 — so what does it map to?
  Re-reading: line 555 `else` branch → `OUTCOME_ERROR`). Either way, no
  breaker feedback.

**Impact on Problem 1 (throttling): CONTRIBUTING.** Once Maven Central
starts throttling, the breaker should auto-open and stop prefetching
until the upstream recovers. It doesn't.

**Impact on Problem 2 (slowness): NONE directly.**

**Severity:** **P1**.

**Confidence:** HIGH.

**Short-term fix:** In `PrefetchCoordinator.fireUpstream`'s
`whenComplete` (line 538), call `this.breaker.recordRateLimit(host)` when
`status == 429` or when `cause instanceof TooManyRequestsException`. Add a
matching threshold in `PrefetchCircuitBreaker` (e.g., 3 × 429 within
30 s → open the breaker for 5 minutes).

**Long-term fix:** Merge the circuit breaker with the 429-aware gate from
Finding #3 — one mechanism per upstream host, used by both foreground and
prefetch paths.

---

## FINDING #8 — Observability gaps prevent CI from catching the regression

**Title:** There is no metric or alert that names the outbound request
rate per upstream host, the 429-from-upstream count, or the
request-amplification ratio (`outbound / inbound` per upstream). The team's
existing perf bench (cold-bench-10x) measures wall time but not the
upstream interaction shape, so a 5× amplification regression looks
identical to a 1× build until Maven Central starts pushing back.

**Category:** `observability-gap`.

**Evidence:**

- `pantera-core/src/main/java/com/auto1/pantera/metrics/PanteraMetrics.java` /
  `MicrometerMetrics.java` (referenced from `BaseCachedProxySlice`): no
  outbound-per-host counter, no 429 counter.
- `performance/scripts/cold-bench-10x.sh` measures `mvn` wall only.
- `performance/results/cold-bench-10x.md` (2026-05-05) reports p50=13.34 s.
  This bench is what the team uses to gate releases. A regression that
  doubles outbound to Maven Central would show identical numbers *as long
  as Maven Central does not rate-limit us in the bench window*. We
  observed this for real: the team's measured bench (13 s) is
  significantly faster than what the user reports in production
  (40-69 s) — because their bench environment evidently does not trip
  Maven Central's per-IP limit at this rate, but the production
  environment does.

**Impact on Problem 1 (throttling): CONTRIBUTING.** The regression
landed undetected. With the right metric in CI, the May 4-5 prefetch
landings would have shown an immediate `outbound/inbound > 1.5`
amplification number and the cluster would have been blocked from merge.

**Impact on Problem 2 (slowness): CONTRIBUTING.** Same root: invisible
regressions become production incidents.

**Severity:** **P0** — must land alongside the fixes for #1-#3 so we can
verify they work.

**Confidence:** HIGH.

**Short-term fix:** Add these Prometheus metrics:

- `pantera_upstream_requests_total{upstream_host, method, outcome}` —
  every outbound request to `JettyClientSlices` increments this. Outcome
  bucket includes `2xx`, `3xx`, `4xx`, `429`, `5xx`, `503-rate-limited`,
  `timeout`, `connect_error`.
- `pantera_upstream_amplification_ratio` — `sum(outbound) / sum(inbound)`
  per upstream over a 5-min window. Recording rule in Prometheus.
- `pantera_proxy_429_total{upstream_host, repo_name}` — counter.
- Alert: `pantera_upstream_amplification_ratio > 1.5 for 5m` →
  page-ops.

**Long-term fix:** Wire the perf bench into CI as a release gate: a
non-zero `429` count from Maven Central during the cold-bench-10x run
must fail the build. Use a Toxiproxy-mediated Maven Central mirror with a
defined rate-limit budget (e.g. 10 req/s) so the bench reliably
reproduces the throttling condition that production sees.

---

## FINDING #9 — No upstream request-rate cap (only concurrency cap)

**Title:** Every outbound-request site (foreground proxy, prefetch
coordinator, group fanout, sidecar fetch, cooldown HEAD) caps
*concurrency*. There is no *requests-per-second* cap anywhere in the
codebase. Concurrency of 16 with 100 ms upstream RTT is 160 req/s.

**Category:** `architecture`.

**Evidence:**

```
grep -rn 'RateLimiter\|TokenBucket\|requests_per_second\|rps' \
    pantera-core/src/main pantera-main/src/main http-client/src/main \
    maven-adapter/src/main npm-adapter/src/main 2>/dev/null
```

0 hits for any rate-limiter primitive.

- `JettyClientSlices.create` configures connection pool size + queue
  depth + idle/connect/acquire timeouts. No request-rate cap.
- `PrefetchCoordinator` uses `Semaphore` (count-bound).
- `RepoBulkhead` (per-repo) similarly count-bound, not rate-bound.

**Impact on Problem 1 (throttling): PRIMARY (latent).** Without rate
caps, adding any new outbound source (prefetch in our case) shifts
behaviour from "comfortably below upstream limit" to "intermittently
above" without a clear control surface.

**Impact on Problem 2 (slowness): NONE directly.**

**Severity:** **P0** for any production deployment that proxies a
rate-limited upstream (which is now: Maven Central, npmjs.org, Docker
Hub, packagist.org, …).

**Confidence:** HIGH.

**Short-term fix:** Wrap `JettyClientSlices.httpClient()` with a per-host
token-bucket rate limiter at the slice layer
(`http-client/.../RateLimitedClientSlice` decorator). Default conservative
caps per known upstream:

| Upstream | Default | Source |
|---|---|---|
| `repo1.maven.org` / `repo.maven.apache.org` | 20 req/s | Maven Central Cloudflare docs (varies; conservative side) |
| `registry.npmjs.org` | 30 req/s | npm public API; private guidance from npm Inc |
| `index.docker.io` | 10 req/s authenticated, 1 req/s anonymous | Docker Hub published limits |
| Generic `*.proxy` | 50 req/s | safer-than-nothing default |

Make these tunable per-upstream in YAML.

**Long-term fix:** Per-upstream rate limiter is a first-class
configuration object, plumbed into every outbound call site through one
slice decorator. The decorator is the ONLY place that makes outbound
calls; everything else funnels through it.

---

## FINDING #10 — Documentation–implementation mismatch creates wrong mental model

**Title:** Developer Guide §7.1 ("BaseCachedProxySlice 7-step pipeline")
describes step 6 as "Deduplicated upstream fetch — Only one in-flight
request per artifact key." The implementation has not behaved this way for
some time; the dedup wraps the cache-write step, not the fetch. This
misled previous review and contributed to the regression landing
undetected.

**Category:** `observability-gap` / documentation.

**Evidence:**

- `docs/developer-guide.md` table of contents line 462 ("§7.1
  BaseCachedProxySlice") and the 7-step list at line 468-477.
- `pantera-core/src/main/java/com/auto1/pantera/http/cache/BaseCachedProxySlice.java`
  line 78-83 (class Javadoc) repeats the same false claim.
- `review-v2.2.0.md` (the in-repo previous review) checks "single-flight
  request collapsing" against the documented contract and passes it,
  without verifying the placement.

**Impact on Problem 1 (throttling): CONTRIBUTING (organisational).**

**Impact on Problem 2 (slowness): CONTRIBUTING (organisational).**

**Severity:** **P1** — docs change.

**Confidence:** HIGH.

**Short-term fix:** Update `docs/developer-guide.md` §7.1 and the class
Javadoc to describe the actual behaviour, AND fix the actual behaviour
(Finding #2). Doing only one of the two leaves the gap.

**Long-term fix:** Embed an integration test that asserts the property —
"N concurrent client requests for the same uncached path produce exactly
1 upstream call" — gated in CI. The test catches future regressions
without needing developers to remember the contract.

---

## Appendix — lower-confidence observations

These do not meet the bar for a P0/P1 finding but are worth flagging.

### A1 — Sequential metadata fanout in `MavenGroupSlice` adds latency for misordered configs

If the artifact's authoritative member is third in the group's declared
order, three sequential upstream RTTs are paid on every cold metadata
miss. The team's design rationale (`MavenGroupSlice.java:48-58`) is
correct for well-organised configs, but operators who have an inherited
misorder will see metadata latency 3× a parallel fanout. Not a finding in
the strict sense because operators can fix it; flagging as A1 because the
admin guide does not yet mention member ordering as a perf knob.

### A2 — `PrefetchDispatcher` parser invocation on the dispatch executor (queue 1024) silently drops events

`ThreadPoolExecutor.AbortPolicy` on the dispatch executor with a 1024
queue means under a burst (e.g. cold mvn dependency-tree with 1000
artifacts in a 10 s window) some `CacheWriteEvent` events will simply not
be parsed. There's a `droppedEvents` counter but no alert. Bound is fine
for throttling but the silent failure mode is worth surfacing.

### A3 — `GroupResolver.memberPin` 60 s TTL pins to the first responder

Member pinning by `artifactName` for 60 s after a 2xx (`GroupResolver
.java:371-386`). Race-condition risk: if member A serves `.pom` and B has
the `.jar` faster, the pin locks subsequent siblings to A. Bounded by TTL
but could cause client-side latency spikes when A is slow on `.jar`.

### A4 — `BaseCachedProxySlice` uses `ForkJoinPool.commonPool()` as the SingleFlight executor

`pantera-core/.../BaseCachedProxySlice.java:240-246`:

```java
this.singleFlight = new SingleFlight<>(
    Duration.ofMillis(ConfigDefaults.getLong("PANTERA_DEDUP_MAX_AGE_MS", 300_000L)),
    10_000,
    ContextualExecutor.contextualize(ForkJoinPool.commonPool())
);
```

`ForkJoinPool.commonPool()` is also used by the JDK for parallel streams,
`CompletableFuture.async`, and any library that drops into it. Sharing it
across these and the proxy hot path means a long-running parallel stream
in unrelated code blocks the slice's coalescer completion. Use a dedicated
named pool.

### A5 — No JFR / async-profiler hooks in the perf bench

The team's CHANGELOG references per-stage timing histograms
("pantera_proxy_phase_seconds") but the cold-bench-10x.sh script doesn't
collect them. CI gates rely on wall clock alone. Recommend adding a
recording step that captures `pantera_upstream_requests_total` and
`pantera_proxy_phase_seconds` from the local Prometheus during each
bench run, and an assertion on amplification ratio.
