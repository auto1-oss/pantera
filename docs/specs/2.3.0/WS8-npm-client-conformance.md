# WS8-npm — corepack / yarn / npm client conformance

- **Status:** 📝 DRAFT
- **Depends on:** `WS4-npm.md` (WS4-npm.3 per-version layout + `.dist-tags.json` sidecar, WS4-npm.8 proxy search/dist-tags routing, WS4-npm.9 `SingleVersionSlice` for hosted repos). All landed on `feat/2.3.0`.
- **Blocks:** any "corepack works against Pantera" / "drop-in npm registry" claim.
- **Decision-gated:** no. Both root causes are unambiguous defects.
- **Size:** M. Two mechanisms (A, B) plus a client-conformance sweep whose findings feed back as additional sub-items.
- **Backport:** yes — this spec's changes ship in **2.2.5** alongside the npm commits already on `feat/2.3.0`. See §10.

Scope boundary: this spec covers **what real package-manager clients require of the npm HTTP surface** across all three repository modes. It does not re-open the npm proxy caching internals (WS5/WS6) or hosted write correctness (WS4-npm).

---

## 1. Problem & goal

A production report against 2.2.4:

> `Type Error: Cannot destructure property 'tarball' of 'versionMetadata.dist' as it is undefined.`
>
> Corepack fetches per-version metadata (`GET <registry>/pnpm/11.5.1`). Pantera answers `200` with a stub — `{name, modified}`, no `dist`, no `versions`. npm and pnpm never hit that endpoint (they pull the full packument at `/pnpm` and pick the version client-side), which is why `npm view` works while corepack doesn't. […] There's a second blocker behind it: the tarball URL Pantera returns is `…/api/npm/npm_proxy/pnpm/-/pnpm-11.5.1.tgz`, which doesn't start with the configured registry base — corepack rejects that too.

Two independent defects, both of which only bite clients that npm and pnpm's happy paths never exercise:

- **A. No single-version route outside hosted repos.** `GET /<pkg>/<version>` is a standard npm registry endpoint. `feat/2.3.0` implements it for **local** repositories only; proxy and group modes answer a structurally invalid stub with `200 OK`.
- **B. Tarball URLs are rooted at the wrong repository.** A group client receives tarball URLs pointing at the winning *member*, not at the group it addressed. npm tolerates a cross-origin tarball URL; corepack refuses any tarball not prefixed by the configured registry.

**Goal:** corepack, npm, pnpm, yarn classic, and yarn berry all resolve and install against Pantera in **all three repository modes** (local, proxy, group), and every URL Pantera emits is rooted at the base the client actually addressed. Endpoint coverage is established by driving the real clients, not by inspection.

---

## 2. Current state (evidence, file:line)

All citations are against `feat/2.3.0`.

### A. Single-version metadata

- **No proxy route.** `npm-adapter/.../proxy/http/NpmProxySlice.java:93-150` registers exactly six routes: search, dist-tags, packument (`ppath.pattern()`), asset, two audit patterns, then `RtRule.FALLBACK` → `404`. There is no single-version route.
- **The packument route swallows it.** `PackagePath.pattern()` (`proxy/http/PackagePath.java:35`) is `^/(((?!/-/).)+)$` — it excludes only paths containing `/-/`. `GET /pnpm/11.5.1` therefore matches, and `NpmPath.value()` returns the **whole** string `pnpm/11.5.1` as the package name.
- **The stub is then manufactured.** `DownloadPackageSlice.response` (`proxy/http/DownloadPackageSlice.java:174-179`) special-cases only the `/latest` suffix; anything else falls through to `serveAbbreviated`/`serveFull` with the bogus package name. Upstream returns a *version manifest* for `/pnpm/11.5.1`, which is then run through packument-shaped processing: `AbbreviatedMetadata.generate()` (`misc/AbbreviatedMetadata.java:96-118`) unconditionally adds `name` and `modified`, then adds `time`, `dist-tags`, and `versions` **only if present** — and none of the three exist in a version manifest. The emitted body is exactly `{"name":"pnpm","modified":"<now>"}`, matching the report byte for byte.
- **Hosted mode already works.** `NpmSlice.java:611-614` routes `^/(@[^/]+/)?[^/]+/[^/]+$` (GET + HEAD) to `SingleVersionSlice`, which resolves a literal version first, then the `.dist-tags.json` sidecar, and rewrites `dist.tarball` via `Tarballs.rewriteTarball` (`Tarballs.java:112`). This is the behaviour proxy/group must match.
- **`/latest` is the existing precedent.** `serveLatestManifest` (`DownloadPackageSlice.java:649+`) already does the right shape of work — fetch packument, apply the cooldown filter, emit one version's manifest — but is hardwired to the `latest` tag by a string-suffix test rather than generalised to any version-or-tag reference.

