# WS5 — Cooldown-Cache Coherence

- **Status:** 📝 DRAFT
- **Depends on:** none (touches per-format cooldown wiring + the shared filtered-metadata cache)
- **Blocks:** nothing hard, but every cooldown-related correctness claim in the blog depends on it
- **Decision-gated:** no
- **Size:** M

## 1. Problem & goal

Cooldown is the release's marquee security feature, but its **caching interactions have sharp edges** that make it either hide packages that should be visible or leak versions that should be blocked. The 2.2.5 npm-ETag fix closed one edge (stale filtered packument on the client); this workstream closes the rest, server-side.

**Goal:** cooldown filtering is *coherent* — a blocked version never leaks, an unblocked/aged-out version becomes visible promptly (bounded and small), a cooldown-induced "empty" never gets negative-cached as a durable 404, and per-request cooldown evaluation is bounded and cheap.

## 2. Current state (evidence)

1. **PyPI negative-caches a cooldown-induced 404 → package invisible after cooldown lifts.** When every version is under cooldown, `PypiSimpleHandler.allBlockedResponse` returns 404 (`PypiSimpleHandler.java:485`), which `CachedPyProxySlice.fetchAndCache` negative-caches with no path/reason guard (`CachedPyProxySlice.java:350-361`). After the window expires there is no proxy upload to invalidate it, so the package keeps 404-ing until the global negative-cache TTL. The `X-Pantera-Cooldown: all-blocked` header is already set (`PypiSimpleHandler.java:486`) — the guard just isn't consulted. **This is the single most important cooldown-coherence bug** (silent, time-delayed, looks like "package doesn't exist"). Mirrors the "never negative-cache a transient/blocked state as 404" invariant in CLAUDE.md.
2. **npm filtered-metadata cache hides new upstream versions for ~a day.** `MetadataFilterService` caches filtered bytes with a 24 h max TTL when nothing is blocked (`MetadataFilterService.java:65,310`); it's invalidated on local upload (`UploadSlice.java:137-140`) but **not** on the proxy stale-while-revalidate background refresh (`NpmProxy.backgroundRefresh`). A version published upstream is invisible until the 12 h packument TTL **and** up to 24 h filtered-cache TTL.
3. **npm prerelease tarball cooldown parse is wrong.** `DownloadAssetSlice.cooldownRequest` derives the version via `base.lastIndexOf('-')` (`DownloadAssetSlice.java:446-451`); `pkg-1.2.3-beta.1.tgz` yields `beta.1` not `1.2.3-beta.1`, so the cooldown lookup keys on the wrong version → prerelease tarballs can bypass or misfire cooldown.
4. **Go `@v/list` evaluates cooldown for every version, unbounded.** `GoListHandler.java:326-344` has no cap (whereas `GoLatestHandler` caps at 50). A module with thousands of tags = thousands of cooldown evals per list request. (The Go caching fix is in WS4-go; the *cap* belongs here.)
5. **Composer root cooldown fan-out unbounded.** `ComposerRootPackagesHandler.java:336-359` evaluates cooldown for every inline `(pkg,version)` in a Satis snapshot in parallel — large snapshots blow up per-request. (Cap.)
6. **Per-request re-filter cost (PyPI).** `PypiSimpleHandler.processUpstream` parses JSON + fans out per-version cooldown + re-serializes on **every** request, even on a storage-cache hit — the post-filter result isn't cached (unlike Maven/npm which use `FilteredMetadataCache`). At 1000 req/s for a hot package this is per-request parse + N lookups. (Overlaps WS3.4 — cache the filtered+rewritten index together.)

## 3. Target design

### WS5.1 — Never negative-cache a cooldown 404 (PyPI; audit all formats)
Guard `CachedPyProxySlice.fetchAndCache` (and the equivalent negative-cache write path in every proxy adapter) so a 404 carrying `X-Pantera-Cooldown: all-blocked` (or any cooldown-origin marker) is **never** written to the negative cache. Add the marker to the other adapters' all-blocked responses if missing, and centralize the "is this 404 negative-cacheable?" decision (it already exists for metadata-vs-versioned coordinates — extend it to exclude cooldown-origin). Cross-check Maven/npm/Docker all-blocked paths for the same shape.

