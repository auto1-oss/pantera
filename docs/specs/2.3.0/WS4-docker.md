# WS4-docker — Docker / OCI-Distribution API completeness & hosted-write correctness

- **Status:** 📝 DRAFT
- **Depends on:** `00-security-integrity-decisions.md` (S2 = WIRE M-half, S3 = WIRE — both **✅ signed off 2026-07-24**). No hard code dependency on WS1/WS2/WS3.
- **Blocks:** any "cosign / SBOM discovery works", "image GC / `skopeo delete`", "multi-arch chunked push", or "group registry aggregates tags" claim.
- **Decision-gated:** WS4-docker.1 (S3) and WS4-docker.2 (S2) are gated by `00` — now unblocked. The rest are unconditional correctness/completeness.
- **Size:** M overall. Nine independent sub-items (`WS4-docker.1`…`.9`), each a separately-shippable agent task. One is L (`.7` Accept negotiation) and may split off.

## 1. Problem & goal

The Docker adapter's read/pull path and monolithic push work. Its **advertised OCI 1.1 surface lies** (referrers is a permanent empty 200 stub — cosign OCI-mode, `oras discover/attach`, notation, SBOM attach are all silently non-functional), its **proxy blob cache caches unverified upstream bytes** under a "correct" digest, `docker-group` **does not aggregate** `tags/list`/`_catalog`, paginating clients **truncate** at page 1, and image **deletion/GC, multi-chunk uploads, and Accept negotiation are missing**. See `.drafts/pantera-2.3.0-api-completeness.md` (Docker rows §1–§3) and the source-of-truth audit `scratchpad/gap-analysis/api-docker.md`.

**Goal:** make every routed/advertised Docker endpoint truthful — OCI 1.1 referrers actually index and serve, proxy cache integrity matches `ProxyCacheWriter`, group mode aggregates, pagination is honest, and the standard delete / multi-chunk / negotiation surface exists — verified against **real clients** (cosign, oras, skopeo, docker buildx) in itcase, not just unit stubs.

**Non-goals for 2.3.0** (§3): a native Docker/OCI **token-server** inbound auth flow (Basic / JWT-as-password stays the auth model); the **full** OCI 1.1 conformance beyond the serve-half — fallback referrers *tag schema* (`sha256-<digest>` index tag) and **proxy-through of upstream referrers** are deferred (`.2` covers hosted-registry referrers only).

## 2. Work items

Ordered by build order (a → e). Each is self-contained: current state (evidence `file:line`), target, file-level plan, acceptance, size.

---

### WS4-docker.1 — Proxy blob cache integrity: digest-verifying cache-store (S3, security/correctness)

**Current state.** The pull-through proxy caches layer bytes via `CachingBlob.saveToCacheAsync` → `this.cache.put(new TrustedBlobSource(fileContent, this.origin.digest()))` (`docker-adapter/.../cache/CachingBlob.java:135-142`). `TrustedBlobSource.saveTo` **never re-hashes** — it saves the content verbatim and reports the digest passed to its constructor (`docker-adapter/.../asto/TrustedBlobSource.java:56-63`). A truncated/corrupt upstream body is therefore cached and re-served under the upstream-declared digest, silently. The correct verifier already exists — `CheckedBlobSource.saveTo` tees the content through `DigestedFlowable`, recomputes the hex, and throws `InvalidDigestException` on mismatch (`docker-adapter/.../asto/CheckedBlobSource.java:52-73`) — but is **instantiated only in a test** (`BlobsITCase.java:71`); it is dead in production. This diverges from `ProxyCacheWriter`'s integrity contract (CLAUDE.md "stream-through tee with integrity verification").

**Target.** The proxy blob cache-store re-hashes streamed bytes and refuses to cache (and refuses to serve from cache) on mismatch; corrupt upstream never lands under a trusted digest. Client still receives the tee'd bytes and verifies its own content digest, but Pantera's *cache* is never poisoned.

