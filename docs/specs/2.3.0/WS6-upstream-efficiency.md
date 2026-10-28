# WS6 — Upstream Efficiency & Resolution Availability

- **Status:** 📝 DRAFT
- **Depends on:** none; overlaps WS4-go (Go `@v/list`/`@latest` caching lives there — this spec owns the cross-format revalidation contract)
- **Blocks:** the "survives upstream outages" claim
- **Decision-gated:** no
- **Size:** M

## 1. Problem & goal

Two related weaknesses: (a) proxies **re-download** full metadata on every TTL boundary instead of a cheap conditional `304`, wasting upstream bandwidth + CPU at scale; and (b) some **resolution surfaces are uncached and upstream-coupled**, so a public-registry blip breaks dependency resolution even for artifacts already fully cached — contradicting the offline-safety the project advertises.

**Goal:** every proxy revalidates upstream conditionally where the protocol allows it (cheap `304`s instead of full re-downloads), and every resolution surface a client needs to resolve a dependency is cache-backed and **serves stale on upstream failure**.

## 2. Current state (evidence)

1. **npm never revalidates upstream conditionally (dead 304 path).** `HttpNpmRemote.loadPackage` extracts the upstream ETag (`:73`) but `RxNpmProxyStorage.save` persists metadata via the ctor that **omits** it (`RxNpmProxyStorage.java:113-118`), so `meta.meta` never carries `upstream-etag`; `NpmProxy.conditionalRefresh` gates on `upstreamEtag().isPresent()` (`NpmProxy.java:506`) → always false → every 12 h TTL boundary does a **full re-download + full re-parse** of the entire packument even when unchanged. The whole conditional-request/304 code path is dead.
2. **Composer stores `Last-Modified` but never revalidates.** `CachedProxySlice` records upstream `Last-Modified` into `lastModifiedStore` (`:568-573`) but **never reads it**; there is no `If-Modified-Since` request and no `304` handling → every `composer update` re-downloads full packument bodies.
3. **Go `@v/list` and `@latest` are never cached → resolution breaks on upstream outage.** Both handlers hit upstream unconditionally (`GoListHandler.java:185`, `GoLatestHandler.java:194`); the orphaned `CacheTimeControl` TTL cache is unreachable. `go get`, `go get -u`, `go list -m -versions` fail when upstream is down even for fully-cached modules. **(The fix lives in WS4-go; this spec owns the shared "cache resolution surfaces, serve stale on failure" contract it instantiates.)**
4. (Maven metadata already does conditional GET + SWR correctly — it's the reference pattern to generalize.)

## 3. Target design

### WS6.0 — A shared upstream-revalidation contract
Define the pattern once (Maven's `MetadataCache` + `buildMetadataResponse` conditional GET is the reference): on a cached-metadata TTL boundary, issue a conditional upstream request (`If-None-Match` with the stored upstream ETag, and/or `If-Modified-Since` with the stored `Last-Modified`); on `304`, refresh the TTL without re-downloading/re-parsing; on `200`, replace + re-parse. On upstream failure (5xx / timeout / breaker-open), **serve the stale cached copy** rather than failing the request, for resolution-critical surfaces. Document it in `docs/developer-guide/` so every adapter follows it.

### WS6.1 — npm: persist the upstream ETag → revive conditional refresh
Thread `pkg.meta().upstreamEtag()` into the `RxNpmProxyStorage.save` ctor (`:113`) so it lands in `meta.meta`; `conditionalRefresh` then sends `If-None-Match` and short-circuits on `304`. Highest-leverage single change here — it turns every 12 h packument refresh from a full multi-MB download+parse into a cheap conditional. (Also removes the CPU half of WS3.5's motivation on the steady-state path.)

### WS6.2 — Composer: honor `If-Modified-Since` / emit `304`
Read the already-captured `lastModifiedStore` (`CachedProxySlice.java:568-573`); on refresh send `If-Modified-Since`; on upstream `304` refresh the TTL without re-download; emit `Last-Modified` + honor client `If-Modified-Since` → `304` on the serve side too. Cross-referenced from WS4-composer (§ conditional requests) — owned here for the shared mechanism.

### WS6.3 — Resolution-surface caching + serve-stale (contract instantiation)
The concrete Go `@v/list`/`@latest` caching is specified in **WS4-go**; this spec requires that its implementation follow WS6.0 (TTL cache + serve-stale-on-upstream-failure + single-flight). Audit the other formats for any resolution surface that is uncached-and-upstream-coupled (npm `/latest` shortcut, PyPI `/pypi/<pkg>/json`, Composer root) and bring each under the contract.

## 4. Implementation plan (ordered)

1. **WS6.0** write the contract doc (short; unblocks consistent implementation).
2. **WS6.1** npm ETag persistence (small, highest leverage).
3. **WS6.2** Composer conditional requests.
4. **WS6.3** audit + bring stragglers under the contract (coordinate with WS4-go for Go).

## 5. Acceptance criteria

1. **npm conditional refresh works:** with an unchanged upstream packument, a post-TTL refresh issues a conditional request and gets `304`, and Pantera does **not** re-download or re-parse the body (invocation-count assertion on the upstream client + parser).
2. **Composer conditional refresh works:** an unchanged upstream metadata doc refreshes via `If-Modified-Since`→`304` with no re-download; a client `If-Modified-Since` on a warm doc gets `304`.
3. **Serve-stale on outage:** with upstream forced to 5xx/timeout, a resolution request for a cached module/package still succeeds from the stale cache (breaker-open + cached-copy test).
4. **No staleness regression:** a genuinely changed upstream doc still refreshes on the next boundary (200 path) — cooldown/WS5 invalidation still applies.

## 6. Test requirements

- Invocation-count assertions (upstream calls, parser calls) prove "304, no re-download." Breaker/timeout injection proves serve-stale. Time-advanceable TTL clock, no wall-clock. Reuse format itcases for the real-client proof (`composer update` bandwidth, `go get` under a killed upstream).

## 7. Out of scope

- The Go `@v/list`/`@latest` caching *implementation* (WS4-go) — this spec only mandates its contract.
- New negative-cache behavior (WS5).

## 8. Risks & rollback

- Serve-stale must not mask a real permanent upstream removal indefinitely — bound the stale-serve window and surface a metric/log when serving stale (degraded-mode visibility is a CLAUDE.md logging requirement).
- Conditional revalidation must interact correctly with cooldown filtering (a `304` means "upstream unchanged," which must still respect an advanced cooldown cutoff — WS5.2 coordination).

## 9. Docs & observability

- `docs/developer-guide/` — the shared upstream-revalidation contract.
- Metrics (WS7): conditional-refresh `304` ratio per format (bandwidth saved), serve-stale-on-outage count, resolution-surface cache hit ratio.
- CHANGELOG under `### ⚡ Performance` + `### 🔧 Bug fixes` (Go resolution offline-safety).
