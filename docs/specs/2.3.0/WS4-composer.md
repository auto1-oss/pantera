# WS4-composer — Composer (PHP) API completeness & hosted-write correctness

- **Status:** 📝 DRAFT
- **Depends on:** `00-security-integrity-decisions.md` (S7 = **WIRE**, locked 2026-07-24). Sub-item **.4** overlaps `WS3-streaming-and-memory.md` (shared `ProxyCacheWriter` stream-through path) — coordinate, do not double-implement.
- **Blocks:** the "standalone `php-proxy` is a usable Packagist mirror" claim; the release itcase-coverage gate (Composer is one of the previously-thin clients).
- **Decision-gated:** only **.3** (dist integrity) — and that decision is already made (S7 = WIRE). Everything else is straight correctness/completeness.
- **Size:** M overall. Sub-items are independent enough to ship one agent-branch each; **.1 + .2 are the mandatory pair** (they make a standalone `php-proxy` work at all and stop the cache/cooldown/auth bypass).

## 1. Problem & goal

Composer's metadata half is the stronger one (v2 lazy `/p2/<vendor>/<pkg>.json`, cache-first stale-while-revalidate, per-version cooldown filtering, auth). The **root** and the **dist-download** halves are not bulletproof:

- A **standalone `php-proxy`** pointed at Packagist **cannot bootstrap `composer install`** — the repository root `GET /packages.json` returns **404**. It only works today when a `local` member fronts the root inside a `php-group`. The synthesized-correct root body already sits in the tree but is **unreachable dead code**.
- Where the root *is* served (lazy-provider passthrough, and the group winner), Pantera **leaks upstream URLs verbatim** (`metadata-url`, `search`, `list`, `notify-batch`, `security-advisories`, `available-packages-url`, `providers-url`). Composer then follows those straight to Packagist — **bypassing Pantera's cache, cooldown, and auth**. Only `dist.url` inside `packages` is ever rewritten.
- Advertised **dist integrity is inert**: the proxy fetches a phantom `.sha256` sidecar that does not exist in the Composer protocol; the real claim — `dist.shasum` inside the packument — is never verified; hosted publish writes no shasum at all.
- Dist archives download through a path that **buffers the whole archive in heap** with **no integrity and no single-flight**, while the good stream-through+single-flight path sits unreachable in the wiring.
- Several standard surfaces are missing or lie: `available-packages.json` is advertised but 404s; `composer search` / `show -a` silently returns nothing (local) or leaks to Packagist (proxy/group); `composer audit` / security-advisories is entirely absent; conditional `If-Modified-Since`/304 is never issued despite the upstream `Last-Modified` being captured; HEAD is unsupported.

**Goal:** a Composer proxy that a client can point at directly and use as a full Packagist mirror — root bootstraps, every URL the proxy emits points back at Pantera (cache/cooldown/auth enforced), dist bytes are streamed once, integrity-verified against `dist.shasum`, and single-flighted — plus the missing standard surfaces (`available-packages`, `search`/`list.json`, conditional GET, HEAD) and a real `composer audit`.

## 2. Current state (evidence)

**Wiring.** `ComposerProxy` (pantera-main) builds one `ComposerProxySlice` per remote under a `RaceSlice`, passing `baseUrl = cfg.url().toString()` (`ComposerProxy.java:63,72-88`). `ComposerProxySlice` dispatches: `rootHandler.matches(path)` **first** (`ComposerProxySlice.java:299`), then `packageHandler` (`:310`), then a fallback `SliceRoute` (`:211-250`).

**Root 404 (dead synthesized body).** `ComposerRootPackagesRequestDetector.isMetadataRequest` matches `/packages.json` and `/repo.json` exactly (`ComposerRootPackagesRequestDetector.java:57`), so `rootHandler` always intercepts the root — even with `NoopCooldownService` (handlers are built unconditionally, `ComposerProxySlice.java:251-278`). `rootHandler`'s upstream is the shared `cachedProxy` (`:272-274`). `CachedProxySlice` has no root branch: it strips the path to a package name (`name = path.replaceAll("^/p2?/","")…replaceAll(".json$","")` → `/packages`, `CachedProxySlice.java:236-239`), runs `checkCacheFirst` → `fetchThroughCache` → `MergePackage.WithRemote("/packages", …).merge(remote)` (`:243,289,371`), which cannot merge the root shape → empty → **404** (`:404-405`). The correct root body — `SliceSimple` emitting `{"packages":{},"metadata-url":"/<rname>/p2/%package%.json"}` — lives in the fallback `SliceRoute` (`ComposerProxySlice.java:211-227`) but is **unreachable** because `rootHandler.matches` wins first.