**Plan.**
- `CachingBlob.saveToCacheAsync` (`:135-142`): swap `TrustedBlobSource` → `CheckedBlobSource` (reuse it as-is; it already wraps `DigestedFlowable`). On the `put` failure path (`whenComplete` `ex != null`, `:143-158`) treat `InvalidDigestException` as a **hard cache-reject**: delete the temp file (already done), do **not** persist a partial blob, and log a state transition (see Observability) — do not fall back to caching unverified bytes.
- Because `CheckedBlobSource` verifies at `onComplete`, ensure the streamed `Content.From(size, streamFromFile(tmp))` is consumed exactly once and rollback of any partially-written cache key is fire-and-forget (mirror `ProxyCacheWriter` rollback semantics — CLAUDE.md notes rollback-after-partial is fire-and-forget by design).
- Delete `CheckedBlobSource` is **not** an option (`00` S3 row: "do NOT delete; wire it under S3"). Keep `TrustedBlobSource` for the hosted push path (`AstoManifests`, `AstoLayers`, `Upload.putTo`) where bytes are already Pantera-computed and trusted — do **not** blanket-replace it.
- No interface change.

**Acceptance.**
- Unit: a proxy fetch whose upstream `Content` yields bytes whose SHA-256 ≠ the declared digest results in **zero** bytes written to the cache storage (invocation-counting `InMemoryStorage`), an `InvalidDigestException`-class rejection, and a logged mismatch. A matching-digest fetch caches exactly once.
- Itcase: pull a real image through `docker-proxy`; corrupt-upstream simulation via a fault-injecting fake upstream proves the poisoned bytes are never served from cache on a subsequent pull.

**Size: S–M** (small code, correctness-critical; the verifier already exists).

---

### WS4-docker.2 — OCI 1.1 Referrers: index `subject` on push, serve from `ReferrersSlice` (S2 M-half, highest value)

**Current state.** `ReferrersSlice.response` hard-returns a constant empty OCI Image Index with 200 OK for **every** target digest (`docker-adapter/.../http/ReferrersSlice.java:64-74`, `EMPTY_INDEX` `:45-51`). Push never reads a manifest `subject`: `PushManifestSlice.response` (`docker-adapter/.../http/manifest/PushManifestSlice.java:57-105`) and `AstoManifests.put` (`docker-adapter/.../asto/AstoManifests.java:66-77`) write only the by-digest and by-reference links (`addManifestLinks` `:229-234`), emit no `OCI-Subject` header, and index nothing. The `Manifest` model exposes `mediaType()`/`isManifestList()`/`manifestListChildren()` (`docker-adapter/.../manifest/Manifest.java:91,158,174`) but **no `subject()` or `artifactType()` accessor**. Net: the 200-OK stub signals "referrers supported" so cosign OCI-mode / `oras discover` / notation / SBOM discovery believe it works and get an empty set.

**Target.** A manifest pushed with a `subject` descriptor is indexed against that subject; `GET /v2/<name>/referrers/<digest>` returns a real OCI Image Index of the referring manifest descriptors; the push response carries `OCI-Subject: <subject-digest>`; the `?artifactType=` query filter is honored with `OCI-Filters-Applied: artifactType`. This makes cosign OCI-mode, `oras attach`/`oras discover`, notation, and SBOM attach functional for **hosted** registries.

**Plan.**
- **Model:** add `Optional<Digest> subject()` (parse `subject.digest`) and `Optional<String> artifactType()` to `Manifest` (`docker-adapter/.../manifest/Manifest.java`) — mirror the existing null-tolerant `getString`/`getJsonObject` accessors.
- **Index write:** new `AstoManifests` step invoked from `put`/`putUnchecked` (`:66-90`): when `manifest.subject()` is present, write a per-referrer descriptor under a new `Layout` prefix — recommend a **prefix-listable** layout `referrers/<subjectDigestAlg>/<subjectHex>/<referrerDigest>` holding the referrer's OCI descriptor JSON (`digest`, `mediaType`, `artifactType`, `size`, `annotations`). Prefix-per-subject avoids read-modify-write races on a shared list file (important under concurrent attach). Reuse the `addLink` write pattern (`:243-248`). **Do not** gate on `ImageTag.valid` — referrer artifacts are pushed by digest, not tag (unlike the audit/index gate at `PushManifestSlice.java:80`).
- **Serve:** rewrite `ReferrersSlice.response` (`:64-74`) to list the `referrers/<subjectDigest>/` prefix, assemble the descriptors into an OCI Image Index (`schemaVersion:2`, `mediaType: application/vnd.oci.image.index.v1+json`, populated `manifests[]`), and return 200. Parse `?artifactType=` from the request line; when present, filter descriptors by `artifactType` and add `OCI-Filters-Applied: artifactType`. Empty result stays a valid empty index + 200 (spec-required).
- **Push header:** in `PushManifestSlice.response` (`:95-100`), when the pushed manifest had a `subject`, add `OCI-Subject: <subject-digest>` to the `created()` response.
- **New interface method** on `Manifests` (`docker-adapter/.../Manifests.java`): `CompletableFuture<Referrers> referrers(Digest subject, Optional<String> artifactTypeFilter)` (or a `List<Descriptor>`), impl in `AstoManifests`; `ProxyManifests`/`CacheManifests` return empty (proxy-through is deferred, §3). `ReferrersSlice` calls it via `docker.repo(name).manifests()`.
- **Audit:** referrers GET is metadata listing → emit `artifact_resolution` (CLAUDE.md audit table) with captured `AuditContext`.

