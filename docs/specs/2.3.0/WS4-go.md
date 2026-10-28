# WS4-go — Go module proxy: API completeness & hosted-write correctness

- **Status:** 📝 DRAFT
- **Depends on:** [00 — security/integrity decisions](00-security-integrity-decisions.md) (✅ signed off: **S5 = DELETE** the inert `.ziphash` claim, **S6 = WIRE** the sumdb proxy). No code dependency on WS1/WS2/WS3.
- **Blocks:** the honest "`go get` survives upstream outages" and "clients can keep sum verification on" claims in `docs/user-guide/repositories/go.md`.
- **Decision-gated:** no — the one product fork (wire-vs-delete Go integrity) is already resolved in 00.
- **Size:** M. Eight sub-items (`WS4-go.1`…`WS4-go.8`); two are M (the resolution-surface cache and the sumdb proxy), the rest S.

Genuine H1-dirhash zip verification (the honest Go archive integrity that replaces the deleted `.ziphash` claim) is a **DEFERRED L follow-on that depends on S6** — see §7. It is **not** built here.

---

## 1. Problem & goal

The Go **artifact-serving** paths (`.info`/`.mod`/`.zip`) are solid: cache-first, offline-safe, single-flighted on `.zip` (`CachedProxySlice.java:325-451,912-1109`). The weak spot is everything a Go client hits to **resolve** a version, plus two correctness/hygiene defects:

1. **Resolution surfaces are never cached in proxy mode.** `@v/list` and `@latest` are intercepted before cache-first and served live off a raw upstream fetch every time (`CachedProxySlice.java:265-290` → `GoListHandler.handle:185` / `GoLatestHandler.handle:194`). So `go get`, `go get -u`, and `go list -m -versions` **hard-fail the instant upstream is unavailable — even for a fully-cached module.** This directly contradicts the doc promise "Cached bytes survive upstream outages" (`go.md:74`). The 12 h TTL machinery meant to fix this (`CacheTimeControl`, `CachedProxySlice.isMetadataPath:857-859`, `cacheControlFor:833-848`) is **orphaned** — unreachable because the two handlers intercept first.
2. **Hosted `@latest` picks the wrong version** — lexicographic `.max(Comparator.naturalOrder())` (`LatestSlice.java:71-73`) makes `v0.9.0 > v0.10.0`. A correct `VersionComparators.semver()` already exists in-tree and is used by the *proxy* fallback (`GoLatestHandler.java:398`) — just not here.
3. **The `.zip` integrity guarantee is inert** (S5). The stream-through path fetches a `.ziphash` sidecar that **does not exist in the GOPROXY protocol** and whose format (`h1:` dirhash) is not a zip-byte SHA anyway (`CachedProxySlice.java:997`); the class javadoc claims "the same primary+sidecar integrity guarantee the Maven adapter received" (`:74-85`) — **false**. Every cached zip is unverified while the code advertises verification.
4. **sumdb is not proxied** (S6). `/sumdb/<name>/supported|lookup|tile` has no route; in proxy mode it falls through `fetchThroughCache` as a "non-artifact path" and is cached forever under the raw path key as a bogus artifact (`CachedProxySlice.java:295-304,833-848`). Clients must disable verification, and **the docs recommend `GONOSUMCHECK`/`GONOSUMDB` — no-ops removed after Go 1.18** (`go.md:23,30,40,52,130`, `jfrog-migration.md:441`, `troubleshooting.md:171`). Air-gapped clients following those docs get checksum failures.
5. **Scale/hygiene:** `GoListHandler` evaluates cooldown for **every** parsed version unbounded (`:326-344`), while `GoLatestHandler` caps at 50 (`:87,401-403`); the escaped `!`-form of a module path is written verbatim into the DB/index/audit `package.name` (`GoProxyPackageProcessor.java:271-274`, `GoUploadSlice.java:206-226`); and `goproxy/Goproxy.java` (270 lines of server-side zip construction) is dead — referenced only by tests.

**Goal:** make `go get`/`go list` survive an upstream outage for cached modules, resolve the correct hosted latest, let clients keep checksum verification on and offline-safe, stop advertising integrity that does not exist, bound the cooldown fan-out, record decoded package names, and delete the dead code — with docs that no longer mislead.

---

## 2. Current state (evidence, file:line)