### B. Client-facing base URL

- **Member config wins.** `NpmProxyAdapter.java:68` sets `baseUrl = Optional.of(cfg.url())` — the member's own configured URL — and `DownloadPackageSlice.getTarballPrefix` (`:990-999`) returns it unconditionally when present. `clientFormat` (`:1117-1132`) does the same for the non-streaming path. A client addressing `npm_group` therefore receives `…/npm_proxy/…` tarballs.
- **The fallback is worse than the config.** With no configured URL, `assetPrefix` (`:1155-1161`) builds `String.format("http://%s/%s", host, prefix)` — scheme hardcoded to `http://`, `X-Forwarded-Proto`/`-Host`/`-Prefix` ignored entirely. Behind any TLS-terminating reverse proxy this emits URLs the client cannot use.
- **The shipped sample config is already wrong.** `pantera-main/docker-compose/pantera/repo/npm_proxy.yaml` declares `url: http://localhost:8081/npm_proxy`, but the documented dev route is `http://localhost:8081/test_prefix/api/npm_proxy/…` (`/CLAUDE.md`, "Local dev stack playbook"). The in-tree fixture cannot round-trip a tarball URL.
- **The needed plumbing already exists.** `TrimPathSlice` (`pantera-core/.../http/slice/TrimPathSlice.java:105`) stamps `X-FullPath` with the full pre-trim path at the exact moment it also holds the trimmed remainder — i.e. it can compute the repo base precisely. `GroupResolver.dropFullPathHeader` (`group/GroupResolver.java:1279-1285`) filters **only** `X-FullPath` and copies every other header through to the member (`:1140-1146`, `:1244-1247`). A second, dedicated header therefore survives the group→member hop unmodified.

### C. What is *not* broken

Verified present and out of scope for repair: tarball byte serving (`DownloadAssetSlice`), packument serving including the abbreviated fast path, proxied `/-/v1/search` and dist-tags (`NpmProxySlice.java:93-106`, WS4-npm.8), `npm ping`, `GET /npm`, audit endpoints, and hosted publish/dist-tag/deprecate/unpublish (WS4-npm.3).

---

## 3. Target design

### Mechanism A — `X-Pantera-Client-Base`

A single internal header carrying the **client-facing base URL of the repository the client actually addressed**, stamped once at the repository entry point and honoured by every slice that emits a URL into a response body.

**Derivation** (new `ClientBaseUrl` helper, `pantera-core`, `com.auto1.pantera.http.headers`):

```
origin = (X-Forwarded-Proto | request scheme) + "://" + (X-Forwarded-Host | Host)
prefix = X-Forwarded-Prefix (optional, prepended)
repoPath = fullPath minus trimmedSuffix          // both held by TrimPathSlice
base = origin + prefix + repoPath
```

**Stamping** — in `TrimPathSlice`, adjacent to the existing `X-FullPath` add (`:105`), under two rules:

1. **Stamp-if-absent.** A member repository is itself wrapped in a `TrimPathSlice`; because the group's value is already on the request, the member does not overwrite it. Group-wins is a consequence of the rule, not a special case. (`X-FullPath` cannot serve this purpose — `GroupResolver` deliberately strips it to defeat the recursion guard at `:84`.)
2. **Configured `url:` overrides the derived value** for the *addressed* repository. `RepositorySlices` passes the repo's configured URL into the wrapper; when set, it is stamped verbatim. A group has no `url:` and so derives; a member's `url:` is never consulted once a header is present.

**Consumption** — `DownloadPackageSlice.getTarballPrefix`/`clientFormat` and `SingleVersionSlice` take the header as first choice. The existing chain (`cfg.url()` → `Host` fallback) is retained beneath it for non-HTTP and unit-test paths, so no call site can become URL-less.

