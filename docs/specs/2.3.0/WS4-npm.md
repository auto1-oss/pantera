# WS4-npm — npm API completeness & hosted-write correctness

- **Status:** 📝 DRAFT
- **Depends on:** `00-security-integrity-decisions.md` (S1 = **WIRE**, locked 2026-07-24) for sub-item .1. All other sub-items are independent.
- **Blocks:** any "npm registry parity" / "supply-chain provenance" claim.
- **Decision-gated:** only .1 (S1) and .2 (star dead-code: DELETE unless `npm star` is wanted). Everything else is a straight correctness/completeness task.
- **Size:** L overall. Eleven sub-items (WS4-npm.1 … .11); build in the order below. Three are greenfield **L** features and are flagged as such — sequence them last and expect each to carry its own itcase client image.

Scope boundary: this spec covers the **npm API surface** and **hosted (local) write correctness**. It does **not** cover npm's proxy-caching internals — upstream ETag→304 revival, ~24h filtered-metadata staleness on refresh, prerelease cooldown-parse, and whole-packument heap buffering are WS3/WS5/WS6 items (see §7).

---

## 1. Problem & goal

The npm adapter's proxy read path is solid, but its **hosted mode advertises operations it silently fails**, and its **API surface is missing the endpoints modern npm/pnpm/yarn clients call**. Two systemic root causes plus a pile of unrouted endpoints:

- **A. meta.json split-brain (hosted).** Publish writes **only** per-version files (`.versions/<v>.json`); `meta.json` is regenerated in memory on every read and **never persisted**. Every dist-tag / deprecate / unpublish handler reads `<pkg>/meta.json` → **404 for every published package**. `publish --tag beta`, `npm dist-tag ls/add/rm`, `npm deprecate`, and single-version `npm unpublish` are all broken.
- **B. `npm search` index never populated.** The search slice is handed a fresh empty in-memory index whose populator has no caller → `/-/v1/search` always returns `{"objects":[],"total":0}`.
- **C. Inert supply-chain features.** `npm publish --provenance` bundles are silently dropped; `/-/npm/v1/attestations/<spec>` and `/-/npm/v1/keys` are unrouted → `npm audit signatures` is non-functional and fails quietly. (S1 = WIRE.)
- **D. Missing standard endpoints.** `npm ping`, token CRUD, `npm profile`, `npm access`, `npm owner`, `npm hook`, HEAD, single-version manifest + local `/latest`, and real `GET /npm` info all 404 or return empty stubs.

**Goal:** every routed npm handler tells the truth (works or returns an honest status, never a silent lie), the supply-chain provenance subsystem is real end-to-end, and the standard client subcommands either work or fail cleanly. Hosted publish becomes correct for dist-tags, custom tags, deprecate, and single-version unpublish.

---

## 2. Current state (evidence, file:line)

**Router.** `npm-adapter/.../http/NpmSlice.java` builds one `SliceRoute` (`:231-529`). Routes are order-sensitive; the tail is a catch-all `GET .*\.json$` → `StorageArtifactSlice` (`:473-486`), `GET .*(?<!\.tgz)$` → `DownloadPackageSlice` (`:487-500`), and a catch-all `PUT ^/(@[^/]+/)?[^/]+$` publish (`:379-392`). **Any new `/-/…` route must be registered before these.**