**Verbatim URL leak (proxy).** When the upstream root is a lazy-provider scheme (no inline `packages`), `ComposerRootPackagesHandler` serves the upstream bytes **verbatim** to "preserve top-level field ordering" (`ComposerRootPackagesHandler.java:255-278`). `MetadataUrlRewriter.rewrite` only rewrites `dist.url` inside `packages`; every top-level field is copied unchanged (`MetadataUrlRewriter.java:56-64`). No top-level `metadata-url`/`search`/`list`/`security-advisories`/`available-packages-url` rewrite exists on the proxy path.

**Verbatim URL leak (group).** `ComposerGroupSlice.rewritePackagesJson` rewrites only `metadata-url` (`:409`) and `providers-url` (`:369`); it copies **every other top-level field verbatim** (`:352-359`) — so `search`, `list`, `notify-batch`, `security-advisories`, `available-packages-url` leak upstream through the group too.

**Dist integrity inert / dead.** `CachedProxySlice.verifyAndServePrimary` + `streamPrimary` implement a correct stream-through + single-flight + `ProxyCacheWriter` integrity path (`CachedProxySlice.java:692-840`), but it (a) verifies against a **phantom `.sha256` sidecar** (`:758`, `fetchSidecar`), not the packument's `dist.shasum`, and (b) is **unreachable**: it fires only for `.zip`/`.tar`/`.phar` under the `PACKAGE` route (`ComposerProxySlice.java:228-234`), which matches `/p2?/<vendor>/<pkg>.json` only (`PackageMetadataSlice.java:40-42`) — never a dist path. Rewritten dist URLs are `<baseUrl>/dist/<pkg>/<version>.zip` (`MetadataUrlRewriter.java:211-217`), which hit the **FALLBACK** route → `ProxyDownloadSlice` (`ComposerProxySlice.java:235-249`). `ProxyDownloadSlice.fetchAndCache` buffers the whole archive (`response.body().asBytesFuture()`, `ProxyDownloadSlice.java:371`) then `storage.save` (`:382-384`) — **no integrity check, no single-flight**.

**Hosted publish writes no shasum.** `AstoRepository.addDist` builds `dist` with `url` + `type` only (`AstoRepository.java:230-237`); nothing computes or stores a SHA-256 of the archive.

**Advertised-but-unrouted `available-packages`.** `SatisLayout.generateRootPackagesJson` advertises `available-packages-url = <base>/p2/available-packages.json` (`SatisLayout.java:170-175`); no slice serves that path in any mode → 404. (Local root is generated here via `AstoRepository.packages` → `satis.generateRootPackagesJson`, `AstoRepository.java:324-325`.)

**Search absent.** No `search`/`packages/list.json` route in local, proxy, or group. `DbArtifactIndex.search(query, maxResults, offset, repoType, repoName, sortBy, sortAsc[, allowedRepos]) → SearchResult` already exists and is populated for Composer uploads (`DbArtifactIndex.java:415-537`; Composer publish records via `AddArchiveSlice` `syncIndex.recordSync`, `AddArchiveSlice.java:336`).

**Conditional GET dead.** The upstream `Last-Modified` is captured into `lastModifiedStore` (`CachedProxySlice.java:568-573`) but **never read** — no `If-Modified-Since` request, no 304 response. Freshness relies solely on the `CacheTimeControl` TTL.

**HEAD unsupported.** Local `PhpComposer` routes are `MethodRule.GET`-only (`PhpComposer.java:99,113,127`); the proxy dispatch and `ProxyDownloadSlice` have no HEAD branch.

**Security-advisories absent.** Zero references to security-advisories in the Java tree (grep clean). No field emitted, no route, no data model, in any mode.

## 3. Target design

Three shared building blocks, then per-item wiring:

1. **`MetadataUrlRewriter.rewriteRoot(json, repoBaseUrl)`** — a new method that rewrites **every top-level root URL** to a Pantera-local path: `metadata-url` → `<base>/p2/%package%.json`, `providers-url` → `<base>/p2/%package%.json`, `available-packages-url` → `<base>/p2/available-packages.json`, `search` → `<base>/packages/list.json?q=%query%&type=%type%` (Packagist search query shape), `list` → `<base>/packages/list.json`, `security-advisories.api-url` → `<base>/api/security-advisories/`, and **drop** `notify`/`notify-batch` (or point at a Pantera-local no-op) so no publish callback escapes. Fields the proxy does not implement are rewritten to their nearest Pantera-local equivalent or dropped — **never** passed through to upstream. One implementation reused by both the proxy root handler (.2) and the group (.2), killing both leaks.
2. **Packument `dist.shasum` accessor** — a small helper (in `CachedProxySlice`/`ProxyDownloadSlice`, or a new `ComposerDistIntegrity`) that reads `packages[<name>][<version>].dist.shasum` from the already-cached `<name>.json` (the same metadata `ProxyDownloadSlice.findOriginalUrl` already parses, `ProxyDownloadSlice.java:488-617`). Feeds .3 and .4.
3. **Root synthesis contract** — `/packages.json` is answered by fetching the **raw** upstream root (not the package-merge path), rewriting top-level URLs via (1), and — for inline-`packages` (Satis) roots — retaining the existing cooldown version filter. This is what makes a standalone proxy bootstrap.

### 4. Implementation plan (ordered by leverage; each is one agent branch)

Build order: **.1 → .2** first (they unblock a usable standalone proxy and stop the bypass), then **.3 → .4** (integrity + streaming), then the missing surfaces **.5 → .8**, then the greenfield **.9**.

---

#### WS4-composer.1 — Fix proxy root `GET /packages.json` bootstrap  ·  **CRITICAL**  ·  size **S**

- **Current:** `rootHandler` routes the root through `cachedProxy`, which treats it as a package named `/packages` and 404s; the correct synthesized body is unreachable (`ComposerProxySlice.java:211-227,272-274,299`; `CachedProxySlice.java:236-239,404-405`).
- **Target:** `GET /packages.json` (and `/repo.json`) on a standalone `php-proxy` returns a valid Pantera-local root so `composer install` bootstraps.
- **Files/classes:**
  - `ComposerRootPackagesHandler` — change its `upstream` from the shared `cachedProxy` to the **raw remote** slice (`remote(clients, remote, auth)` in `ComposerProxySlice`), so `handle` fetches the genuine upstream `/packages.json` instead of the package-merge path. Keep the existing inline-`packages` cooldown filter (`:253-329`) and the lazy-provider branch (`:255-278`) — both now operate on the real root.
  - `ComposerProxySlice` — pass the raw-remote slice into the `rootHandler` ctor (currently `:272-274`); the now-redundant `ByPath(ALL_PACKAGES)` `SliceSimple` fallback route (`:211-227`) is removed (dead once the handler serves the root) **or** kept only as the cooldown-disabled degenerate. Recommend: delete it and let the handler own the root in all cooldown modes (handlers are already built unconditionally, `:251-259`).
  - Root URL rewriting lands in **.2** (same handler) — sequence .2 immediately after so the revived root does not itself leak.
- **Acceptance:** itcase — a standalone `php-proxy` (no local member) pointed at Packagist: `composer install` of a small dependency tree succeeds from an empty cache; `GET /test_prefix/api/php_proxy/packages.json` (backend `:8088`) returns 200 with a JSON body whose `metadata-url` is Pantera-local. Unit: `ComposerRootPackagesHandler.handle` against an upstream fake returning a lazy-provider root returns 200 (not 404).

#### WS4-composer.2 — Rewrite ALL top-level root URLs to Pantera-local (proxy + group)  ·  **CRITICAL**  ·  size **S**