Composer, PyPI, and Docker perform structurally identical rewriting and are candidates to adopt `ClientBaseUrl` later. **Explicitly out of scope here** (§7).

### Mechanism B — version-or-tag resolution on proxy and group

Generalise the `/latest` shortcut into a full version-or-tag resolver. Because `DownloadPackageSlice` is already 1164 lines and PMD caps class cyclomatic complexity at 80, the resolver lands as a **new class** (`proxy/http/VersionManifestResolver`) that `DownloadPackageSlice` delegates to; `serveLatestManifest` collapses into a call with `ref = "latest"`.

1. **Parse** `/<pkg>/<ref>` scope-aware, mirroring `SingleVersionSlice.parse`: two segments unscoped → `(pkg, ref)`; three segments with a leading `@` → `(@scope/pkg, ref)`; two segments with a leading `@` → a scoped package name, *not* a version reference. npm package names cannot contain `/` unless scoped, so the split is exact. `ref` of `-` or empty is rejected.
2. **Fetch** the packument cache-first and apply the **existing** cooldown filter — a cooldown-blocked version must `404`, never leak. This is why the endpoint resolves through the packument rather than proxying `/<pkg>/<version>` upstream verbatim: a passthrough would bypass cooldown entirely.
3. **Resolve** `ref` as a literal version first, then as a dist-tag against the filtered packument (so `latest` is one case of the general path, and a version that shares a name with a tag resolves to itself).
4. **Emit** `versions[ref]` with `dist.tarball` rewritten through `Tarballs.rewriteTarball(…, clientBase)`, plus `ETag` / `If-None-Match` / `304`, matching `SingleVersionSlice`'s response shape exactly. `GET` and `HEAD`.

**Group mode needs no `GroupResolver` change.** Local members already route to `SingleVersionSlice`; proxy members gain the path above; first-2xx-wins with 404-fall-through applies unchanged; the client-base header rides through untouched.

**No new cache surface.** The manifest is derived from the already-cached packument. Cost is one packument parse per request — identical in kind to today's `/latest` path, and these requests are rare (corepack resolves a package-manager version once per project, then caches).

### Data flow (group, the failing case)

```
client   GET https://host/artifactory/api/npm/npm_group/pnpm/11.5.1
  TrimPathSlice(npm_group)  stamps X-Pantera-Client-Base:
                            https://host/artifactory/api/npm/npm_group
                            trims path -> /pnpm/11.5.1
  GroupResolver             drops X-FullPath, keeps client-base
                            rewrites path -> /npm_proxy/pnpm/11.5.1
  TrimPathSlice(npm_proxy)  header present -> does NOT overwrite
  DownloadPackageSlice      -> VersionManifestResolver("pnpm", "11.5.1")
                            packument (cached) -> cooldown filter -> versions["11.5.1"]
  response                  dist.tarball =
    https://host/artifactory/api/npm/npm_group/pnpm/-/pnpm-11.5.1.tgz
```

---

## 4. Implementation plan (ordered, file-level)

**WS8-npm.1 — `ClientBaseUrl` helper [S]**
`pantera-core/.../http/headers/ClientBaseUrl.java` (new) — header name constant, derivation from `(Headers, fullPath, trimmedPath)`, forwarded-header handling. Pure function, no I/O.

**WS8-npm.2 — stamp in `TrimPathSlice` [S]**
`pantera-core/.../http/slice/TrimPathSlice.java:105` — add the header alongside `X-FullPath`, stamp-if-absent. New optional ctor parameter carrying the addressed repo's configured `url:`; existing ctors delegate with `Optional.empty()` (**one constructor initialises fields**, per the PMD rule). Wire the configured URL through `RepositorySlices`' `trimPathSlice`/`browsableTrimPathSlice` helpers.

**WS8-npm.3 — consume in the npm adapter [S]**
`npm-adapter/.../proxy/http/DownloadPackageSlice.java:990-999,1117-1132,1155-1161` — header first, then `cfg.url()`, then a `Host` fallback that now honours `X-Forwarded-Proto` instead of hardcoding `http://`.
`npm-adapter/.../http/SingleVersionSlice.java` — same precedence for the hosted single-version path.

