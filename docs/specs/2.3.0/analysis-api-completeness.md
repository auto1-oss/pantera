# Pantera 2.3.0 — API Completeness Matrix

*Companion to the gap analysis. Six exhaustive per-format API audits (npm, Maven/Gradle, PyPI, Composer, Go, Docker/OCI) read against `master`. Purpose: catalog every endpoint/feature that is **BROKEN-BUT-PRESENT** (routed or advertised but silently non-functional) or **MISSING**, because for an AI-built release these are the concrete, well-scoped coding tasks.*

**Direct answer to "is search the only one, or are there others?"** — Search is the tip. **Every format has advertised-but-broken endpoints**, and the pattern you spotted (a feature that *looks* implemented and lies about success) recurs 40+ times. The most dangerous cluster is **advertised security/integrity features that provide zero protection.**

---

## 1. The scary tier — advertised security/integrity that is INERT

These ship code, tables, config flags, or 200-OK responses that imply a protection which does not exist. For a "bulletproof" release these are worse than a missing feature, because users *trust* them.

| Format | Advertised as | Reality | Evidence |
|---|---|---|---|
| **npm** | `npm publish --provenance` / attestations / `npm audit signatures` | **Zero attestation code.** Provenance bundle in `_attachments` silently dropped; `/-/npm/v1/attestations/<spec>` + `/-/npm/v1/keys` unrouted → 404. | grep empty; `CliPublish.java:98-115` treats every attachment as a tgz |
| **Maven** | `.asc` PGP signature verification (`verifyPgp` flag, `pgp_keyring` table) | **Full verifier + keyring + Flyway V131 + flag — zero callers.** `.asc` served but never verified. A swapped/unsigned artifact passes. | `PgpVerifier.java` no non-test refs; `V131__pgp_keyring.sql` |
| **Go** | `.zip` "same integrity guarantee the Maven adapter received" (javadoc) | **Inert.** `.ziphash` sidecar URL doesn't exist in GOPROXY, and Go's hash is an H1 dirhash, not a zip-byte SHA — nothing is ever verified. | `CachedProxySlice.java:74-85,997` |
| **Go** | checksum-db security (`GONOSUMCHECK` in docs) | **sumdb not proxied**; the doc's `GONOSUMCHECK` flag has been a **no-op since Go 1.18**. Clients must `GOSUMDB=off` (i.e. *disable* verification). | `go.md:23,40,52,130`; tests use `GOSUMDB=off` |
| **Docker** | OCI 1.1 referrers (cosign/notation/SBOM discovery) | **Permanent empty stub returning 200.** `subject` never indexed, `OCI-Subject` never emitted. Clients believe discovery works and get nothing → cosign OCI-mode, notation, `oras discover/attach`, SBOM attach all silently non-functional. | `ReferrersSlice.java:64-74` |
| **Docker** | Proxy blob cache integrity | **Unverified.** Cache-store uses `TrustedBlobSource` (never hashes; digest from path, not recomputed) → corrupt/truncated upstream bytes cached and re-served under a "correct" digest. The real verifier `CheckedBlobSource` is **dead code**. | `CachingBlob.java:142`, `TrustedBlobSource.java:57-63` |
| **PyPI** | twine `gpg_signature` upload | Silently discarded — no `.asc` stored. Uploads never digest-verified against the client-declared hash. | `WheelSlice.java:185-199,123` |
| **Composer** | dist integrity | `.sha256` sidecar inert; `dist.shasum` (in the packument) never verified; hosted publish writes no shasum. | (storage audit) |

**Recommended stance:** for each, **wire it or delete it + drop the claim.** Shipping inert security is a liability the blog's "supply-chain quarantine" framing makes worse.

---

## 2. Cross-format catalog — BROKEN-BUT-PRESENT (routed/advertised, silently wrong)

The "lies about success" list. Ordered roughly by user-facing blast radius.