- **Current:** proxy serves lazy roots verbatim (`ComposerRootPackagesHandler.java:255-278`), `MetadataUrlRewriter` rewrites only `dist.url` (`MetadataUrlRewriter.java:56-64`), and the group copies all non-`metadata-url`/`providers-url` top-level fields verbatim (`ComposerGroupSlice.java:352-359`) → `metadata-url`/`search`/`list`/`notify-batch`/`security-advisories`/`available-packages-url` all leak to Packagist, bypassing cache/cooldown/auth.
- **Target:** every URL Pantera emits in a root points back at Pantera; no client can be steered upstream from a root document.
- **Files/classes:**
  - `MetadataUrlRewriter` — add `rewriteRoot(String json, String repoBaseUrl)` (block 1 above), rewriting/dropping all top-level URL fields.
  - `ComposerRootPackagesHandler` — call `rewriteRoot` on **both** the lazy-provider verbatim branch (`:255-278`) and the filtered inline branch (`:289-312`) before serving. Use the `baseUrl` already threaded to `CachedProxySlice`; thread it into the handler ctor (`ComposerProxySlice.java:272-274`).
  - `ComposerGroupSlice.rewritePackagesJson` — replace the verbatim copy of unrewritten fields (`:352-359`) with a call to `MetadataUrlRewriter.rewriteRoot(json, basePath)`, keeping the existing `providers`/uid handling (`:361-420`). One rewriter now plugs both proxy and group.
- **Acceptance:** itcase — with the proxy warm, capture the served `/packages.json`; assert no field value contains the upstream host (packagist.org / api.github.com) and `metadata-url`/`search`/`list`/`available-packages-url` all start with the Pantera repo base. Same assertion against `php-group`'s root. Behavioural: `composer require` of a cooldown-blocked version through the proxy is blocked (proves the client did not reach Packagist directly).

#### WS4-composer.3 — Dist integrity via packument `dist.shasum` (S7 = WIRE)  ·  size **S**

- **Current:** proxy verifies a phantom `.sha256` sidecar on a dead path (`CachedProxySlice.java:758`); the live dist path (`ProxyDownloadSlice`) verifies nothing (`:371-384`); hosted publish writes no shasum (`AstoRepository.addDist`, `:230-237`).
- **Target:** on proxy fetch/store, the downloaded archive's SHA-256 is compared to the packument's `dist.shasum`; a mismatch is rejected (nothing cached) with a `checksum_mismatch`-class audit failure. On hosted publish, `dist.shasum` is written so downstream clients verify.
- **Files/classes:**
  - Packument `dist.shasum` accessor (block 2) — extend `ProxyDownloadSlice.findOriginalUrl` (or a sibling) to also return the declared `shasum` from the cached `<name>.json` (`ProxyDownloadSlice.java:508-601`).
  - `ProxyDownloadSlice.fetchAndCache` — compute SHA-256 of the fetched bytes; if a declared `shasum` is present and differs, do **not** `storage.save`, emit `AuditLogger.access(..., OUTCOME_FAILURE, checksum_mismatch)`, return 502 with `X-Pantera-Fault: upstream-integrity:sha256`. (When .4 lands, this moves into the `ProxyCacheWriter` tee via its `dist.shasum`-backed sidecar supplier — see .4.)
  - `CachedProxySlice.streamPrimary` — replace the `.sha256`-sidecar supplier (`:756-758`) with one that yields the packument `dist.shasum` (no phantom HTTP fetch).
  - `AstoRepository.addDist` — compute SHA-256 of the stored archive bytes and add `"shasum"` to the `dist` object (`:230-237`); the bytes are already in hand at `addArchive` (`:119-150`).
- **Acceptance:** itcase — a proxy fetch of an artifact whose upstream bytes are corrupted (test double serving wrong bytes for a known `dist.shasum`) returns 502 and leaves the cache empty (next clean fetch succeeds). Hosted: publish an archive, then `composer install` it from a fresh client with `--verbose`; the served packument carries a `dist.shasum` matching the archive. Unit: verify accepts on match, rejects on mismatch.

#### WS4-composer.4 — Route dist download through streaming + single-flight (overlaps WS3)  ·  size **L**

- **Current:** dist archives hit `ProxyDownloadSlice`, which whole-buffers the archive in heap (`:371`) with no single-flight — GC/OOM risk at 1000 req/s of cold dists; the stream-through+single-flight path (`CachedProxySlice.verifyAndServePrimary`/`streamPrimary`, `:692-840`) is unreachable in wiring.
- **Target:** dist bytes are teed once (upstream → client + cache) via `ProxyCacheWriter.streamThroughAndCommit`, integrity-verified against `dist.shasum` (.3), and concurrent cold requests for the same archive collapse to one upstream fetch.
- **Cross-reference:** this is the Composer slice of **`WS3-streaming-and-memory.md`** (shared `ProxyCacheWriter` stream-through). Implement the streaming primitive there; here, do only the **routing**: make `/dist/...` reach the stream-through path instead of `ProxyDownloadSlice`'s buffer.
- **Files/classes:**
  - `ComposerProxySlice` — route the rewritten `/dist/<vendor>/<pkg>/<version>.zip` FALLBACK (`:235-249`) into a stream-through handler (either reuse `CachedProxySlice.verifyAndServePrimary` by teaching it the `/dist/` path shape + `dist.shasum` sidecar, or give `ProxyDownloadSlice` a `streamThroughAndCommit` body in place of the `asBytesFuture` buffer at `:371-397`). Preserve `ProxyDownloadSlice`'s cooldown gate, `original_url` resolution, and access-audit (`:216-296,310-399`).
  - Single-flight: reuse the existing `SingleFlight` keyed by dist `Key` (pattern already in `CachedProxySlice.primarySingleFlight`, `:139,206-210,704-717`).