| Concern | Evidence |
|---|---|
| `@latest`/`@v/list` intercepted before cache-first, served live | `CachedProxySlice.java:262-290` |
| List handler fetches upstream every call, no cache | `GoListHandler.java:185-199` |
| Latest handler fetches upstream every call, no cache | `GoLatestHandler.java:194-208` |
| Orphaned TTL machinery (unreachable in proxy) | `CacheTimeControl.java:34-104`; `CachedProxySlice.java:833-848,857-859` |
| Hosted `@latest` lexicographic sort over full key string | `LatestSlice.java:70-73` |
| Working semver comparator (used by proxy fallback only) | `VersionComparators.java:47-98`; `GoLatestHandler.java:398` |
| Inert `.ziphash` sidecar wiring + false integrity javadoc | `CachedProxySlice.java:74-85,154-160,531-534,997,1032-1036,1111-1134` |
| `.ziphash` integrity test (asserts the inert behaviour) | `CachedProxySliceIntegrityTest.java:69-130,245-273` |
| sumdb unrouted → accidental pass-through cached as bogus artifact | `CachedProxySlice.java:295-304`; local/group have no `/sumdb/` route |
| Unbounded per-version cooldown eval in `@v/list` | `GoListHandler.java:326-344` |
| Bounded (50) cooldown eval in `@latest` (the pattern to mirror) | `GoLatestHandler.java:87,401-403` |
| Escaped module name written raw to DB/index/audit (proxy) | `GoProxyPackageProcessor.java:263-275,206-224` |
| Escaped module name written raw to DB/index/audit (hosted) | `GoUploadSlice.java:151-160,206-226` |
| Dead `Goproxy.java` (only test refs) | `go-adapter/.../goproxy/Goproxy.java` (270 lines); refs only in `GoproxyTest`/`GoproxyITCase` |
| Docs recommend no-op `GONOSUMCHECK`/`GONOSUMDB` | `go.md:23,30,40,52,130`; `jfrog-migration.md:441`; `user-guide/troubleshooting.md:171` |
| Upload already invalidates negative + filtered-metadata caches (extend to the new base cache) | `GoUploadSlice.java:170-183` |

---

## 3. Target design

**Resolution-surface caching (the tentpole).** Cache the **raw, unfiltered** upstream `@v/list` / `@latest` document through the existing `Cache` + revived `CacheTimeControl`, then run the cooldown filter **over the cached base on every request** (cooldown state changes independently of the list, so the filter must run per-request; the base is cached, the filtering is not). Three properties fall out:
- **fresh cache** → served with no network call (offline-safe for cached modules);
- **expired cache** → single-flighted upstream refresh (thundering-herd protection on resolution, which MVS makes list/latest-heavy);
- **upstream failure with any cached copy** → serve the stale base (better than the current hard-fail), still cooldown-filtered.

Cache the base **unfiltered** so a cooldown lift/re-block is reflected without a re-fetch. Invalidate the base cache on hosted upload (extend the existing `invalidateAfterUpload` fan-out) so a newly-published version is not hidden for the full TTL.

**Honest integrity.** Delete the `.ziphash` sidecar fetch and the "Maven-equivalent integrity" claim; the zip stream-through path keeps its stream+cache+single-flight behaviour but stops pretending to verify. Real Go integrity returns via S6 + the deferred dirhash work.

**sumdb proxy (S6).** A dedicated handler proxies `/sumdb/<name>/…` to the same upstream remote(s) (GOPROXY-protocol convention `<proxyURL>/sumdb/<name>/<path>`), caching `lookup` and `tile` responses **immutably** (they are content-addressed / append-only) and probing `supported` live. Local/group return an honest 404 (no upstream to reach) and the docs point pure-local users at `GOPRIVATE`.

---

## 4. Implementation plan (ordered — each item is one branch/PR)