**Acceptance** (real clients — add an `oras` + `cosign` client image under `test_images/` if absent):
- Itcase: `oras attach` an artifact to a pushed image, then `oras discover` returns it; `OCI-Subject` header present on the attach push; the referrer's `artifactType` appears; a `?artifactType=` filter narrows the set and the response carries `OCI-Filters-Applied`.
- Itcase: `cosign sign` (OCI 1.1 / referrers mode) then `cosign verify` succeeds against a hosted `docker` repo (contrast: the tag-scheme `sha256-<hash>.sig` path already works — keep it green).
- Unit: pushing a manifest with a `subject` writes exactly one referrers entry; `ReferrersSlice` assembles it; no-subject push writes none; empty subject → empty-index 200.

**Size: M.** Highest-value item in this spec.

---

### WS4-docker.3 — `docker-group` aggregates `tags/list` + `_catalog` (silent failure)

**Current state.** `docker-group` falls through the generic `GroupResolver` (`pantera-main/.../RepositorySlices.java:1038-1052`) — first-2xx-wins, no merge — so `tags/list` and `_catalog` return only the **first** member's view. Proxy mode already merges: `CacheDocker.catalog` uses `JoinedCatalogSource` (`docker-adapter/.../cache/CacheDocker.java:110`), `CacheManifests.tags` uses `JoinedTagsSource` (`.../cache/CacheManifests.java:219`). A composite that does exactly the group job already exists — `MultiReadDocker.catalog` → `JoinedCatalogSource` (`.../composite/MultiReadDocker.java:72`) and `MultiReadManifests.tags` → `JoinedTagsSource` (`.../composite/MultiReadManifests.java:104`) — but is not wired for `docker-group`.

**Target.** `docker-group` `tags/list` and `_catalog` return the **union** across members (dedup, ordered), while manifest/blob GET keep first-2xx-wins semantics (correct for content-addressed pulls).

**Plan.**
- Add a `docker-group`-specific branch in `RepositorySlices.buildSlice` (before/within the generic group `case`, `:1038`): build a `DockerSlice` over a `MultiReadDocker` composed of the members' `Docker` instances (each member resolved read-only, mirroring how `docker`/`docker-proxy` construct `AstoDocker`/`DockerProxy`). `MultiReadManifests`/`MultiReadDocker` already give merged `tags()`/`catalog()` via `JoinedTagsSource`/`JoinedCatalogSource`; manifest/blob reads inherit `MultiReadRepo`'s prioritized first-hit walk.
- Preserve the group contract for non-list ops: all-members-unavailable must **not** 404 (CLAUDE.md group invariant — 404 gets negative-cached and outlives the outage). `JoinedTagsSource`/`JoinedCatalogSource` already swallow per-member errors to empty lists (`JoinedTagsSource.java:70`, `JoinedCatalogSource.java:64`) so a partial-member outage degrades to a partial union, not a hard failure — keep that.
- Keep auth/authz wrapping identical to the existing group branch (`CombinedAuthzSliceWrap` + `OperationControl`).

**Acceptance.**
- Itcase: `docker-group` fronting two `docker`/`docker-proxy` members each holding distinct tags for the same repo → `skopeo list-tags docker://.../repo` returns the **merged** tag set; `_catalog` returns the merged repo set.
- Unit: `MultiReadManifests.tags` / `MultiReadDocker.catalog` union + dedup (extend existing `JoinedTagsSourceTest`/`JoinedCatalogSourceTest` coverage to the group wiring).

**Size: M.**

---

### WS4-docker.4 — `Link: rel="next"` pagination on `tags/list` + `_catalog` (silent truncation)