- **Acceptance:** itcase/load — N concurrent cold `composer install`s of the same package issue exactly **one** upstream dist GET (invocation-count fake, per CLAUDE.md "counts not wall-clock"); heap does not scale with archive size (stream-through, verified by a large-archive fixture). Integrity failures keep the cache empty (folds .3).

#### WS4-composer.5 — `available-packages.json` route (or stop advertising)  ·  size **S**

- **Current:** advertised in every generated root (`SatisLayout.java:170-175`) but no route serves `/p2/available-packages.json` → 404; `composer show -a`/wildcard resolution fails.
- **Target:** either serve a valid `available-packages.json` (`{"available-packages":[<vendor/pkg>,…]}` enumerated from the local index / cached packuments) or stop advertising it so clients fall back to lazy `metadata-url` cleanly. Recommend: **serve it** for local/hosted (enumerable from `DbArtifactIndex`), and for proxy either omit the field (rewriteRoot in .2 drops it) or proxy-passthrough+cache.
- **Files/classes:**
  - Local: new route in `PhpComposer` (`:92-166`) for `ByPath(^/p2/available-packages.json$)` GET → a slice enumerating package names from `DbArtifactIndex` for this repo; `SatisLayout` keeps advertising it.
  - Proxy: `MetadataUrlRewriter.rewriteRoot` (.2) decides — drop the field, or rewrite to a Pantera-local passthrough. Pick drop-if-unsupported to avoid a second 404.
- **Acceptance:** itcase — `composer show -a` against a local `php` repo lists published packages; `GET /p2/available-packages.json` returns 200 with the expected names. Regression: the previously-404 path no longer 404s in any advertised mode.

#### WS4-composer.6 — `composer search` / `show -a` via `GET /packages/list.json`  ·  size **M**

- **Current:** no `search`/`list.json` route; local returns nothing silently; proxy/group leak the `search` URL to Packagist (fixed for the leak by .2, but still non-functional locally).
- **Target:** `GET /packages/list.json` (optionally `?q=`, `?type=`) returns Packagist's `{"packageNames":[…]}` shape, sourced from `DbArtifactIndex`; advertise `list` (and `search`) in the root via `rewriteRoot`.
- **Files/classes:**
  - New `ComposerListSlice` (composer-adapter `http`) — calls `DbArtifactIndex.search(query, maxResults, offset, "php", <rname>, sortBy, asc, allowedRepos)` (`DbArtifactIndex.java:415-537`), maps `SearchResult` docs → `{"packageNames":[…]}`; run blocking work on `HandlerExecutor` (never the event loop, per CLAUDE.md thread model).
  - Route in `PhpComposer` (local/hosted) and advertise `list` via `MetadataUrlRewriter.rewriteRoot`. Group: aggregate members' `list.json` (union of package names) or delegate to the local member; proxy: serve from the index of what has been cached, or passthrough+cache the upstream `list.json`.
  - Auth: reuse `AdapterBasicPermission(name, READ)` like the metadata routes (`PhpComposer.java:105-108`).
- **Acceptance:** itcase — publish two packages to a local `php` repo, then `composer search <term>` returns them; permission-filtered (a token without READ on the repo gets none). Unit: `ComposerListSlice` maps a `SearchResult` to the `packageNames` shape.

#### WS4-composer.7 — Conditional `If-Modified-Since` / 304  ·  size **M**