### WS4-go.1 — Delete the inert `.ziphash` integrity claim (S5) — **S**
- **Current:** `CachedProxySlice.java:997` fetches a phantom `.ziphash` sidecar; javadoc `:74-85,154-160,531-534` claims Maven-equivalent verification; `fetchSidecar` `:1111-1134` and the comment `:1032-1036` support it; `CachedProxySliceIntegrityTest.java` asserts the inert behaviour.
- **Target:** the zip stream-through path streams + caches + single-flights **without** a sidecar fetch and **without** any integrity claim, honestly documented as "no archive integrity verification yet (returns via WS4-go sumdb + dirhash follow-on)".
- **Plan (`CachedProxySlice`):** drop the `sidecars.put(SHA256, …)` line (`:997`) and pass an empty `EnumMap` to `cacheWriter.streamThroughAndCommit` (`:1026-1037`); delete `fetchSidecar` (`:1117-1134`) and the `.ziphash` comments; rewrite the class-level and `cacheWriter`-field javadoc (`:74-85,154-160,531-534`) to state there is no zip integrity check. Remove the wasted always-404 sidecar GET. Rewrite `CachedProxySliceIntegrityTest` to assert "zip streams + caches, second GET served from cache" with **no** sidecar assertions (or delete the two ziphash cases). Optionally drop `ziphash` from the `NegativeCacheKey.java:88` regex (harmless if left).
- **Acceptance:** unit — a `.zip` proxy fetch streams to the client and the second GET is a cache hit, with **zero** `.ziphash` upstream requests (recording fake counts sidecar GETs == 0). `mvn clean install -T8` green with the rewritten test.

### WS4-go.2 — TTL-cache `@v/list` & `@latest` in proxy (offline-safe) + single-flight — **M**  *(tentpole)*
- **Current:** `CachedProxySlice.java:265-290` routes both to handlers that call `upstream.response(...)` live every time (`GoListHandler.java:185`, `GoLatestHandler.java:194`); `CacheTimeControl` + `isMetadataPath`/`cacheControlFor` are orphaned.
- **Target:** both handlers read their base document through the shared `Cache` with `new CacheTimeControl(storage)` (12 h TTL, already the intended use per its javadoc `:23-33`), single-flight the miss, serve stale on upstream failure, and cooldown-filter the returned base per request.
- **Plan:**
  - `CacheTimeControl` — revive as-is; ensure the metadata write path stamps `updated-at=now` when the base is cached (else `validate()` treats the entry as forever-fresh, `:90-93`, and new upstream versions never appear). Optionally expose the TTL via `PANTERA_GO_METADATA_TTL` (a DB-less env default; not required for correctness).
  - `GoListHandler` / `GoLatestHandler` — add `Cache` + `Storage` + a metadata `SingleFlight<Key,Void>` to the ctor; in `handle()`, resolve the base via `cache.load(baseKey, <upstream fetch>, new CacheTimeControl(storage))` (leader streams the raw upstream body into cache; followers park then re-read the warm cache); on upstream failure fall back to the cached copy if present, else forward the upstream status. Keep the existing parse → `blockedVersions` → `filter` over the returned base bytes.
  - `CachedProxySlice` — pass `this.cache`, `this.storage`, and a shared metadata single-flight into the two handlers at construction (`:229-234`); **remove** the now-dead metadata branch in `cacheControlFor`/`isMetadataPath` (`:833-848,857-859`) so metadata caching has a single owner (the handlers).
  - `GoUploadSlice` — extend the post-upload invalidation (`:170-183`) to also evict the base `@v/list`/`@latest` cache for the module, so a hosted publish is visible immediately.
- **Acceptance (real `go`, itcase):**
  - `go list -m -versions <module>` succeeds **with upstream unreachable after a warm fetch** (warm cache with upstream up → block upstream → assert the version list still returns).
  - `go get <module>` (bare, hits `@latest`) resolves after a warm fetch with upstream down.
  - Single-flight: N concurrent `@v/list` misses → **1** upstream call (recording-fake invocation count).
  - Cooldown-over-cached-base: a version put under cooldown after the base is cached is filtered out with **0** additional upstream calls.
  - TTL refresh (unit, fake storage timestamp — not wall-clock): an entry past `updated-at + TTL` triggers a re-fetch; within TTL does not.

### WS4-go.3 — Hosted `@latest` correct semver — **S**
- **Current:** `LatestSlice.java:70-73` picks `.max(Comparator.naturalOrder())` over the full `.info` key string → lexicographic (`v0.9.0 > v0.10.0`) and comparing paths, not versions.
- **Target:** pick the highest `.info` by `VersionComparators.semver()` applied to the extracted version.
- **Plan (`LatestSlice`):** in `resp()`, map each `.info` key to its version (filename after the last `/`, strip `.info`; `semver()` tolerates the `v` prefix per `VersionComparators.java:31`), select `.max(VersionComparators.semver())`, then serve that key's `.info`. No signature change.
- **Acceptance:** unit — publish `v0.2.0`, `v0.9.0`, `v0.10.0` `.info` files to `InMemoryStorage`; `@latest` returns `v0.10.0`. itcase — hosted `go` local with those versions; a raw `@latest` GET (and `go list -m <module>@latest`) resolves `v0.10.0`.