| Format | Endpoint / op | What happens | Evidence |
|---|---|---|---|
| **Composer** | `php-proxy` root `GET /packages.json` | **404 — standalone php-proxy can't bootstrap `composer install`.** Correct synthesized handler is unreachable dead code; only masked when a local member fronts it in a group. | `ComposerProxySlice.java:216-227,299` |
| **Composer** | root `metadata-url`/`search`/`list`/`security-advisories` (proxy) | **Leaks upstream URLs verbatim → client goes straight to packagist, bypassing Pantera cache, cooldown, and auth.** | `ComposerRootPackagesHandler.java:255-278` |
| **PyPI** | hosted yank/unyank (`POST …/{yank,unyank}`) | **Silent no-op** — sidecar flips but the served index is frozen at upload; pip/uv never see `data-yanked`. **Also: no per-repo authz — any valid token can yank any repo.** | `PypiHandler.java:176-202` |
| **PyPI** | proxy `/simple/<pkg>/` (HTML/pip) | **Drops `data-yanked` for every pip client** (model/parser/rewriter have no yanked field), even with zero cooldown blocks. | `PypiMetadataRewriter.java:44-66` |
| **npm** | `dist-tag ls/add/rm` | **404 for every published package** — handlers read a `meta.json` publish never writes. Breaks `install pkg@beta`, `publish --tag`, channel promotion. | `GetDistTagsSlice.java:50` vs `PerVersionLayout.java:94` |
| **npm** | `deprecate`, `unpublish <version>` | **404 for every published package** (same split-brain); unpublish is non-effective even if seeded (`.versions/<v>.json` survives). | `DeprecateSlice.java:55`, `UnpublishPutSlice.java:88` |
| **npm** | `npm search` `/-/v1/search` | **Always `{"objects":[],"total":0}`** — the Caffeine index is never populated (populator has no caller). | `NpmSlice.java:465` |
| **npm** | local `npm audit` | `{}` stub — reports "0 vulnerabilities" **without auditing**, and leaks the request body. | `LocalAuditSlice.java:38-42` |
| **Composer** | `composer search` / `show -a` / `composer audit` | **Silently return nothing** on local; leak to packagist on proxy/group. No routes exist. | grep clean |
| **Composer** | `available-packages-url` | Advertised in the local root, **no route behind it → 404**. | `SatisLayout.java:170-175` |
| **Go** | hosted `@latest` | **Wrong version** — lexicographic sort (`v0.9.0 > v0.10.0`). A correct semver comparator exists in-tree, just isn't used here. | `LatestSlice.java:71-73` |
| **Go** | proxy `@v/list`, `@latest` | **Never cached** → `go get`, `go get -u`, `go list -m -versions` break the moment upstream is unavailable, even for fully-cached modules (contradicts the "survives outages" doc). | `GoListHandler.java:185`, `GoLatestHandler.java:194` |
| **Docker** | `GET referrers/<digest>` | Empty stub (see §1). | `ReferrersSlice.java:64-74` |
| **Docker** | `docker-group` `tags/list` / `_catalog` | **Not aggregated** — returns only the first member's view (proxy mode merges; group doesn't). | `RepositorySlices.java:1038-1052` |
| **Maven** | local artifact `If-None-Match`/304 | **ETag advertised but ignored** — every re-resolve re-downloads the full artifact from a warm cache. | `ArtifactHeaders.java:70-73` |
| **Maven** | proxy HEAD cache-hit | Javadoc promises `Last-Modified`; code emits only `Content-Length`. | `HeadProxySlice.java:61-89` |
| **Maven** | group metadata `.sha256`/`.sha512` | Bypass the merge → checksum computed over a member's own (maybe unfiltered) bytes ≠ served bytes → **mismatch**. | `MavenGroupSlice.java:237-239` |
| **PyPI** | proxy `/pypi/<pkg>/<ver>/json` | Unfiltered upstream passthrough → a cooldown-blocked version's metadata leaks. | `PypiJsonMetadataRequestDetector.java:76` |

---

## 3. Missing standard endpoints (client errors cleanly — not silent)