- **Current:** upstream `Last-Modified` is captured into `lastModifiedStore` (`CachedProxySlice.java:568-573`) and never read; every `composer update` re-downloads full metadata bodies even from a warm cache.
- **Target:** on a stale-while-revalidate refresh (and on cache-miss re-fetch), send `If-Modified-Since` from the stored value; on upstream **304**, keep the cached bytes and skip the body transfer.
- **Files/classes:**
  - `CachedProxySlice.packageFromRemote` (`:548-605`) — add the `If-Modified-Since` request header from `lastModifiedStore.get(path)`; handle a 304 by returning the cached content (short-circuit the merge/rewrite/save) and refreshing the freshness marker.
  - `CachedProxySlice.backgroundRefresh`/`fetchThroughCache` (`:317-440`) — thread the conditional through the refresh path so SWR revalidation is a cheap 304 when unchanged.
- **Acceptance:** unit/itcase — with a recording upstream fake, a second metadata fetch after a 304 issues the conditional request and performs **zero** body reads (invocation/byte-count assertion); the served bytes equal the cached bytes. `composer update` with no upstream change transfers no metadata bodies.

#### WS4-composer.8 — HEAD support (local + proxy)  ·  size **S**

- **Current:** local routes are GET-only (`PhpComposer.java:99,113,127`); proxy dispatch and `ProxyDownloadSlice` have no HEAD branch → HEAD probes 405/inconsistent.
- **Target:** `HEAD` on metadata and dist paths returns the same status + `Content-Length`/`Content-Type` (and `Last-Modified` where known) as GET, with no body.
- **Files/classes:**
  - `PhpComposer` — add `MethodRule.HEAD` alongside GET on the metadata and download routes, or a HEAD→GET-without-body wrapper slice.
  - `ComposerProxySlice`/`ProxyDownloadSlice` — accept HEAD; for dist, answer from the cache existence check (`ProxyDownloadSlice.java:233-240`) without streaming bytes.
- **Acceptance:** itcase — `HEAD` of a cached packument and a cached dist returns 200 with a correct `Content-Length` and empty body; `HEAD` of an absent artifact returns the same status a `GET` would.

#### WS4-composer.9 — `composer audit` / security-advisories subsystem  ·  size **L** (greenfield)

- **Current:** entirely absent (grep clean) — no field emitted, no route, no data model, no aggregation, in any mode. `composer audit` is a silent security blind spot; proxy/group would (pre-.2) leak the Packagist advisory URL.
- **Target:** serve the Packagist advisories API shape at `POST /api/security-advisories/` (Composer sends `packages[]` / `updatedSince`), returning `{"advisories":{<pkg>:[…]}}`. Advisory data sources: **proxy** — passthrough + cache the upstream advisories response (offline-safe like the metadata cache); **local/hosted** — a stored advisory table; **group** — union across members. Advertise via `security-advisories.api-url` in `rewriteRoot` (.2).
- **Files/classes:** new `ComposerSecurityAdvisoriesSlice` (+ route in `PhpComposer`/`ComposerProxySlice`/`ComposerGroupSlice`); a Flyway-backed advisory store for local/hosted (mirror the DB-backed-setting recipe if a table is needed); proxy cache reuse of the existing `Cache`/`CacheTimeControl`. Emit an `artifact_resolution` audit for the advisory query.
- **Acceptance:** itcase — `composer audit` against a proxy repo returns the upstream advisories for a known-vulnerable package (served from Pantera, not Packagist directly); works from cache when upstream is unreachable. Local: seed an advisory, `composer audit` flags the matching installed version.
- **Note:** largest item — no model/route/aggregation exists today. Sequence last; may warrant its own follow-on spec if the local advisory store grows beyond a passthrough cache.

## 5. Acceptance criteria (whole workstream)

1. **Standalone proxy bootstraps** — a `php-proxy` with **no** local member serves `GET /packages.json` 200 and `composer install` completes from an empty cache (.1).
2. **No upstream leak** — no field value in any served root (proxy or group) contains the upstream host; every advertised URL is Pantera-local; a cooldown-blocked version cannot be fetched by a client following the served root (.2).
3. **Dist integrity enforced** — a corrupted upstream archive is rejected (502, cache stays empty) against the packument `dist.shasum`; hosted publish emits `dist.shasum` (.3).
4. **Dist streaming + single-flight** — N concurrent cold pulls of one archive → one upstream GET; heap does not scale with archive size (.4, with WS3).
5. **No advertised-but-404 surface** — `available-packages.json` and `list.json` resolve where advertised; `composer show -a` / `composer search` return real results (.5, .6).
6. **Conditional GET** — a 304 revalidation transfers zero metadata body bytes; served bytes equal cached bytes (.7).
7. **HEAD parity** — HEAD returns GET's status + `Content-Length`, no body (.8).
8. **`composer audit`** returns Pantera-served advisories, offline-safe on proxy (.9).