### WS4-go.4 — sumdb proxy + immutable cache (S6) — **M**
- **Current:** `/sumdb/<name>/…` unrouted; proxy accidental pass-through cached as a bogus artifact (`CachedProxySlice.java:295-304`); local/group 404.
- **Target:** a dedicated `GoSumdbHandler` proxying `/sumdb/<name>/{supported,lookup/<mod>@<v>,tile/<…>}` to the same upstream remote(s), preserving the path; cache `lookup`/`tile` **immutably** (`CacheControl.Standard.ALWAYS`, keyed by path), probe `supported` live (or short-TTL). Local/group answer an honest 404. Lets clients keep verification on (no `GOSUMDB=off`) and stay offline-safe for cached lookups/tiles.
- **Plan:**
  - New `GoSumdbHandler` (mirror `GoListHandler` construction/shape) taking the upstream `Slice`, `Cache`, `Storage`; `matches(path)` = `path.startsWith("/sumdb/")`; `handle()` routes `lookup`/`tile` through `cache.load(key, <upstream>, CacheControl.Standard.ALWAYS)`, `supported` through a live upstream probe (200 → empty 200, else 404).
  - `CachedProxySlice.response` — intercept `sumdbHandler.matches(path)` **before** the generic `fetchThroughCache` fall-through (alongside the `@latest`/`@v/list` intercepts, `:265-290`).
  - Local/group Go slices — return 404 for `/sumdb/` (they have no upstream); documented as expected.
- **Acceptance (real `go`, itcase):** with a `go-proxy` and **`GOSUMDB` left on**, `go get <module>` succeeds (lookups/tiles proxied + cached). Block upstream and re-`go get` a cached module → still succeeds (immutable sumdb cache is offline-safe). Unit — a second `/sumdb/.../lookup/<mod>@<v>` request makes **0** upstream calls (immutably cached).
- **Out of scope here:** genuine H1-dirhash zip verification against the sumdb lookup — the DEFERRED L follow-on (§7).

### WS4-go.5 — Cap `@v/list` cooldown evaluation — **S**
- **Current:** `GoListHandler.blockedVersions:326-344` evaluates cooldown for **every** parsed version, unbounded; `GoLatestHandler` caps at `MAX_VERSIONS_TO_EVALUATE=50` (`:87,401-403`).
- **Target:** bound the fan-out the same way, without dropping older versions from the served list. Cooldown targets recent releases, so evaluate only the newest N by semver and pass the remainder through as allowed.
- **Plan (`GoListHandler`):** sort candidates semver-desc (reuse `VersionComparators.semver().reversed()`), evaluate cooldown for the newest `MAX_VERSIONS_TO_EVALUATE` (share the constant with `GoLatestHandler` / `MetadataFilterService.DEFAULT_MAX_VERSIONS`), treat the tail as not-blocked, and still return every non-blocked version (newest + tail).
- **Acceptance:** unit — a 500-version list with a recording cooldown fake asserts `evaluate` invoked ≤ cap, and every non-blocked version (including older ones beyond the cap) is served.

### WS4-go.6 — Decode `!`-escaping for DB/index/audit `package.name` — **S**
- **Current:** Go escapes uppercase as `!`+lowercase (`github.com/!burnt!sushi/toml` ⇢ `BurntSushi`); the escaped form is written verbatim to the `ArtifactEvent` in `GoProxyPackageProcessor.java:271-274,206-224` (proxy) and `GoUploadSlice.java:151-160,206-226` (hosted) → DB/index/search/audit `package.name` all show the escaped form.
- **Target:** decode the module path (`!x` → `X`) before building the `ArtifactEvent`; **leave the storage key escaped** (the storage/wire path must keep matching what the client requests — decoding it would break serving).
- **Plan:** add a small `unescape(String)` helper (new private method, or a shared `GoModulePath.unescape` in `go-adapter`); apply to `coords.module()` before the `ArtifactEvent` in `GoProxyPackageProcessor.processGoPackageAsync` and to `module` before `recordEvent` in `GoUploadSlice`. Do not touch `Key`/`zipKey` construction.
- **Acceptance:** unit — an event for `github.com/!burnt!sushi/toml` records `package.name=github.com/BurntSushi/toml`; the storage key is unchanged. itcase — `go get github.com/BurntSushi/toml` then assert the index/search row shows the decoded name.