**WS8-npm.4 — `VersionManifestResolver` [M]**
`npm-adapter/.../proxy/http/VersionManifestResolver.java` (new) — parse, packument fetch, cooldown filter, version-or-tag resolve, manifest emit, ETag/304.
`npm-adapter/.../proxy/http/DownloadPackageSlice.java:140,174-179,649+` — delegate; `serveLatestManifest` becomes `resolve(pkg, "latest")`; `LATEST_SUFFIX` removed.
`npm-adapter/.../proxy/http/NpmProxySlice.java:107` — register the single-version route (GET + HEAD) **before** the packument route, mirroring the search/dist-tags ordering comment already there.

**WS8-npm.5 — fix the sample configs [S]**
`pantera-main/docker-compose/pantera/repo/npm_proxy.yaml` and siblings — correct or remove the stale `url:` so the dev stack round-trips a tarball URL.

**WS8-npm.6 — client conformance sweep [M]**
Drive real clients against the running local stack (§5), record the matrix, and open a sub-item for every gap found. **This item is open-ended by design** — the fix list is not final until the sweep runs.

---

## 5. Acceptance criteria (testable)

Per repository mode — **local, proxy, group** — unless noted:

1. `GET /<pkg>/<version>` returns `200` with a body containing `name`, `version`, and `dist.tarball`; `dist.tarball` is an absolute URL **string-prefixed by the base the client addressed**.
2. `GET /<pkg>/latest` and `GET /<pkg>/<custom-tag>` resolve through the same path.
3. `GET /<pkg>/<nonexistent-version>` returns `404` with an honest JSON body — never a `200` stub. **This is the regression guard for the reported bug.**
4. A cooldown-blocked version returns `404` from the single-version endpoint (proxy/group), i.e. cooldown is not bypassed.
5. `GET /@scope/pkg` still resolves as a **packument**, not as `(pkg=@scope, ref=pkg)`.
6. `If-None-Match` against a single-version response returns `304`; changing the client base changes the `ETag`.
7. Behind `X-Forwarded-Proto: https` + `X-Forwarded-Host` + `X-Forwarded-Prefix`, emitted URLs use the forwarded scheme, host, and prefix.
8. **Group:** tarball URLs are rooted at the group, never at the winning member.
9. **corepack end-to-end:** `corepack use pnpm@11.5.1` succeeds against a group and against a proxy, with `COREPACK_NPM_REGISTRY` pointed at Pantera.
10. `mvn clean install -T8` fully green (unit + PMD + license) — the standard gate.

---

## 6. Test requirements

**Unit** (`InMemoryStorage`, no Docker/network/DB, JUnit 5 + Hamcrest matcher objects):
- `ClientBaseUrlTest` — derivation from `Host`; `X-Forwarded-Proto`/`-Host`/`-Prefix`; stamp-if-absent (group-wins); configured-`url:` override; trailing-slash normalisation.
- `VersionManifestResolverTest` — scoped/unscoped parsing incl. the `/@scope/pkg` two-segment ambiguity; literal-version-beats-tag precedence; unknown ref → 404; cooldown-blocked version → 404; ETag round-trip → 304.
- `DownloadPackageSliceSingleVersionTest` — the reported case end to end at slice level: `GET /pnpm/11.5.1` yields a manifest with `dist.tarball`, and **never** the `{name, modified}` stub.

**Integration** (`*ITCase.java`, `-Pitcase`): corepack resolving `pnpm@<version>` through both a group and a proxy, asserting the tarball prefix matches the addressed base and the install completes. Requires a `test_images/` node image with corepack — `build.sh` is Linux-only (GNU sed), so flag CI feasibility as part of WS8-npm.6.

**Prohibited** (per `/CLAUDE.md` testing doctrine): absolute wall-clock latency assertions, `Files.createFile` (use `@TempDir`), static Hamcrest factories.

### Conformance matrix (WS8-npm.6 deliverable)

Filled in by running each client; `?` means not yet exercised.

| Client | Endpoints exercised | local | proxy | group |
|---|---|---|---|---|
| corepack | `/<pkg>/<version>`, tarball | ? | ? | ? |
| npm | packument (abbrev), tarball, `/-/v1/search`, audit | ? | ? | ? |
| pnpm | packument (abbrev + `time`), tarball | ? | ? | ? |
| yarn classic | packument, tarball | ? | ? | ? |
| yarn berry | packument, tarball, checksum verify | ? | ? | ? |

