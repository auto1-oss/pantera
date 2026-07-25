# Upstream Revalidation Contract

> **Guide:** Developer Guide | **Section:** Upstream Revalidation

Every proxy adapter periodically has to decide "is my cached copy of this metadata document still good?" This page is the one contract every format follows for that decision (WS6.0, 2.3.0). Maven's `MetadataCache` is the reference implementation — read it first if you're implementing this for a new format.

---

## The contract

1. **TTL-gate the check, not the request.** A cached metadata document is served immediately while it's within its TTL — no upstream call at all.
2. **On TTL expiry, issue a conditional request, never a blind re-fetch.** Send `If-None-Match` with the stored upstream `ETag` and/or `If-Modified-Since` with the stored upstream `Last-Modified`.
3. **On `304 Not Modified`:** refresh the TTL / freshness marker only. Do **not** re-parse or re-persist the body — the whole point is that a same-content refresh costs a header round trip, not a full re-download-and-reparse.
4. **On `200 OK`:** replace the cached bytes, capture the new validators (`ETag` / `Last-Modified`) for the next cycle, and re-parse.
5. **On upstream failure (5xx, timeout, or a marked circuit-open 502 — see `UpstreamCircuitOpenException`):** for a **resolution-critical surface** (anything a client needs to resolve a dependency — package metadata, module version lists, packument JSON), serve the stale cached copy rather than failing the request. A public-registry blip must not break dependency resolution for an artifact that's already fully cached — that's the "offline-safe" claim this project makes.
6. **Log every degraded serve.** A stale-serve is a state transition per the project's logging discipline (`event.action=serve_stale`, `event.outcome=success`, `event.reason=upstream_unavailable`) — counters alone are invisible during an incident.

What this contract does **not** cover: negative caching (WS5), or the two circuit breakers (`AutoBlockRegistry` / `UpstreamCircuitBreaker` — see the module map in `CLAUDE.md`). A conditional-revalidation `304` still has to respect an advanced cooldown cutoff (WS5.2) — "upstream unchanged" and "cooldown-filtered view is unchanged" are different questions; the cooldown filter re-runs on every serve regardless of whether the underlying cache hit was a 304 or a fresh 200.

---

## Reference implementations

| Format | Conditional-refresh (upstream side) | Client-facing 304 | Serve-stale-on-outage |
|---|---|---|---|
| Maven | `MetadataCache` + `CachedProxySlice.fetchMetadata` — `If-None-Match`/`If-Modified-Since` on refresh | `CachedProxySlice.buildMetadataResponse` — Pantera-computed content-hash ETag, honors client `If-None-Match` | `MetadataCache`'s TTL-expired-but-present entry |
| Go `@v/list` / `@latest` | `GoMetadataBaseLoader` (WS4-go.2) — TTL cache + single-flight over the raw base document | N/A (no client-conditional surface for this endpoint) | `GoMetadataBaseLoader.staleFallback` |
| npm packument | `RxNpmProxyStorage.save` persists the upstream ETag into `meta.meta`; `NpmProxy.conditionalRefresh` sends `If-None-Match` on the stale-while-revalidate background refresh | N/A (abbreviated-metadata path has its own derived-ETag 304 — see `DownloadPackageSlice.serveAbbreviated`) | `NpmProxy` serves the pre-refresh stale copy immediately (SWR), refresh runs in the background |
| Composer packument | `CachedProxySlice.revalidateOrRefresh` / `touchCache` (WS4-composer.7) — `If-Modified-Since` against the captured `lastModifiedStore` entry | `CachedProxySlice.buildMetadataResponse` (WS6.2) — emits the captured `Last-Modified`, honors client `If-Modified-Since` | Cache-first check in `checkCacheFirst` serves whatever is on disk regardless of upstream reachability; TTL-expired entries still get a background revalidation attempt |
| PyPI JSON API (`/pypi/<pkg>/json`) | `PypiJsonBaseLoader` (WS6.3) — TTL cache + single-flight over the raw base document, mirrors `GoMetadataBaseLoader` | N/A | `PypiJsonBaseLoader.staleFallback` |
| Composer root (`/packages.json`, `/repo.json`) | `ComposerRootBaseLoader` (WS6.3) — TTL cache + single-flight over the raw root document, mirrors `GoMetadataBaseLoader` | N/A | `ComposerRootBaseLoader.staleFallback` |

---

## Adding this to a new resolution surface

If you're bringing an uncached-and-upstream-coupled resolution surface under this contract (the WS6.3 "audit" pattern — the same fix shape for npm's `/latest` dist-tag shortcut, Go's `@v/list`/`@latest`, and PyPI's JSON API):

1. **Pick a TTL cache control.** Most formats already have one (`CacheTimeControl` — every adapter's own copy, keyed off `Storage#metadata`'s `updated-at`). Reuse it; don't invent a new TTL mechanism.
2. **Wrap the raw upstream fetch in a single-flight gate**, keyed by request path — `com.auto1.pantera.http.resilience.SingleFlight` (pantera-core, shared). Concurrent cold misses for the same path must collapse onto one upstream call.
3. **Cache the *unfiltered* document.** Cooldown/version filtering runs per-request over the cached base bytes — never bake a cooldown decision into the cached artifact, or a cooldown lift/re-block won't take effect until the next TTL boundary.
4. **On upstream failure with nothing cached: forward the upstream's status, not a fabricated one.** Only a genuinely stale-but-present copy gets served as a degraded response; a cold miss with a dead upstream is a real failure and must be reported as one (never synthesize a 404 here — see the negative-cache-poisoning rule in `CLAUDE.md`'s circuit-breaker table).
5. **Namespace your cache keys** if you're sharing a `Storage`/`Cache` with an existing per-format index cache (e.g. PyPI's `PypiJsonBaseLoader` prefixes its keys with `json-api/` so they never collide with the Simple-API index cache entries in the same repository storage).
6. **Test with invocation counts, not timers.** Prove "second call within TTL makes zero upstream calls," "TTL-expired + upstream failure serves the stale copy and logs `serve_stale`," and "N concurrent cold misses collapse to one upstream call" — see `GoMetadataBaseLoaderTest` / `PypiJsonBaseLoaderTest` for the pattern (a `FakeMetaStorage` decorator lets tests stamp a controllable `updated-at` instant instead of sleeping past a real TTL).

---

## Related Pages

- [Caching](caching.md) -- The L1 Caffeine + L2 Valkey pattern for in-process/cluster caches (a different concern — that page is about caching *derived* values like auth decisions, not upstream metadata documents).
- [Cooldown](cooldown.md) -- How per-version filtering composes with the cached base document.
- [Fault Model](fault-model.md) -- How upstream failures map to client-facing responses when nothing is cached to fall back to.
- Runbook: [`PanteraConditionalGetHitRateLow`](../runbooks/low-conditional-get-hit-rate.md) -- what to do when the 304 hit rate drops.