- **Split-brain write path.** Publish → `CliPublish.publishWithInfo` (`CliPublish.java:57-71`) → `MetaUpdate.ByJson.update` (`MetaUpdate.java:67-92`) → `PerVersionLayout.addVersion`, which writes **only** `.versions/<v>.json` (`PerVersionLayout.java:94-96`). `meta.json` is generated on read only, in `DownloadPackageSlice.java:98` via `PerVersionLayout.generateMetaJson`, and that generator emits dist-tags with **only** `latest` (semver, prereleases excluded) — custom tags are structurally impossible (`PerVersionLayout.java:168-189`).
- **dist-tags read absent meta.json.** `GetDistTagsSlice.java:50` reads `<pkg>/meta.json` → `notFound` at `:62`. `AddDistTagsSlice.java:59-88` and `DeleteDistTagsSlice.java` read/write the same absent `meta.json` → 404. Routed at `NpmSlice.java:284-311` (PUT/DELETE) and `:393-406` (GET).
- **deprecate reads absent meta.json.** `DeprecateSlice.java:54-56` → `notFound` at `:75-77` (body consumed, honest 404 — but non-functional).
- **unpublish single-version.** `UnpublishPutSlice.java:88-89` reads `<pkg>/meta.json` → 404 (`:105`). Even if `meta.json` were seeded, `updateMeta` patches only `meta.json` (`:119-147`); the `.versions/<v>.json` file survives, so `generateMetaJson` re-adds the version → unpublish is **non-effective**.
- **search always empty.** `NpmSlice.java:465` constructs `new SearchSlice(storage, new InMemoryPackageIndex())` — a fresh empty index; `SearchSlice` ignores `storage` (`SearchSlice.java:61`, NOPMD). No caller invokes `InMemoryPackageIndex.index(...)` in main. Meanwhile publish already populates the DB index (`UploadSlice.java:125` `syncIndex.recordSync`), a different subsystem `SearchSlice` never reads.
- **local audit stub + body leak.** `LocalAuditSlice.java:38-42` returns `{}` and **does not consume the request body** (Vert.x buffer leak); reports "0 vulnerabilities" without auditing. Routed at `NpmSlice.java:407-414`.
- **provenance/attestations absent.** Repo-wide grep for `attestation|provenance|sigstore|dsse|in-toto|npm/v1/keys` is empty (only unrelated `.sigstore` file-type check at `api/v1/ArtifactHandler.java:552`). `CliPublish.updateSourceArchives` treats **every** `_attachments` entry as a base64 tgz (`CliPublish.java:98-115`, `.getString("data")`) — a provenance bundle attachment would be mis-stored as a tarball. No `/-/npm/v1/attestations` or `/-/npm/v1/keys` route exists.
- **star dead code.** `npm-adapter/.../repository/NpmStarRepository.java` and `MetadataEnhancer.enhanceWithStars` (`MetadataEnhancer.java:91-101`) are referenced only by each other/tests; `enhance()` always writes an empty `users` object (`MetadataEnhancer.java:71-74`). Never wired.
- **`GET /npm` stub.** Returns empty 200 (`NpmSlice.java:231-245`), with a standing `@todo #340` (`:55-60`).
- **missing routes (grep-confirmed absent in `NpmSlice`):** `/-/ping`, `/-/npm/v1/tokens`, `/-/npm/v1/user` (profile), `npm access`, `npm owner`, `/-/npm/v1/hooks`. No `MethodRule.HEAD` anywhere → HEAD 404.
- **single-version + local `/latest`.** A GET `/<pkg>/1.2.3` or `/<pkg>/latest` falls to the catch-all download route (`NpmSlice.java:487-500`); `DownloadPackageSlice` runs `PackageNameFromUrl` (`PackageNameFromUrl.java:44-51`) which treats the whole path (`pkg/1.2.3`) as the package name → `hasVersions` false → 404.
- **web login (local).** `POST /-/v1/login` is unrouted in `NpmSlice` (only `/-/user/org.couchdb.user:` adduser at `:415-435` and whoami at `:436-458`). Proxy/group explicitly forbid it (`RepositorySlices.java:867,933`).
- **proxy search/dist-tags.** npm-proxy routing (`RepositorySlices.java:851-892`) audits `/-/npm/v1/security/*`, forbids user-mgmt, else FALLBACK → `NpmProxyAdapter` (`NpmProxyAdapter.java:73-137`, `RaceSlice` over `CachedNpmProxySlice`→`NpmProxySlice`→`NpmProxy`). `/-/v1/search` and dist-tags are not routed to upstream → 404.