### WS4-go.7 — Delete dead `Goproxy.java` — **S**
- **Current:** `goproxy/Goproxy.java` (270 lines) is referenced only by `GoproxyTest`/`GoproxyITCase`; hosted publish uses `GoUploadSlice`, proxy uses `CachedProxySlice`. Listed for deletion in 00's hygiene list.
- **Target:** remove the class and its two tests; fix the stale `goproxy/package-info.java` / `http/package-info.java` wording that describes "Goproxy files".
- **Plan:** delete `Goproxy.java`, `GoproxyTest.java`, `GoproxyITCase.java`; adjust package-info comments. Confirm no itcase gate depends on `GoproxyITCase`.
- **Acceptance:** `mvn clean install -T8` green after deletion (no compile/PMD refs remain).

### WS4-go.8 — Docs: drop no-op `GONOSUMCHECK`/`GONOSUMDB`, use `GOSUMDB=off` / `GOPRIVATE` — **S** *(land with WS4-go.4)*
- **Current:** `go.md:23,30,40,52,130`, `jfrog-migration.md:441`, `user-guide/troubleshooting.md:171` recommend `GONOSUMCHECK`/`GONOSUMDB` — removed/no-op since Go 1.18.
- **Target:** with the sumdb proxy live (WS4-go.4), the go-proxy path needs **no** checksum-disabling flag — clients keep verification on. Remove `GONOSUMCHECK`/`GONOSUMDB` everywhere; for **pure-local** (`go` hosted, not sumdb-covered) modules document `GOPRIVATE=<module-prefix>` (disables sumdb + proxy for those paths) or, as a blunt global escape hatch, `GOSUMDB=off`. Rewrite the two "checksum mismatch" troubleshooting rows to `GOPRIVATE=<prefix>` (preferred) / `GOSUMDB=off`. Keep `GOINSECURE` guidance.
- **Plan:** edit the three files' env blocks, the variable table (`go.md:27-31`), the CI/CD YAML (`go.md:48-54`), and both troubleshooting rows; add a short "checksum verification is proxied — keep it on" note to the `go-proxy` section (`go.md:72-95`).
- **Acceptance:** `grep -rn 'GONOSUMCHECK\|GONOSUMDB' docs/` returns nothing; the go-proxy itcase runs with `GOSUMDB` unset and succeeds (proving the docs' "keep it on" claim, tied to WS4-go.4).

---

## 5. Acceptance criteria (whole workstream)

1. `go list -m -versions <module>` and bare `go get <module>` succeed against a `go-proxy` **with upstream unreachable** after a prior warm fetch (WS4-go.2).
2. Concurrent identical `@v/list`/`@latest` misses collapse to one upstream call; a cooldown change after warm-up is reflected with zero extra upstream calls (WS4-go.2).
3. Hosted `@latest` returns `v0.10.0` when `v0.2.0/v0.9.0/v0.10.0` are published (WS4-go.3).
4. With the sumdb proxy live and `GOSUMDB` **on**, `go get` succeeds and re-succeeds for a cached module with upstream down (WS4-go.4).
5. No code path fetches `.ziphash` and no javadoc claims zip integrity verification (WS4-go.1).
6. `@v/list` cooldown evaluation is bounded; all non-blocked versions still served (WS4-go.5).
7. DB/index/audit `package.name` shows the decoded (`BurntSushi`) form; storage keys unchanged (WS4-go.6).
8. `Goproxy.java` gone; reactor green (WS4-go.7). Docs free of `GONOSUMCHECK`/`GONOSUMDB` (WS4-go.8).
9. `mvn clean install -T8` fully green (unit + PMD + license); itcase suite green.

---

## 6. Test requirements (unit / itcase / load)

- **Unit** (`InMemoryStorage`, recording fakes, no Docker/DB): semver-sort selection (WS4-go.3), single-flight coalescing + TTL-refresh via a fake `updated-at` timestamp — **never wall-clock**, use latches/invocation counts per CLAUDE.md doctrine (WS4-go.2), bounded cooldown eval count (WS4-go.5), `!`-decode + key-unchanged (WS4-go.6), sidecar-GET==0 (WS4-go.1), immutable sumdb cache hit (WS4-go.4).
- **itcase** (`test_images/` Go client, `-Pitcase`): the offline `go list`/`go get` scenarios (WS4-go.2), hosted `@latest` resolution (WS4-go.3), `go get` with `GOSUMDB` on + offline replay (WS4-go.4), capitalised-module name decode end-to-end (WS4-go.6). Reuse the existing Go container harness (the `GoproxyITCase` being deleted is not this coverage).
- **No load test** gates this workstream (it is correctness, not the WS1 scale claim), though the single-flight cap materially reduces upstream fan-out under MVS bursts.

---

## 7. Out of scope

- **Genuine H1-dirhash zip integrity verification** — the DEFERRED **L** follow-on that verifies the downloaded archive's `h1:` dirhash against the sumdb `lookup` from WS4-go.4. It depends on the sumdb proxy landing first and is a real feature; sequence it as its own spec after WS4-go.4. WS4-go.1 deliberately leaves the zip path **unverified but honestly documented** until then.
- **go-group union-merge** of `@v/list` across members (generic `GroupResolver`, first-2xx-wins) — deferred product decision, per the gap analysis.
- **Local `HEAD`** route (404 today) — minor, not in this mandate.
- Maven PGP (S4), and every other WS4 per-format spec.

---

## 8. Risks & rollback

- **Hiding newly-published upstream versions.** Caching `@v/list`/`@latest` for 12 h could hide a fresh upstream release. Mitigated by: caching the **unfiltered** base (cooldown re-applied per request), the TTL bound, and extending `GoUploadSlice`'s `invalidateAfterUpload` fan-out to the new base cache. Missing the `updated-at` stamp would make entries forever-fresh — call it out in review; the TTL-refresh unit test guards it.
- **Serving stale on upstream failure** is intentional for resolution (better than a hard-fail) and stays cooldown-filtered so embargoed versions remain hidden; it is bounded by "a cached copy exists", never fabricated.
- **sumdb misconfiguration** — a remote that cannot reach the real checksum DB yields client checksum failures; the docs' `GOPRIVATE`/`GOSUMDB=off` fallback (WS4-go.8) is the escape hatch, and local/group honestly 404 rather than caching bogus bytes.
- **`!`-decode must never touch the storage key** — decoding the served path would break `go get`. The unit test asserts the key is unchanged.
- **Deleting `Goproxy.java`** removes `GoproxyITCase`; confirm no `-Pitcase` gate references it before merge.
- **Rollback** is `git revert` per sub-item (no feature flags); the sub-items are independent PRs except WS4-go.8 which lands with WS4-go.4.

---

## 9. Docs & observability to update

**Docs (same-PR rule):**
- `docs/user-guide/repositories/go.md` — WS4-go.8 env/table/CI edits; add the "sumdb is proxied — keep verification on" note; document `/sumdb/*` support for `go-proxy` and its 404 for local/group.
- `docs/user-guide/jfrog-migration.md`, `docs/user-guide/troubleshooting.md` — WS4-go.8 edits.
- `docs/configuration-reference.md` / `docs/admin-guide/environment-variables.md` — any new `PANTERA_GO_METADATA_TTL`.
- `CHANGELOG.md` — `### 🔧 Bug fixes` (offline `go get`/`go list`; hosted `@latest` semver; decoded package names; bounded cooldown eval), `### 🌟 New features` (sumdb proxy), `### 🔒 Security`/`### 🔧` note that the removed `.ziphash` claim was inert (do **not** disclose it as an exploitable weakness — state it as "removed a non-functional integrity claim"). One attributed bullet each; house sections only.

**Observability (a metric without a panel is invisible):**
- New counters (bounded `repository.name` tag only): `pantera.go.metadata.cache` (hit / miss / stale_served), `pantera.go.metadata.singleflight` (coalesced), `pantera.go.sumdb.cache` (hit / miss, lookup / tile). Guard every call with `MicrometerMetrics.isInitialized()`; verify exact exposed names against a live `:8087/metrics/vertx` scrape before writing queries; add the panels in `grafana/provisioning/dashboards/` (bucket-less counters → `rate`/`increase`, not `histogram_quantile`).
- State-transition logs via `EcsLogger` (`log.source=application`): `serve_stale` on upstream-failure fallback (event.action `serve_stale`, event.outcome `success`, `event.reason=upstream_unavailable`), sumdb `supported`-probe failure, base-cache refresh. Counters alone are invisible in an incident.