**Current state.** `TagsSlice` builds the body from `manifests().tags(Pagination.from(...))` and returns `ResponseBuilder.ok().header(ContentType.json()).body(tags.json())` with **no `Link` header** (`docker-adapter/.../http/TagsSlice.java:46-59`). `CatalogSlice` is identical (`.../http/CatalogSlice.java:42-53`). The `n`/`last` query params filter the body but the client is never told a next page exists, so paginating clients (`skopeo list-tags`, crawlers) see a truncated page 1 as the complete set.

**Target.** When a page is truncated (result count == requested `n` and more entries exist), emit `Link: </v2/<name>/tags/list?n=<n>&last=<lastTag>>; rel="next"` (and the `_catalog` analogue) per the Distribution spec, so clients follow to completion.

**Plan.**
- Determine "has next page" from the paginated source — the `Tags`/`Catalog` page model (produced by `AstoTags`/`TagsPage`/`CatalogPage`) must expose whether more entries remain and the `last` cursor value. If it doesn't, thread it through `Pagination` + the page builders (`docker-adapter/.../misc/TagsPage.java`, `CatalogPage.java`) — request `n+1`, emit `n`, set `next` when the extra element exists.
- In `TagsSlice.response` (`:52-57`) and `CatalogSlice.response` (`:47-51`), add the `Link` header when a next page exists. Preserve the mandatory body-consume (`body.asBytesFuture()`, `:48`/`:44`).
- URL-encode `last` and preserve the repo-name path for `tags/list`.

**Acceptance.**
- Itcase: `skopeo list-tags` against a repo with more tags than the page size returns the **full** set across follow-ups; a raw `curl` of `tags/list?n=<k>` carries a `Link: …; rel="next"` when truncated and omits it on the last page.
- Unit: page-boundary cases (exactly `n`, fewer than `n`, more than `n`) emit/omit `Link` correctly; `last` cursor round-trips.

**Size: S.**

---

### WS4-docker.5 — `DELETE manifests` + `DELETE blobs` and `Manifests.delete` / `Layers.delete` (missing — GC / `skopeo delete`)

**Current state.** No DELETE route for manifests or blobs — `DockerSlice`'s `SliceRoute` has DELETE only for `UPLOADS` (`docker-adapter/.../http/DockerSlice.java:110-112`); there is no `RtRulePath.route(MethodRule.DELETE, PathPatterns.MANIFESTS/BLOBS, …)`. The interfaces have no delete: `Manifests` (`.../Manifests.java:23-61`) and `Layers` (`.../Layers.java:20-45`) expose only put/get/tags/mount. `Blobs` exposes only `put`/`blob`. So there is **no image deletion or blob GC** in any mode; `skopeo delete` / registry GC fail.

**Target.** OCI-spec `DELETE /v2/<name>/manifests/<reference>` (removes the tag/digest link → 202 Accepted) and `DELETE /v2/<name>/blobs/<digest>` (removes the blob → 202), authorized under a delete permission, on `local` (`docker`) mode. Proxy/group delete is out of scope (deletes belong to the authoritative store).

**Plan.**
- **Interfaces:** add `CompletableFuture<Void> delete(ManifestReference ref)` to `Manifests`; `CompletableFuture<Void> delete(Digest digest)` to `Layers` (and the backing `Blobs.delete`). Impl in `AstoManifests` (delete the manifest link key via `Layout.manifest(name, ref)`, mirroring `addLink`/`readLink` `:243-268`; on digest-ref also remove the by-digest link) and `AstoLayers`/`Blobs` (delete the blob key). `ProxyManifests`/`CacheManifests`/`MultiReadManifests` throw `UnsupportedOperationException` (consistent with `ProxyManifests.put` `:94-95`).
- **Referrers GC:** when a deleted manifest had a `subject` (WS4-docker.2), remove its referrers-index entry so `oras discover` doesn't list a dangling reference.
- **Slices:** new `DeleteManifestSlice` (permission `DockerActions.DELETE` mask) + `DeleteBlobSlice` (registry delete permission), each consuming the body and returning `202 Accepted`; route them in `DockerSlice` under `MethodRule.DELETE` for `PathPatterns.MANIFESTS` and `PathPatterns.BLOBS`.
- **Audit:** emit `artifact_delete` (CLAUDE.md audit table) with captured `AuditContext` on both.
- **GC semantics note:** deleting a manifest link does not cascade-delete shared blobs (blobs are content-addressed and may be referenced by other manifests) — blob removal is the separate `DELETE blobs` op, matching registry GC behavior. Document this.