**Reusable infra:** `settings.artifactIndex()` (`ArtifactIndex`, `search(query,max,offset)→SearchResult(List<ArtifactDocument>)`, `ArtifactDocument{repoType,repoName,artifactPath,name,version,…}`) is already threaded into RepositorySlices (`:899,966,1003,1046`); publish already writes it. Token infra: `db/dao/UserTokenDao` (`store/listByUser/revoke/isValid/isValidForUser`), `api/v1/AuthHandler`, `auth/JwtTokens`; npm-adapter `repository/{StorageToken,StorageUser,Token,User}Repository`.

---

## 3. Sub-items (build order)

Legend: **[S/M/L]** size; **(a)** security/integrity, **(b)** silent-failure, **(c)** missing endpoint, **(d)** hosted-write correctness. 🟢 greenfield L feature.

### WS4-npm.1 — npm provenance / attestations / `audit signatures` (a) 🟢 **[L]**
**Current:** inert (§2, C). Bundles dropped, endpoints 404, `npm audit signatures` non-functional.
**Target:** end-to-end provenance. Accept and store the attestation/provenance bundle on publish; serve `GET /-/npm/v1/attestations/<spec>` and `GET /-/npm/v1/keys`; make `npm audit signatures` verify against the served keys.
**Plan:**
1. **Accept:** in `CliPublish.updateSourceArchives` (`CliPublish.java:98-115`), distinguish attachment types instead of assuming tgz. npm publish sends the provenance/attestation bundle in the publish payload (verify the exact carrier against the npm registry publish protocol — either an `_attachments` entry with `content_type: application/vnd.dev.sigstore.bundle+json` or a sibling top-level field). Route the bundle to a sidecar store (`<pkg>/-/attestations/<name>-<version>.sigstore` or an `attestations.json` keyed by package spec) — never into the tarball path.
2. **Store keys:** a small keyring store for the registry's public signing keys (mirror the shape used for other DB-backed material; a new `NpmKeysStore` over `auth_settings` or a dedicated table + Flyway migration `V<next>`). Seed with the local registry key; support admin upload later.
3. **Serve:** new slices `AttestationsSlice` (`GET /-/npm/v1/attestations/<@scope%2fpkg|pkg>@<version>`) and `KeysSlice` (`GET /-/npm/v1/keys` → `{"keys":[{keyid,keytype,scheme,key}]}` per npm's expected schema — **verify shape against `npm audit signatures`**). Register **before** the catch-all GET routes in `NpmSlice` (`:473`).
4. **Audit:** emit `artifact_publish` for the attestation store as part of publish (captured `AuditContext`, `package.name/version`, `client.ip`, `trace.id`); serving attestations/keys → `artifact_resolution`. `EcsLogger` state log on store + on a verification-mode serve.
**Acceptance:** itcase — `npm publish --provenance` against a local repo stores the bundle; `npm audit signatures` in a consuming project returns "verified" (0 invalid); `GET /-/npm/v1/keys` returns a non-empty keyring; a tampered attestation fails verification. (Requires a `test_images/` npm client image that can mint provenance — likely a keyless/offline test key; flag CI feasibility.)
**Notes:** greenfield; largest item. Provenance carrier + keys JSON schema **must be verified against the live npm CLI** before coding — mark those two facts as VERIFY.

### WS4-npm.2 — delete dead `npm star` subsystem (a, hygiene) **[S]** — decision-gated
**Current:** `NpmStarRepository` + `MetadataEnhancer.enhanceWithStars` never wired (§2).
**Target (default per `00`):** DELETE `NpmStarRepository.java` and `MetadataEnhancer.enhanceWithStars` (`:91-101`); keep `enhance()`'s empty `users` object (`:71-74`) since clients expect the field. **Flag:** if `npm star`/`unstar` is a wanted feature, promote to a WS4c item instead (route `PUT/DELETE /-/user/...` star ops + persist through `NpmStarRepository`).
**Acceptance:** grep shows no non-test references to the deleted symbols; `mvn clean install -T8` green (PMD unused-code clean).

### WS4-npm.3 — unify dist-tags / deprecate / unpublish on the per-version layout (b, d) **[M]** — the tentpole
**Current:** split-brain (§2, A + the four handler citations). One root cause, four broken ops.
**Target:** a **single source of truth** for tags: a durable `<pkg>/.dist-tags.json` sidecar written by the per-version layout, plus per-version file deletion on unpublish. `generateMetaJson` merges the sidecar so custom tags surface in the packument.
**Plan:**
1. `PerVersionLayout`: add `readDistTags(pkg)` / `writeTag(pkg, tag, version)` / `removeTag(pkg, tag)` operating on `<pkg>/.dist-tags.json`; on `addVersion`, initialise/refresh `latest` in the sidecar. In `generateMetaJson` (`:168-189`), merge the sidecar over the computed `latest` so both `latest` and custom tags appear.
2. `GetDistTagsSlice` (`:50`), `AddDistTagsSlice` (`:59-88`), `DeleteDistTagsSlice`: read/write the sidecar via `PerVersionLayout`, not `<pkg>/meta.json`. Return `notFound` only when the package genuinely has no versions (`hasVersions` false).
3. `DeprecateSlice` (`:54-79`): apply the `deprecated` field to the target `.versions/<v>.json` file(s) (read-modify-write the per-version file), not `meta.json`.
4. `UnpublishPutSlice` (`:88-147`): resolve the removed version, **delete `.versions/<v>.json`**, drop the tag from the sidecar if it pointed at it, recompute `latest`. Keep the `ArtifactEvent` emission (`:96-103`).
5. `publish --tag <x>`: `CliPublish`/`MetaUpdate.ByJson` must persist the requested tag into the sidecar during publish (the tag arrives in the publish payload's `dist-tags`).
**Acceptance (itcase, real client):** after `npm publish` then `npm dist-tag ls <pkg>` returns `latest`; `npm dist-tag add <pkg>@<v> beta` then `npm dist-tag ls` shows `beta`; `npm install <pkg>@beta` resolves that version; `npm publish --tag next` makes `next` visible in `dist-tag ls`; `npm deprecate <pkg>@<v> "msg"` then a packument GET shows `deprecated` on that version; `npm unpublish <pkg>@<v>` removes it and a subsequent packument GET no longer lists it (proves the `.versions/<v>.json` deletion).
**Audit:** unpublish-version → `artifact_delete`; dist-tag/deprecate mutations → emit an audit record (map to `artifact_publish` when a version's availability changes on `--tag`; for a pure tag move confirm taxonomy — do **not** invent a fifth action silently; see §9).

### WS4-npm.4 — wire `npm search` to the DB index (b) **[M]**
**Current:** empty index (§2, B). `NpmSlice.java:465`, `SearchSlice.java:61`.
**Target:** `SearchSlice` reads the shared, already-populated `ArtifactIndex`; delete the dead `InMemoryPackageIndex` (and `PackageIndex`/`PackageMetadata` if fully orphaned after).
**Plan:**
1. Thread `settings.artifactIndex()` into the local `NpmSlice` ctor (`RepositorySlices.java:647-656`) alongside the existing `syncArtifactIndexer`.
2. Give `SearchSlice` an `ArtifactIndex`; in `response` (`SearchSlice.java:99`) call `index.search(text, size, from)` (or the filtered overload with `repoType="npm"`, `repoName=<this repo>`), map `SearchResult.documents()` (`ArtifactDocument.name/version` + description if indexed) into the npm `objects[]` schema (`packageToJson`, `:120-140`), and set `total` from the result count.
3. Delete `InMemoryPackageIndex.java`; drop the `new InMemoryPackageIndex()` at `NpmSlice.java:465`.
**Acceptance (itcase):** publish two packages to a local repo, then `npm search <term>` (or `GET /-/v1/search?text=<term>`) returns both with correct name/version; a non-matching term returns `total:0`; pagination (`size`/`from`) honoured. Assert via the real client and via a direct backend GET (bypass nginx per the dev playbook).

### WS4-npm.5 — honest local audit (b) **[S]**
**Current:** `{}` stub + body leak (§2). `LocalAuditSlice.java:38-42`.
**Target:** consume the request body (fix the leak) and return a well-formed audit report. For hosted repos with no vuln DB, a valid empty bulk-advisory response (`{}` for the bulk endpoint is acceptable **once the body is consumed**); confirm the exact shape npm expects for `/-/npm/v1/security/audits` vs `/advisories/bulk` so the client doesn't error.
**Plan:** in `LocalAuditSlice.response`, `body.asBytesFuture().thenApply(...)` before returning; keep the empty-but-valid JSON. Verify shape against `npm audit` on a local-only repo.
**Acceptance (itcase):** `npm audit` against a local repo exits cleanly reporting 0 vulnerabilities; no Vert.x "body not consumed" warning in logs; a large request body does not leak (run under the existing leak assertions if any).

### WS4-npm.6 — `npm ping`, real `GET /npm`, HEAD (c) **[S]**
**Current:** `/-/ping` unrouted; `GET /npm` empty stub (`NpmSlice.java:231-245`); no HEAD.
**Target:**
1. `GET /-/ping` → 200 `{}` (npm's contract). New tiny slice or `SliceSimple`.
2. `GET /npm` → minimal real info (registry name, version, endpoints) instead of empty 200; remove the `@todo #340`.
3. **HEAD**: add `MethodRule.HEAD` coverage — either a `HeadToGetSlice` wrapper that runs the GET pipeline and drops the body (consuming the publisher per the reactive-bodies rule), or dedicated HEAD routes for packument + tarball emitting `Content-Length`/`ETag`. Register before the catch-all GETs.
**Acceptance (itcase):** `npm ping --registry <local>` succeeds; `curl -I` (HEAD) on a published packument and a `.tgz` returns 200 with `Content-Length` and no body; `GET /npm` returns non-empty JSON.

### WS4-npm.7 — single-version manifest + local `/latest` (c) **[M]**
**Current:** 404 (§2). `PackageNameFromUrl.java:44-51`, `DownloadPackageSlice`.
**Target:** `GET /<pkg>/<version>` returns that version's manifest object; `GET /<pkg>/latest` returns the `latest` dist-tag's manifest.
**Plan:** add a route (before the catch-all download) matching `/<pkg>/<version-or-latest>`; a handler that, for `latest`, resolves the tag via the `.dist-tags.json` sidecar (WS4-npm.3), then reads `.versions/<v>.json` and returns it with tarball-URL rewriting (reuse `Tarballs`/`DownloadPackageSlice` helpers). Scoped-package URL-decoding as in `DownloadPackageSlice.java:56-58`.
**Acceptance (itcase):** after publish, `GET /<pkg>/1.2.3` returns that version's manifest (name+version+dist.tarball rewritten to Pantera); `GET /<pkg>/latest` returns the latest; `npm view <pkg>@1.2.3 version` prints the version. **Depends on WS4-npm.3** for `latest` resolution.

### WS4-npm.8 — proxy fallthrough for `/-/v1/search` + dist-tags (c) **[S]**
**Current:** proxy 404s search/dist-tags (§2). `RepositorySlices.java:851-892`.
**Target:** proxy repos forward `/-/v1/search` and dist-tag GETs to the upstream (read-through), so `npm search`/`npm dist-tag ls` work through a proxy. Preserve the existing cache/cooldown/auth wrapping — do **not** leak upstream URLs (rewrite tarball URLs back to Pantera as the packument path already does).
**Plan:** add explicit RtRulePaths in the npm-proxy `SliceRoute` for `.*/-/v1/search` and dist-tag GET that dispatch through `npmProxySlice`; confirm `NpmProxy`/`CachedNpmProxySlice` pass these through rather than treating them as package names.
**Acceptance (itcase):** against a proxy repo, `npm search <term>` returns upstream results; `npm dist-tag ls <upstream-pkg>` returns upstream tags; responses are cache-wrapped (second call served from cache / offline-safe per existing proxy behavior).

### WS4-npm.9 — `npm token` CRUD + `npm profile` (+ local web login) (c) **[M]**
**Current:** `/-/npm/v1/tokens`, `/-/npm/v1/user`, `POST /-/v1/login` unrouted local (§2).
**Target:** map npm's token/profile/web-login onto existing infra.
**Plan:**
1. `/-/npm/v1/tokens`: `GET` (list) / `POST` (create) / `DELETE /-/npm/v1/tokens/token/<key>` (revoke) over `db/dao/UserTokenDao` (`listByUser/store/revoke`) — reuse the shared token type (`api`) and the shared `jwtAuthHandler` semantics; do **not** mint a bypassing auth handler. npm-adapter's `StorageTokenRepository` is the standalone fallback.
2. `/-/npm/v1/user`: `GET` returns the authenticated user's profile (username/email) from `UserRepository`; `PUT`/`POST` profile update optional (can 200 no-op with honest body if out of scope).
3. `POST /-/v1/login` (web login) local: route to `OAuthLoginSlice` (already exists, used jwt-only) so `npm login --auth-type=web` works on hosted repos.
Register all before the catch-all GET/PUT routes; audit token create/revoke as `event.category=configuration` mutations with captured context.
**Acceptance (itcase):** `npm token list` shows tokens; `npm token create` returns a usable token that authenticates a subsequent `npm publish`; `npm token revoke <id>` invalidates it; `npm profile get` returns the user; `npm login --auth-type=web` completes against a local repo.

### WS4-npm.10 — `npm access` + `npm owner` (c) 🟢 **[L]**
**Current:** unrouted; no per-package ACL/maintainer model (§2).
**Target:** per-package access level (`public`/`restricted`) and maintainer list, backed by `CachedDbPolicy` (users/roles/user_roles) — greenfield.
**Plan (sketch, expect its own detailed spec):** a package-ACL table (Flyway `V<next>`) keyed by `(repo, package)` with access level + maintainers; slices for `npm access get/set/ls`, `npm access grant/revoke`, `npm owner add/rm/ls`; enforce on publish/read via `OperationControl`. Cross-node cache invalidation must follow the policy-propagation contract (see WS2.3 — policy cache publishing).
**Acceptance:** `npm access set public|restricted`, `npm owner add/ls/rm`, and an authorization denial for a non-maintainer publish, all client-driven. **Flag:** net-new; sequence after the S/M items land; coordinate with WS2 (authz propagation).

### WS4-npm.11 — `npm hook` (c) 🟢 **[L]**
**Current:** `/-/npm/v1/hooks` unrouted; no webhook infrastructure (§2).
**Target:** webhook CRUD (`npm hook add/ls/rm/update`) + delivery on publish/dist-tag/deprecate events — greenfield.
**Plan (sketch, own spec):** hooks table (Flyway), CRUD slices under `/-/npm/v1/hooks`, and a delivery worker fed by the existing `ArtifactEvent` queue (HMAC-signed POST to the registered endpoint, retry/backoff). Delivery must run off the event loop (`HandlerExecutor`/a scheduled drain), never inline.
**Acceptance:** `npm hook add/ls/rm` manage hooks; a publish fires a signed delivery to a test sink. **Flag:** largest net-new surface; lowest priority — cut first if the release slips.

---

## 4. Implementation order

1. **WS4-npm.3** (split-brain) — unblocks .7's `latest` and is the biggest hosted-correctness win; land behind its itcase first.
2. **WS4-npm.4** (search) and **WS4-npm.5** (audit) — small, high-signal silent-failure fixes.
3. **WS4-npm.6** (ping/GET-npm/HEAD) and **WS4-npm.8** (proxy search/dist-tags fallthrough) — small missing surface.
4. **WS4-npm.7** (single-version + latest) — depends on .3.
5. **WS4-npm.9** (token/profile/web-login) — reuses shared infra.
6. **WS4-npm.2** (star delete) — trivial, fold in any time after the decision.
7. **WS4-npm.1** (provenance) 🟢 — the flagged S1 tentpole; own branch, own itcase image.
8. **WS4-npm.10** (access/owner) 🟢 and **WS4-npm.11** (hook) 🟢 — greenfield, each its own spec; sequence last.

---

## 5. Acceptance criteria (whole spec)

Prefer real-client itcase assertions (npm 9/10, plus pnpm/yarn where they exercise the path); assert **semantics and state**, never wall-clock (CLAUDE.md doctrine). Each sub-item's criteria are above; the release-level bar:

1. **dist-tags round-trip:** `npm publish` → `npm dist-tag ls` returns `latest`; `npm dist-tag add …@… beta` → `ls` shows `beta`; `npm install pkg@beta` resolves it; `publish --tag next` surfaces `next`.
2. **deprecate & unpublish effective:** `npm deprecate` marks the version in the served packument; `npm unpublish pkg@ver` removes it from the packument (per-version file deleted).
3. **search truthful:** search returns published packages (local), forwards to upstream (proxy); empty query/term behave per npm contract, no silent empty.
4. **audit honest:** `npm audit` on a local repo exits clean with the body consumed (no leak); `npm audit signatures` verifies against `/-/npm/v1/keys` once provenance lands.
5. **missing surface present:** `npm ping`, HEAD packument/tarball, single-version + `/latest`, `npm token`/`profile` work; `GET /npm` non-empty.
6. **provenance end-to-end:** `npm publish --provenance` stores a bundle served at `/-/npm/v1/attestations/<spec>`; tampering is detected.
7. **no silent lies:** any surface not implemented returns an honest status (e.g. 404/501 with a clear body), never a 200 stub that implies success.

---

## 6. Test requirements

- Unit tests use `InMemoryStorage`; JUnit 5 + Hamcrest **matcher objects** (`new IsEqual<>(x)`), reason strings on multi-assert; `@TempDir` not `Files.createFile`.
- New/expanded itcases under `-Pitcase` backed by `test_images/` npm client images: dist-tags, deprecate, single-version unpublish, search (local + proxy), audit, provenance (may need a new image capable of `--provenance`; **flag CI key-provisioning feasibility** — keyless Sigstore may not run in the SHA-pinned/offline CI, so a local test key path may be required).
- Split-brain regression guard: a test that publishes, then asserts `dist-tag ls` and a custom-tag install work **without** hand-planting `meta.json` (today's `NpmDistTagsIT` passes only because it seeds `meta.json` — remove that crutch).
- Router-ordering guard: assert new `/-/…` routes are not swallowed by the catch-all `.*\.json$` / download routes.
- Never assert absolute latency; prove behavior with state + invocation counts.

---

## 7. Out of scope (other workstreams / deferred)

- **Proxy caching internals:** upstream ETag→304 revival (`RxNpmProxyStorage.java:105-135` stores hashes but no conditional GET), ~24h filtered-metadata staleness on background refresh, prerelease cooldown-parse (`DownloadAssetSlice` `lastIndexOf('-')`), and whole-packument heap buffering → **WS3 (streaming/memory), WS5 (cooldown coherence), WS6 (upstream efficiency)**.
- **Group packument union-merge** (npm group is first-2xx-wins) → deferred product decision (gap-analysis §"DEFER").
- **`_rev` maintenance** — npm tolerates its absence; not pursued.
- **npm upload tarball-integrity recompute** — the audits rate hosted publish integrity as correct; not adding a digest-verify pass here (contrast PyPI/Composer S7).
- Cross-node cache coherence for any new npm state (tokens/ACLs) beyond honoring the WS2.3 policy-propagation contract where WS4-npm.10 touches policy.

---

## 8. Risks & rollback

- **WS4-npm.3 touches the hosted write/read contract** — highest-risk item. The `.dist-tags.json` sidecar changes on-disk layout: existing hosted packages have no sidecar, so `generateMetaJson` must fall back to computed `latest` when the sidecar is absent (backward-compatible; no migration required). Land behind the crutch-free itcase before flipping.
- **Router ordering** — a new `/-/…` route placed after the catch-alls silently does nothing; a bad regex can shadow package GETs. Guard with the ordering test; review the `SliceRoute` insertion point for every new route.
- **WS4-npm.1 provenance** — the provenance carrier and `/-/npm/v1/keys` JSON schema are the two facts to verify against the live npm CLI; getting them wrong makes `audit signatures` fail confusingly. Keep the accept-side change in `CliPublish` strictly additive (unknown attachments must not break tarball handling).
- **WS4-npm.9 tokens** — reuse the shared `jwtAuthHandler`/token type; a fresh handler would skip blocklist + JTI ownership checks (CLAUDE.md JWT rule). Security-sensitive.
- Rollback is `git revert` per sub-item — no feature flags (CLAUDE.md); each sub-item is an independent branch.

---

## 9. Docs & observability

- **CHANGELOG.md** (`### 🔧 Bug fixes`): dist-tags/deprecate/unpublish now work on published packages; `npm search`/`npm audit`/`npm ping`/HEAD/single-version/`/latest` fixed. `### 🌟 New features`: provenance/attestations + `npm audit signatures`, `npm token`/`profile`/web-login, (later) `npm access`/`owner`/`hook`. `### 🔒 Security`: token endpoints reuse the shared auth handler. One attributed bullet each; no dev-log narrative; never disclose unpatched weaknesses.
- **docs/rest-api-reference.md** — every new route: `/-/ping`, `/-/npm/v1/tokens`, `/-/npm/v1/user`, `/-/npm/v1/attestations/<spec>`, `/-/npm/v1/keys`, `/-/npm/v1/hooks`, single-version/`latest`, HEAD.
- **docs/user-guide/** npm page — dist-tags/channels, `--provenance`, `npm audit signatures`, token/profile, search on local vs proxy, `npm access`/`owner` (when landed).
- **Audit taxonomy (CLAUDE.md):** unpublish-version → `artifact_delete`; attestation store → `artifact_publish`; attestation/keys serve → `artifact_resolution`; token/access/owner/hook mutations → `event.category=configuration`. For a **pure dist-tag move** (no availability change) the four-action artifact taxonomy has no exact slot — **confirm with the maintainer** whether to map to `artifact_resolution` or extend the taxonomy; do not invent a fifth action silently. Every audit record carries `user`/`client.ip`/`trace.id`/`package.name`/`package.version`/`package.size`/`repository.{name,type}` via a `captureAuditContext(headers)` at slice top.
- **Metrics → panels (CLAUDE.md "a metric without a panel is invisible"):** if added, `pantera.npm.search.requests_total{result=hit|empty}`, `pantera.npm.disttag.ops_total{op}`, `pantera.npm.attestation.{stored,served}_total`, `pantera.npm.hook.delivery_total{outcome}` — bounded tags only (repo capped by `RepoNameMeterFilter`, never package/version), each guarded by `MicrometerMetrics.isInitialized()`, each with a Grafana panel in `grafana/provisioning/dashboards/` verified against a live `:8087/metrics/vertx` scrape. Log every state transition (attestation verify pass/fail, hook delivery failure) via `EcsLogger`, not just counters.