| Format | Missing | Impact |
|---|---|---|
| **npm** | `npm ping`, `token` CRUD, `profile`, `access`, `owner`, `hook`, `star`/`unstar` (**dead code present**), web login (`POST /-/v1/login`), HEAD, single-version manifest `/<pkg>/<v>` + local `/latest` | CLI subcommands error; token/access/owner management impossible |
| **PyPI** | PEP 658 `.metadata` (hosted), PEP 700 `versions[]`/`size`, PEP 714 `core-metadata` alias, legacy `/pypi/<pkg>/json` (local), proxy/group HEAD | `--require-hashes` + metadata-only resolution fail for hosted; uv HEAD probes inconsistent |
| **Composer** | `security-advisories` API, `search`/`list.json`, `available-packages.json`, `metadata-changes-url`, v1 provider-includes, HEAD | `composer audit`/`search`/`show -a` non-functional |
| **Go** | `/sumdb/*` proxy, local HEAD | Air-gapped clients must disable sum verification |
| **Maven** | server-side `maven-metadata.xml` regen on deploy, Range/206/`Accept-Ranges`, redeploy immutability, artifact 304 | Concurrent deploys drop versions; no resumable downloads; mutable releases |
| **Docker** | `DELETE manifests`/`blobs` (no GC), multi-chunk `PATCH` (single-chunk only → 405), `Link` pagination, full `Accept` negotiation, native token-server | No image deletion/GC; chunked pushers fail; paginating clients truncate |

---

## 4. Cross-cutting API patterns (fix the class, not the row)

1. **Dead-code security/integrity subsystems** — verifiers/indexers written but never wired (Maven PGP, Docker `CheckedBlobSource`, npm `NpmStarRepository`, Go `Goproxy.java`, orphaned Maven `RepoHead`, Go `CacheTimeControl`). *Wire or delete; never ship inert.*
2. **Read/write split-brain** — a write path and a read path touch different stores (npm `meta.json` vs `.versions/`; PyPI yank sidecar vs frozen served index). *Single source of truth per resource.*
3. **Advertised-but-unrouted** — a served document points at endpoints that 404 (Composer `available-packages`, npm dist-tags reading absent `meta.json`). *A capability advertised must have a working route.*
4. **Leak-to-upstream = cache/cooldown/auth bypass** — proxy returns upstream URLs verbatim so clients skip Pantera entirely (Composer root, PyPI `<ver>/json`). **Security + cooldown bypass.** *Rewrite every URL a proxy emits to point back at Pantera.*
5. **No conditional-GET / HEAD parity** — 304/If-None-Match and HEAD are implemented per-format inconsistently (Maven artifacts, npm upstream, Composer, PyPI proxy all lack pieces). *Standardize a validator+HEAD contract across adapters.*
6. **Group mode doesn't aggregate** — npm/Maven/PyPI/Go/Docker groups are first-2xx-wins; only Composer-group and Docker-*proxy* merge. *Decide the group contract per format.*

---

## 5. The 2.3.0 "API completeness" workstream (WS4-expanded)

This refines and enlarges WS4 in the gap-analysis plan. Since you want it all in 2.3.0, here's the full set, sized, in recommended build order. Security/integrity first (they're the trust-breakers), then the silent-failure endpoints, then the missing surface.

### 4a — Inert security/integrity: wire-or-delete (decision-gated, do first)
- **[M] Docker referrers (serve half):** index `subject` on manifest push, serve from `ReferrersSlice`, emit `OCI-Subject`. Highest-value integrity item.
- **[M] Docker proxy blob verify:** swap cache-store to a digest-verifying tee (reuse `DigestedFlowable`); delete `CheckedBlobSource` or wire it.
- **[M→L] Maven PGP:** wire (`verifyPgp` parse + `JdbcKeyringStore` install + verify `.asc` on fetch + admin keyring UI) **or** delete package+migration. (Recommend: delete unless PGP verification is a real requirement.)
- **[S] Go `.zip` integrity claim:** delete the inert `.ziphash` wiring + fix the javadoc (or gate behind real dirhash+sumdb — see 4c).
- **[S] PyPI/Composer upload digest verify:** compare twine `sha256_digest` / packument `dist.shasum` on store; reject mismatch.
- **[L] npm `--provenance`/attestations:** greenfield — accept/store the bundle, serve `/-/npm/v1/attestations/<spec>` + `/-/npm/v1/keys`, wire `npm audit signatures`.