### WS5.2 — Invalidate filtered metadata on proxy refresh (npm; audit all)
When a proxy background/conditional refresh stores a **changed** packument, invalidate the `FilteredMetadataCache` for that package (call `FilteredMetadataCacheRegistry.invalidateAfterUpload`, as the local upload path already does), or key the filtered cache by the upstream content hash so a content change auto-busts it. Apply the same check to every format that has both a proxy refresh and a filtered-metadata cache.

### WS5.3 — Correct prerelease version parsing for cooldown (npm)
Parse `<pkg>/-/<file>` against the **known package name** to split name/version correctly instead of `lastIndexOf('-')`, so prerelease tarballs key cooldown on the true version. Add a regression test with `pkg-1.2.3-beta.1.tgz`.

### WS5.4 — Bound per-request cooldown evaluation (Go, Composer)
Cap the number of versions evaluated per request in `GoListHandler` (mirror `GoLatestHandler`'s cap) and in `ComposerRootPackagesHandler` (cap the inline fan-out); the newest N versions are the only ones plausibly inside the window, matching `MetadataFilterService`'s existing bounded-evaluation model. `log()` when the cap drops evaluations (no silent truncation).

### WS5.5 — Cache the PyPI filtered/rewritten index (perf; with WS3.4)
Bring PyPI onto the shared `FilteredMetadataCache` (or an equivalent per-package cache of the filtered+rewritten simple index) so the parse+filter+rewrite cost is paid once per (content, cutoff), not per request — keyed so it self-busts on content change and cutoff advance. Coordinate with WS3.4.

## 4. Implementation plan (ordered)

1. **WS5.1** negative-cache-cooldown-404 guard (highest correctness value, small).
2. **WS5.3** prerelease parse (small, isolated).
3. **WS5.2** filtered-cache invalidation on refresh.
4. **WS5.4** per-request eval caps (Go, Composer).
5. **WS5.5** PyPI filtered-index caching (with WS3.4).

## 5. Acceptance criteria

1. **Cooldown 404 not durably cached:** a fully-blocked PyPI package returns 404 while blocked, then becomes installable on the **next** request after the window/unblock — not after the negative-cache TTL (time-advanceable clock test + invocation assertion that the negative cache was never written for the cooldown 404). Repeat for any other format with an all-blocked path.
2. **New version visible after refresh:** with npm cooldown active and nothing blocked, publishing a new upstream version and triggering a proxy refresh makes it visible on the next request (no 24 h wait) — clock/refresh test.
3. **Prerelease cooldown correct:** `pkg-1.2.3-beta.1.tgz` is blocked/allowed identically to the packument's decision for `1.2.3-beta.1` (not `beta.1`).
4. **Bounded evaluation:** a Go `@v/list` / Composer root with thousands of versions evaluates at most the cap, and logs the truncation; result correctness matches evaluating the newest-N.
5. **No leak regression:** a blocked version is still absent from every metadata surface after these changes (golden tests preserved).

## 6. Test requirements

- Use a **time-advanceable cooldown clock** (inject the clock; never `Thread.sleep`) so block→age-out transitions are deterministic. Invocation-count assertions for "negative cache not written" and "filter evaluated once." CLAUDE.md doctrine — no wall-clock.
- Reuse the format itcase clients where a real client best proves the fix (e.g. `pip install` of a just-unblocked package).

## 7. Out of scope

- The client-side npm ETag staleness (already fixed in 2.2.5).
- Changing cooldown *policy* (windows, per-repo overrides) — this is caching coherence only.

## 8. Risks & rollback

- WS5.1 must not accidentally stop negative-caching genuine 404s (that would reopen the thundering-herd problem WS's negative cache exists to solve) — the guard keys strictly on the cooldown marker, verified by tests on both paths.
- WS5.2 cache-busting on refresh must not cause a filter recompute storm — pair with WS5.5 caching.

## 9. Docs & observability

- Metrics (WS7): cooldown-origin 404 count (and confirmation they're not negative-cached), filtered-cache invalidations on refresh, per-request eval-cap truncations.
- `docs/cooldown-metadata-filtering.md` — correct the caching/coherence description (and remove any "one-hour time bucket" claim if present anywhere — that mechanism does not exist in the code).
- CHANGELOG under `### 🔧 Bug fixes` + `### 🔒 Security` (the leak-prevention angle).