---

## 7. Out of scope

- Adopting `ClientBaseUrl` in the Composer, PyPI, and Docker adapters (structurally identical rewriting; separate change).
- npm proxy caching internals — upstream ETag revival, filtered-metadata staleness, packument heap buffering (WS3/WS5/WS6).
- Hosted write correctness (WS4-npm.3) and provenance/signing (WS4-npm.1) — already landed.
- Presigned direct-download for npm (`8e9a0a41d`) — a 2.3.0 feature, deliberately excluded from the 2.2.5 backport (§10).
- `npm access`, `npm owner`, `npm hook` — no client in the matrix requires them.

---

## 8. Risks & rollback

| Risk | Assessment | Mitigation |
|---|---|---|
| Generalising `/<pkg>/<ref>` changes behaviour for two-segment paths previously forwarded upstream verbatim | The change most likely to surprise. Correct per npm semantics — such a path *is* a version reference — but it converts some previously-proxied requests into packument lookups | Acceptance criterion 5 guards the scoped-package ambiguity; the conformance sweep exercises the rest |
| `TrimPathSlice` is shared by all 15 formats | Stamping is purely additive and consumption is npm-only, so blast radius is bounded — but it is a shared file on every request path | Unit coverage on the helper; no behaviour change when the header is unread |
| `url:`-overrides-derived may be the wrong precedence for some deployments | An operator whose reverse proxy neither sends `X-Forwarded-*` nor is reflected in `url:` still gets a wrong base | Documented in `configuration-reference.md`; the derived value is strictly better than today's hardcoded `http://` + `Host` |
| Packument re-parse per single-version request | Same cost as today's `/latest` path; these requests are rare | If it ever shows up in profiling, a small TTL cache keyed on `(pkg, filtered-etag)` slots in behind the resolver |

**Rollback:** `git revert`. No feature flags — settled changes ship as full replacements (project convention).

---

## 9. Docs & observability to update (same PR)

- `CHANGELOG.md` — one attributed bullet per user-visible change, house sections only.
- `docs/user-guide/` npm page — corepack setup (`COREPACK_NPM_REGISTRY`), and the note that group and proxy both serve single-version metadata.
- `docs/admin-guide/` + `docs/configuration-reference.md` — `url:` precedence and the `X-Forwarded-*` requirements for reverse-proxy deployments.
- No new metrics, so no new Grafana panels. `EcsLogger` state transitions: an unresolvable version reference logs at DEBUG with `event.action=version_resolution`, `url.path`, `package.name` — not a counter.

---

## 10. Release shape

**`feat/2.3.0`** — implement and verify here.

**`release/2.2.5`** — cut off `master`, cherry-picking the npm commits already on `feat/2.3.0`, then this spec's work:

| Commit | Contents |
|---|---|
| `5ef2c832f` | cooldown-filtered packument ETag keyed to filtered bytes |
| `971612178` | delete dead star subsystem (prerequisite) |
| `7ea3c283b` | dist-tags/deprecate/unpublish on per-version layout; search → DB index |
| `adc1fb990` (+ merge `9aa759cbc`) | API surface — provenance/signing, tokens/profile, proxied search & dist-tags, single-version, ping, honest audit |
| `ecc43500e` | reserved-key guard (registry signing key, user/token records) |
| `757435f89` | npm prerelease tarball cooldown keyed on full version |
| `7cb5a64b2` | invalidate cooldown filtered-metadata cache on proxy refresh |
| `542f94aec` | persist upstream ETag so conditional packument refresh fires |
| *(this spec)* | client-base header + version-or-tag resolution |

**Deliberately excluded:** `8e9a0a41d` (presigned npm download — a 2.3.0 feature; `NpmSlice`'s `downloadPolicy` parameter must be dropped when backporting `adc1fb990`) and `ceb6a37ae` (cluster de-clustering — not npm-specific).

**Note on release character:** this list makes 2.2.5 a **feature-bearing patch release** — package signing, provenance attestations, and `npm token`/`profile` are new capabilities, not bug fixes. The 2.2.5 CHANGELOG therefore carries a `🌟 New features` section alongside `🔧 Bug fixes`. This is intended, not an accident of cherry-picking.