**Acceptance.**
- Itcase: `skopeo delete docker://.../repo:tag` against a `docker` repo removes the tag (subsequent pull 404s / `tags/list` drops it); `DELETE /v2/<name>/blobs/<digest>` removes a blob (subsequent blob GET 404s); a multi-arch image's child manifests and a referrer's index entry are handled correctly.
- Unit: `AstoManifests.delete` removes both link keys; `Blobs.delete` removes the blob; deleting a subject-bearing manifest prunes its referrers entry; proxy/group delete → `UnsupportedOperationException`/405.

**Size: M.**

---

### WS4-docker.6 — True multi-chunk `PATCH` blob upload (missing — chunked pushers 405)

**Current state.** `Upload.append` throws on the **second** chunk: `if (!chunks.isEmpty()) throw new UnsupportedOperationException("Multiple chunks are not supported")` (`docker-adapter/.../asto/Upload.java:111-116`). `PatchUploadSlice` calls `upload.append(...)` (`.../http/upload/PatchUploadSlice.java:41-57`), so a chunk-per-`PATCH` sequence (skopeo/oras chunking, very large layers) fails the 2nd PATCH with a misleading 405. Docker/BuildKit push monolithically so this is invisible to them but breaks conformant chunked pushers.

**Target.** Accept N sequential `PATCH` chunks, accumulating offset per the Distribution spec (validate `Content-Range` contiguity, return `Range: 0-<offset>` and `Docker-Upload-UUID`), then finalize on `PUT` with the assembled-blob digest verified against the claimed digest.