All assertions follow CLAUDE.md testing doctrine: invocation counts / latches / state, **never** wall-clock latency.

## 6. Test requirements

- **Unit** (surefire, `InMemoryStorage`, no Docker): `MetadataUrlRewriter.rewriteRoot` (every top-level field rewritten/dropped, idempotent); `ComposerRootPackagesHandler` root serving against lazy + inline upstream fakes; `dist.shasum` verify accept/reject; `ComposerListSlice` `SearchResult`→`packageNames`; conditional 304 short-circuit via a recording upstream fake.
- **Itcase** (`-Pitcase`, failsafe, composer client image in `test_images/`): standalone `php-proxy` `composer install` bootstrap; leak-free root assertion; corrupted-archive rejection; concurrent single-flight; `composer show -a` / `composer search`; `composer update` 304 no-body; HEAD parity; `composer audit`. Add composer to the previously-thin itcase clients called out in the release gate.
- **Load** (WS3/WS1 gate): cold-dist streaming under concurrency proving no per-archive heap growth.

## 7. Out of scope

- `source` (VCS) download rewriting — `--prefer-source` bypasses the proxy; documented as unsupported, not fixed here.
- Composer v1 `provider-includes` and the full v1 provider-hash protocol (v2 lazy `metadata-url` is the supported path).
- Presigned direct-download for dist bytes — that is `WS1.7` (redirect mode), not this spec; here dist bytes stream through Pantera.
- Rewrite memoization / root cooldown fan-out caps (scale polish, WS3/WS6) beyond the single-flight in .4.

## 8. Risks & rollback

- **.1/.2 change the root contract** — the highest-blast-radius pair. Land them behind the standalone-proxy `composer install` itcase before merge; a regression here breaks every proxy client. `rewriteRoot` must be conservative: drop unknown top-level URL fields rather than pass them through (fail-closed against leaks).
- **.4 touches the shared `ProxyCacheWriter` stream path** — coordinate with WS3 so the tee is implemented once; do not fork a second streaming path. Integrity failures must keep the cache empty (fail-closed), matching the Maven/`CachedProxySlice.streamPrimary` decision.
- **.9 is greenfield security surface** — a wrong advisory shape makes `composer audit` silently under-report; validate against a real Packagist advisory response fixture. Prefer passthrough+cache over a hand-rolled model for the proxy path.
- Rollback is `git revert` per sub-item branch (no feature flags — CLAUDE.md).

## 9. Docs & observability

- **User guide:** `docs/user-guide/repositories/composer.md` **already exists** (151 lines) — the gap-analysis note that "Composer has no user-guide page" is stale; **fix that claim** and **expand** the existing page: standalone-proxy setup (now that root bootstraps), `composer search`/`show -a`, `composer audit`, dist-integrity behaviour, HEAD/conditional-GET notes. Do not create a duplicate page.
- **Reference:** `docs/rest-api-reference.md` — new/changed Composer routes (`/packages.json` root, `/p2/available-packages.json`, `/packages/list.json`, `/api/security-advisories/`, HEAD). `docs/configuration-reference.md` + `docs/admin-guide/environment-variables.md` — any new advisory-store or cache tunables from .9.
- **CHANGELOG.md:** `### 🔧 Bug fixes` (standalone-proxy root bootstrap, dist streaming, conditional GET, available-packages/list 404s), `### 🔒 Security` (upstream-URL-leak → cache/cooldown/auth bypass closed; dist `dist.shasum` integrity wired; `composer audit`). Concise attributed bullets, house sections only.
- **Observability (WS7):** per the metric-needs-a-panel rule, if .3/.4 add integrity-failure or single-flight-collapse counters (`pantera.composer.*`, bounded repo tag only), add the Grafana panel in the same PR and verify the exact exposed name against a live `:8087/metrics/vertx` scrape. Log the new state transitions (integrity rejection, root-leak-blocked, advisory cache miss) via `EcsLogger`, not counters alone.