### 4b — Silent-failure endpoints: make them tell the truth
- **[S] Composer proxy root `/packages.json`** — revive the synthesized-correct body before `rootHandler`. **[S]** rewrite all top-level proxy root URLs to Pantera-local (kills the upstream-bypass leak; also fix the group leak).
- **[S-M] PyPI yank:** regenerate/invalidate the index on yank/unyank **+ add per-repo authz** (currently any token yanks any repo). **[S-M]** add a `yanked` field through the proxy parser/rewriter so pip sees `data-yanked`.
- **[M] npm dist-tags/deprecate/unpublish split-brain:** operate on the per-version layout + a durable `dist-tags.json`; make `generateMetaJson` emit stored custom tags. One change closes four broken ops.
- **[M] npm search:** point `SearchSlice` at the already-populated `DbArtifactIndex`; delete the dead Caffeine index.
- **[S] npm local audit:** consume the body (fix leak); shape a valid report or proxy upstream.
- **[S] Go hosted `@latest`:** semver sort (reuse `VersionComparators.semver()`). **[M]** TTL-cache proxy `@v/list`/`@latest` (offline-safe) + single-flight.
- **[M] Composer search/`list.json`** from `DbArtifactIndex`; advertise `list`. **[L]** `composer audit`/security-advisories (new subsystem: proxy-cache packagist advisories and/or local store + group aggregation).
- **[S] Docker `Link` pagination** on `tags/list`+`_catalog`; **[M]** docker-group tag/catalog aggregation (reuse `JoinedTagsSource`).
- **[S] Maven:** honor `If-None-Match`→304 on local + proxy-cache-hit artifacts; group `.sha256/.sha512` over served bytes; consistent proxy artifact headers + HEAD `Content-Length`/`Last-Modified`.

### 4c — Missing standard endpoints
- **[S each] npm** `ping`, `GET /npm` real info, HEAD (or HEAD→GET rewrite), proxy fallthrough for `/-/v1/search`+dist-tags; **[M]** `token`/`profile` over existing storage repos, single-version + `/latest` local; wire-or-delete `star`.
- **[L] npm** `access` + `owner` (per-package ACL/maintainer on `CachedDbPolicy`); **[L]** `hook`.
- **[M-L] PyPI** hosted PEP 658 (`.metadata` extract+write+route); **[M]** PEP 700 `versions[]`/`size`; **[S]** PEP 714 alias; **[S-M]** proxy HEAD; **[M]** local legacy JSON + filter `<ver>/json`.
- **[M] Composer** `available-packages.json` route; **[M]** conditional `If-Modified-Since`/304 (the `lastModifiedStore` is already captured, just unread); v1 providers + HEAD.
- **[M] Go** `/sumdb/*` proxy+cache (lets clients keep verification on, offline-safe); **[L]** genuine H1-dirhash zip verify (depends on sumdb).
- **[M] Maven** wire `RangeSlice`/206/`Accept-Ranges`; **[M]** redeploy immutability; **[L]** server-side metadata regen on deploy (also in WS4 core — the biggest hosted-correctness item).
- **[M] Docker** `DELETE manifests`/`blobs` + interface impls (GC/`skopeo delete`); **[M]** true multi-chunk `PATCH`; **[L]** full `Accept`-driven manifest negotiation (+406, cache-key by variant).

### Build-order recommendation
1. **4a security/integrity** first — decide wire-vs-delete per item (I'll bring options); these are the trust-breakers and several are small.
2. **4b silent-failures** — biggest UX/correctness wins; most are S/M and already routed.
3. **4c missing surface** — the S/M items fold in cheaply; the **L greenfield** items (npm attestation, npm access/owner, npm hook, Composer audit, Docker full Accept-negotiation, genuine Go+sumdb integrity) are real features — sequence them explicitly and expect each to be its own spec.

**Docs debt bundled throughout:** Composer has no user-guide page; the Go docs actively mislead (`GONOSUMCHECK`); every new route/field lands in `configuration-reference.md` + CHANGELOG per the same-PR rule.

---

## 6. What this means for the plan

The API-completeness surface is **large but overwhelmingly S/M** — most items are "a routed handler already exists and lies; make it truthful," which is ideal work to hand a Sonnet agent against a precise spec + a client-driven acceptance test. The genuinely heavy items are a handful of greenfield features (npm attestation/access/owner/hook, Composer audit, Docker referrers-full + Accept-negotiation, Go sumdb+real integrity) and they should each get their own spec.

Recommended next step: I turn §1 (wire-or-delete decisions) into a short **decision doc** for your sign-off — because "delete the inert PGP/`.ziphash`/`CheckedBlobSource`" vs "implement it for real" is a product call that changes the size of the release materially — and in parallel I start authoring the per-workstream specs for the unambiguous 4b/4c items so Sonnet can begin.