**Plan.**
- Rewrite `Upload.append` (`:111-131`): instead of rejecting a non-empty chunk set, append each chunk into an ordered, offset-keyed staging set under the upload root (`Layout.upload(name, uuid)`), tracking a running byte offset via storage metadata (reuse the `MetaCommon(meta).size()` accounting already used at `:125-128` and in `offset()` `:138-153`). Keep a single-`DigestedFlowable` running hash if feasible, else recompute at finalize.
- Validate contiguity: reject a chunk whose start offset ≠ current offset with `416 Requested Range Not Satisfiable` (spec) rather than silently accepting.
- `Upload.putTo(layers, digest)` (`:163-188`) finalizes by assembling the ordered chunks into the target blob and verifying against `digest` (ties to WS4-docker.9's explicit verify).
- `PatchUploadSlice` (`:41-57`) unchanged in shape but returns the accumulated `Range`/offset via the existing `acceptedResponse`.

**Acceptance.**
- Itcase: a multi-`PATCH` chunked upload (`oras push` chunked, or a scripted chunk sequence) followed by `PUT ?digest=` assembles the correct blob; `offset()`/`Range` accounting is correct across chunks; a non-contiguous chunk → 416.
- Unit: two+ chunks assemble in order; final digest matches; single-chunk path still works (regression).

**Size: M.**

---

### WS4-docker.7 — Full `Accept`-driven manifest content negotiation (missing — L)

**Current state.** `GetManifestSlice` ignores the `Accept` header entirely — it echoes the stored manifest's own `mediaType` (`docker-adapter/.../http/manifest/GetManifestSlice.java:60-64`), never inspecting what the client accepts, never converting schema1→schema2, never returning 406. A client accepting only `manifest.v2` can receive an `oci.index.v1`, and vice versa. The proxy hardcodes an Accept superset upstream (audit §1.B). Benign for most clients but non-conformant.

**Target.** Honor the `Accept` header: serve the stored variant when acceptable; return `406 Not Acceptable` when the stored manifest's media type is not in the client's `Accept` set; negotiate across the four types — `application/vnd.docker.distribution.manifest.v2+json`, `application/vnd.oci.image.manifest.v1+json`, `application/vnd.docker.distribution.manifest.list.v2+json`, `application/vnd.oci.image.index.v1+json`. On the **proxy** path, key the cache by Accept-variant so a v2-manifest and an OCI-index response for the same tag don't collide.

**Plan.**
- `GetManifestSlice.response` (`:37-102`): parse `Accept` (comma-separated media types, ignore `q` for now or honor it), compare against `found.mediaType()`; 200 with the stored `Content-Type` when acceptable, else `406` with an OCI error body. Same for `HeadManifestSlice`.
- Schema1→schema2 conversion is legacy and likely out of scope even here — document that only the four modern types are negotiated; a stored type outside the Accept set 406s rather than converting.
- **Proxy cache key:** in `ProxyManifests`/`CacheManifests`, incorporate the negotiated Accept-variant into the cache key so distinct representations are cached separately.
- **L flag:** this is the largest item; if it slips, it may become its own follow-on spec — the other eight items are independent of it.

**Acceptance.**
- Itcase: pull a multi-arch image with `Accept: application/vnd.oci.image.index.v1+json` returns the index; a client accepting only `manifest.v2+json` against an OCI-index-only tag gets 406; proxy caches the two variants independently (no cross-variant contamination).
- Unit: Accept parsing + 200/406 matrix over the four media types.

**Size: L.**

---

### WS4-docker.8 — `204 No Content` on upload cancel (cosmetic)

**Current state.** `DeleteUploadSlice` cancels then returns `ResponseBuilder.ok()` (200) with only `Docker-Upload-UUID` (`docker-adapter/.../http/upload/DeleteUploadSlice.java:49-54`). The Distribution spec's cancel response is `204 No Content`.

**Target.** Return `204 No Content` on a successful cancel; keep 404 (`UploadUnknownError`) for an unknown upload.

**Plan.** `DeleteUploadSlice.response` (`:51`): swap `ResponseBuilder.ok()` → the 204 builder, retaining `Docker-Upload-UUID`. No other change.

**Acceptance.** Unit: cancel of a started upload → 204; cancel of an unknown UUID → 404. Itcase (opportunistic): a client that starts then aborts a push sees 204.

**Size: S.**

---

### WS4-docker.9 — Verify claimed-vs-computed digest on `PUT`-by-digest (cosmetic/hardening)

**Current state.** `Upload.putTo(layers, digest)` looks up the staged chunk by a key derived from the **claimed** digest (`chunk(digest)`, `:164`) and, if absent, fails with `InvalidDigestException(digest.toString())` (`:185`). Because `append` stores the chunk under its **computed** digest (`chunk(data.digest())`, `:118-122`), a mismatch surfaces only *implicitly* as a key-miss — fragile: the error is misleading, the digest returned to the client (`Blob.digest()`) is the claimed one (not recomputed over the finalized bytes), and the cross-repo mount / assembled-multi-chunk paths don't recompute.

**Target.** Explicitly recompute the digest of the finalized blob bytes and compare to the client-claimed `?digest=`; on mismatch return the OCI `DIGEST_INVALID` error (400), not a misleading not-found. The stored/served digest is always the computed one.

**Plan.**
- In `Upload.putTo(layers, digest)` (`:163-188`) — after assembly (WS4-docker.6) — verify via `DigestedFlowable`/`CheckedBlobSource` (same primitive as WS4-docker.1) that the finalized bytes hash to `digest`; on mismatch fail with a `DIGEST_INVALID`-mapped error that `ErrorHandlingSlice` renders as 400.
- `PutUploadSlice` (`.../http/upload/PutUploadSlice.java:41-56`) unchanged in shape; the error mapping surfaces the proper status.

**Acceptance.** Unit: `PUT ?digest=` with a claimed digest ≠ computed → 400 `DIGEST_INVALID`; matching digest → 201 with the computed digest echoed. Regression: existing monolithic single-chunk push stays green.

**Size: S.**

---

## 3. Out of scope (2.3.0)

- **Native Docker/OCI token-server** inbound auth flow — Basic / JWT-as-password stays the auth model (works today); a `/token` bearer-issuing server is deferred.
- **Full OCI 1.1 referrers conformance** beyond the serve-half: the **fallback referrers tag schema** (`sha256-<subject>` index tag for registries without the referrers API) and **proxy-through of upstream referrers** (a `docker-proxy` surfacing the *upstream* registry's referrers). `WS4-docker.2` covers hosted-registry referrers only; `ProxyManifests`/`CacheManifests` return empty referrers.
- **Schema1 → schema2 manifest conversion** in `WS4-docker.7` — only the four modern media types are negotiated.
- Proxy/group **delete** — deletes target the authoritative (`local`) store only.
- Anything in WS1 (storage-for-scale, presigned blob redirects) / WS2 (HA) / WS3 (streaming) — this spec is adapter-API only.

## 4. Risks & rollback

- **WS4-docker.1** changes proxy cache-store behavior — a mismatch now *rejects* rather than caches. Risk: a legitimately-large streamed blob whose size is unknown could false-positive if `DigestedFlowable` sees a truncated tee. Mitigate with the existing `CachingBlob` byte-count guard (`expectedSize`/`written` at `:100-107`) — only verify+cache when the full expected byte count was written; otherwise skip caching (already the current fallback). No feature flag (CLAUDE.md); rollback is `git revert`.
- **WS4-docker.2** introduces a new storage layout (`referrers/…`) — prefix-per-subject is chosen specifically to avoid read-modify-write races under concurrent `oras attach`. Boot/backfill: existing images have no referrers entries (correct — they had no subject); no migration needed.
- **WS4-docker.5** delete is destructive and authorized — ensure the delete permission is distinct and not granted by default read/write roles; audit every delete (`artifact_delete`). GC must never cascade-delete shared content-addressed blobs.
- **WS4-docker.3** must preserve the group all-members-unavailable → non-404 invariant; the joined sources already degrade to partial unions on member error — keep that, don't "fix" it into a hard failure.
- **WS4-docker.7 (L)** is the only item that could slip; the other eight are independent of it and ship without it.

## 5. Docs & observability

**Docs (same-PR rule):**
- `docs/user-guide/repositories/docker.md` (exists) — add: referrers / cosign-OCI-mode & `oras attach`/`discover` support and its hosted-only scope; `skopeo delete` / image GC; chunked-push support; group-registry tag/catalog aggregation; the token-server non-goal.
- `docs/configuration-reference.md` + `docs/admin-guide/environment-variables.md` — any new delete-permission wiring / referrers or negotiation tunables.
- `CHANGELOG.md` — `### 🔒 Security` (proxy blob cache verification, WS4-docker.1), `### 🌟 New features` (OCI 1.1 referrers, image delete/GC, chunked upload, group aggregation, Accept negotiation), `### 🔧 Bug fixes` (pagination `Link`, 204 cancel, PUT-by-digest verify). One attributed bullet each; no dead-code/dev-log narrative.

**Observability (CLAUDE.md: a metric without a panel is invisible; every state transition logged):**
- **State transitions to log** (`EcsLogger`, `log.source=application`): proxy blob digest-mismatch reject (WS4-docker.1) — `event.category=web`, `event.action=blob_digest_mismatch`, `event.outcome=failure`, carry `package.checksum` + `repository.name`; referrers-index write and referrers-serve; manifest/blob delete; chunked-upload finalize / 416 contiguity reject.
- **Metrics + Grafana panels (same PR, WS7-tracked):** referrers index-write and serve counters (bounded `repository` tag only); proxy blob-verify failure counter (security signal); manifest/blob delete counters; chunked-upload chunk count / finalize outcome. Verify exact exposed names/tags against a live `:8087/metrics/vertx` scrape before writing panel queries; guard every recording with `MicrometerMetrics.isInitialized()`.
- **Audit taxonomy:** referrers GET → `artifact_resolution`; manifest/blob DELETE → `artifact_delete`; proxy fetch-and-store digest failure → `artifact_access`/`artifact_publish` `failure` with a `checksum_mismatch`-class reason. Capture `AuditContext` (traceId/clientIp) at the top of each slice before the first async hop.

## 6. Build order

1. **WS4-docker.1** (proxy blob verify) — security/correctness, small, the `CheckedBlobSource`/`DigestedFlowable` primitive it establishes is reused by `.6` and `.9`.
2. **WS4-docker.2** (referrers serve-half) — highest-value; establishes the `Manifest.subject()` accessor and `referrers/` layout that `.5` GC depends on.
3. **WS4-docker.3** (group aggregation) + **WS4-docker.4** (`Link` pagination) — independent silent-failure fixes.
4. **WS4-docker.5** (delete/GC) — depends on `.2` for referrers pruning.
5. **WS4-docker.6** (multi-chunk PATCH) — reuses `.1`'s digest primitive.
6. **WS4-docker.8** (204 cancel) + **WS4-docker.9** (PUT-by-digest verify) — cosmetic; trivial, can land anytime alongside `.6`.
7. **WS4-docker.7** (Accept negotiation, L) — last; independent of all above; may split into its own spec.
