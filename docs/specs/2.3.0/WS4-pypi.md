# WS4-pypi — PyPI adapter: API completeness & hosted-write correctness

- **Status:** 📝 DRAFT
- **Depends on:** `00-security-integrity-decisions.md` (S7 = WIRE, S8 = WIRE — both signed off 2026-07-24)
- **Blocks:** any "PEP 592/658/700/714 compliant" or "hosted PyPI is safe" claim
- **Decision-gated:** WS4-pypi.1 (S8) + WS4-pypi.2 (S7) are gated by `00`; the rest are unambiguous correctness/spec work
- **Size:** M. Ten independent sub-items (WS4-pypi.1 … WS4-pypi.10), each a separately-shippable agent task. Build order is security → truthful-silent-failures → missing surface.

## 1. Problem & goal

The PyPI adapter serves PEP 503 HTML + PEP 691 JSON simple indexes, file downloads with `#sha256`, `data-requires-python`, name normalization, DELETE, upload, and local HEAD correctly. But several advertised capabilities are **wired but silently non-functional on the wire**, one is a **straight authorization vulnerability**, and hosted-write accepts corrupt/duplicate uploads without complaint:

- **Any valid token can yank any repo** — `PypiHandler` was never passed `security.policy()`, so the yank/unyank endpoints have no per-repo authorization (authn only).
- **Hosted yank/unyank is a no-op on the wire** — the sidecar flips `yanked:true`, but the served index is never regenerated, so pip/uv never see `data-yanked`. The advertised PEP 592 compliance does not reach any client for hosted packages.
- **Proxy `/simple/<pkg>/` drops `data-yanked` for every pip (HTML) client** — the parsed link model has no `yanked` field, so the rewriter cannot emit it.
- **Hosted upload accepts corrupt and duplicate files** — twine's `sha256_digest` is ignored (no verify) and a re-upload silently overwrites (`move`, no exists check), contradicting the twine "File already exists" contract.
- **Missing spec surface:** hosted PEP 658 `.metadata` (always null; no extract/route), PEP 700 `versions[]` + per-file `size`, the PEP 714 `core-metadata` naming (JSON emits the wrong key entirely), proxy/group HEAD, the local legacy `/pypi/<pkg>/json` API, and proper `Accept` q-value negotiation. A proxy `<ver>/json` passthrough also leaks cooldown-blocked version metadata.

**Goal:** make every advertised PyPI capability truthful on the wire, close the yank authz hole and the corrupt/duplicate upload holes, and land the missing standard endpoints — all provable against a real pip/twine client.

## 2. Current state (evidence)

| Item | Symptom | Evidence (file:line) |
|---|---|---|
| S8 authz | `PypiHandler` constructed without `security.policy()`; routes have authn but no per-repo authz | `AsyncApiVerticle.java:471-473`, `PypiHandler.java:68,78-83` |
| S7 digest | twine `sha256_digest` / `gpg_signature` / `:action` form parts dropped — only the `content` part is inspected | `WheelSlice.java:190` |
| S7 overwrite | re-upload = unconditional `move`, no `exists` guard → silent clobber | `WheelSlice.java:123` |
| Yank no-op | `applyYank`/`applyUnyank` persist the sidecar but never call `IndexGenerator` or invalidate caches | `PypiHandler.java:176-202`; regen primitive exists at `WheelSlice.java:139-159` |
| Proxy data-yanked | `Link` record has no `yanked`; parser never extracts it (HTML `:180-186`, JSON `:219-225`); rewriter never emits it | `PypiSimpleIndex.java:55-77`, `PypiMetadataParser.java:180-186,219-225`, `PypiMetadataRewriter.java:44-66` |
| PEP 714 JSON key | JSON simple emits the **HTML** attribute name `data-dist-info-metadata` instead of `core-metadata` → clients ignore it | `SimpleJsonRenderer.java:67-70` |
| PEP 714 HTML alias | parser collapses `core-metadata`→`distInfoMetadata`; HTML emits only legacy `data-dist-info-metadata`, never `data-core-metadata` | `PypiMetadataParser.java:255-273`, `PypiMetadataRewriter.java:53-57`, `IndexGenerator.java:363-368` |
| PEP 658 hosted | sidecar `dist-info-metadata` hardcoded null on write; no `.metadata` extraction/write/route | `PypiSidecar.java:74`; `Metadata.java:206-224` already reads the `METADATA`/`PKG-INFO` entry |
| PEP 700 | JSON has no top-level `versions[]`; `FileEntry` has no `size` | `SimpleJsonRenderer.java:73-78,84-93` |
| Proxy/group HEAD | `PyProxySlice` routes GET only; FALLBACK → 405 | `PyProxySlice.java:120,138-141`; group at `RepositorySlices.java:1037-1060` |
| Local legacy JSON | `PySlice` has no `/pypi/<pkg>/json` route → 404 (local); HEAD routes exist at `:210-244` | `PySlice.java:108-248` |
| `<ver>/json` leak | detector deliberately does NOT match `/pypi/<name>/<ver>/json` → unfiltered upstream passthrough, cooldown-blocked version leaks | `PypiJsonMetadataRequestDetector.java:75-95` |
| Content-neg | `Accept` matched by substring `.contains(JSON_MIME)`; no q-values, no `latest+json` alias | `SimpleApiFormat.java:41-49` |

## 3. Target design

Two principles drive every item:

1. **Single source of truth per resource.** The sidecar (`PypiSidecar.Meta`: requires-python, upload-time, yanked, yanked-reason, dist-info-metadata) is authoritative for hosted metadata; the served index MUST be a pure projection of it. Any write to the sidecar (upload, yank, unyank) regenerates the projection and invalidates read caches. No write path may leave the served index stale.
2. **A capability advertised must reach the wire.** `data-yanked`, `core-metadata`/`data-core-metadata`, `.metadata`, `versions[]`, `size`, HEAD — each is verified against a real pip/twine client, not just a unit assertion on an internal model.

The hosted metadata flows entirely through `WheelSlice` → `PypiSidecar` → `IndexGenerator`/`SliceIndex` → `SimpleJsonRenderer`; the proxy flow through `PypiMetadataParser` → cooldown filter → `PypiMetadataRewriter`. Both pipelines already exist and are individually correct except for the specific gaps below — this is truthful-ing existing code, not greenfield, with two exceptions (PEP 658 extraction, PEP 700 `size`) that add a storage read.

## 4. Implementation plan (ordered sub-items)

### WS4-pypi.1 — [S] Per-repo authorization on yank/unyank (S8, security, do first)
- **Current:** `AsyncApiVerticle.java:471-473` builds `new PypiHandler(crs, repoData)` with no policy; `PypiHandler.register` (`:78-83`) places routes after the JWT filter (authn) but performs zero authz — **any authenticated principal can yank/unyank any repo**. Every sibling handler (`ArtifactHandler`, `CooldownHandler`, `SearchHandler`) already receives `this.security.policy()`.
- **Target:** yank/unyank require the caller to hold **write** authority on the specific `:repo` in the path. Unauthenticated → 401 (already handled by the JWT filter); authenticated-but-unauthorized → 403.
- **Plan:**
  - `PypiHandler` ctor gains a `Policy<?> policy` parameter; `AsyncApiVerticle.java:471-473` passes `this.security.policy()`.
  - In `yankHandler`/`unyankHandler`, before dispatching to `HandlerExecutor`, authorize the request user against a **per-request, per-repo** permission built from `ctx.pathParam("repo")` — reuse the `AuthzHandler` mechanism (`com.auto1.pantera.api.AuthzHandler` resolves `context.user()` → `policy.getPermissions(authUser).implies(perm)`), but construct the permission per request because `:repo` varies. The adapter-native repo-scoped permission is `new AdapterBasicPermission(repo, Action.Standard.WRITE)` (the same permission `PySlice` enforces on upload at `PySlice.java:139`). On deny, `ApiResponse.sendError(ctx, 403, "FORBIDDEN", …)` and return without touching storage.
- **Acceptance (itcase):** with a token scoped to `repoA` only, `POST /api/v1/pypi/repoB/pkg/1.0/yank` → **403** and the `repoB` sidecar is unchanged; the same token on `repoA` → 204. Anonymous → 401.
- **Size:** S.

### WS4-pypi.2 — [S] Upload digest verification + overwrite reject (S7, security)
- **Current:** `WheelSlice.filePart` (`:190`) accepts only the `content` multipart part; twine's `sha256_digest`/`md5_digest`/`gpg_signature`/`:action` parts are silently dropped. The stored file is never checked against the client-declared hash. `WheelSlice.java:123` does an unconditional `this.storage.move(key, name)` with no existence check → a second upload of the same filename silently overwrites.
- **Target:** (a) if the multipart carries `sha256_digest`, compute SHA-256 over the received bytes and **reject on mismatch** (400, `checksum_mismatch`); (b) reject a re-upload of an already-present distribution filename with **409 Conflict** (twine treats 409 as "already exists"; honors `--skip-existing`). Do not store the rejected temp file. `gpg_signature` remains out of scope (see §7).
- **Plan:**
  - Extend `WheelSlice.filePart` to also capture the `sha256_digest` field value while inspecting parts (accept both the `content` part and the digest field; keep the "exactly one content part" invariant). Thread the declared digest through to `response`.
  - After the temp save, before the `move` at `:123`, if a declared sha256 is present compare it against the SHA-256 of the stored bytes (reuse `ContentDigest(value, Digests.SHA256).hex()` as `IndexGenerator.buildEntry` does at `:184-185`); on mismatch delete the temp key and return 400.
  - Guard the `move`: `this.storage.exists(name)` first; if present, delete the temp key and return 409. (Note the immutability semantics: PyPI filenames are content-addressed by name+version, so a same-name re-upload is a genuine conflict.)
  - On mismatch/conflict emit the audit event `artifact_publish` / `failure` with `event.reason` `checksum_mismatch` (mismatch) — capture the `AuditContext` at the top of the slice per CLAUDE.md audit rules.
- **Acceptance (itcase):** `twine upload` of a wheel whose bytes were corrupted after digest computation → HTTP 400, file not stored; a clean `twine upload` → 200/201; `twine upload` of the same file again → 409 and `twine upload --skip-existing` succeeds (treats 409 as already-present).
- **Size:** S.

### WS4-pypi.3 — [S-M] Hosted yank/unyank truthful — regenerate index + invalidate caches
- **Current:** `PypiHandler.applyYank`/`applyUnyank` (`:176-202`) flip the sidecar via `PypiSidecar.yank`/`unyank` but never regenerate the served index, and never invalidate the negative / filtered-metadata caches. The persisted `<pkg>.html`/`<pkg>.json` (written by `IndexGenerator` on upload, `WheelSlice.java:139-159`) stay frozen at `yanked:false`. `SliceIndex` serves the stale persisted copy (`SliceIndex.java:123-143`), so pip/uv never observe the yank.
- **Target:** a yank/unyank is visible to the next pip/uv resolution without re-upload.
- **Plan:**
  - After the sidecar writes complete in `applyYank`/`applyUnyank`, run `new IndexGenerator(scoped, packageKey, path).generate()` (package index) **and** `generateRepoIndex()` if repo-level listing is affected — reuse the exact regen the upload path already calls (`WheelSlice.java:144-159`); `packageKey` = `new Key.From(packageName)` under the scoped storage.
  - Invalidate read caches the same way the upload path does (`WheelSlice.java:257-260`): `NegativeCacheRegistry.instance().invalidateAfterUpload("pypi", normalizedPkg)` and `FilteredMetadataCacheRegistry.instance().invalidateAfterUpload("pypi", normalizedPkg)`, so a cross-node peer's cached projection is dropped (rides the existing `CacheInvalidationPubSub`).
  - Keep the existing application-log state-transition entries; add the regenerated-index outcome to them.
- **Acceptance (itcase):** upload two versions of a hosted package; `POST …/yank` the newer; `pip install <pkg>` (no version pin) then resolves to the **older** version (pip skips yanked); `pip install <pkg>==<yanked>` still installs it but prints the yank warning; the served `/simple/<pkg>/` HTML now carries `data-yanked` and the JSON carries `"yanked": "<reason|empty>"`. `unyank` reverses it.
- **Size:** S-M.

### WS4-pypi.4 — [S-M] Proxy `/simple/<pkg>/` HTML — carry `data-yanked` through parser/rewriter
- **Current:** `PypiSimpleIndex.Link` (`:55-77`) has no `yanked`/`yankedReason` field. The HTML parse branch (`PypiMetadataParser.java:180-186`) has no `data-yanked` pattern; the JSON parse branch (`:219-225`) never reads the `yanked` field. `PypiMetadataRewriter` (`:44-66`) never emits `data-yanked`. Result: even with zero cooldown blocks, every pip (HTML) client loses the upstream yank signal. uv (JSON) is unaffected only because it reads upstream JSON directly.
- **Target:** the proxy preserves the upstream yank status end-to-end so pip sees `data-yanked` for proxied packages.
- **Plan:**
  - Add `String yanked` (PEP 592 wire form: `false` absent, or the reason string / empty string when yanked) to `PypiSimpleIndex.Link` — extend the record + its compact ctor.
  - `PypiMetadataParser`: HTML branch — add a `data-yanked="..."` attribute pattern (mirror `DATA_REQ_PYTHON_PATTERN`) and populate the field; JSON branch — read the PEP 691 `yanked` field (boolean `false` or string) via a `textOrNull`-style helper.
  - `PypiMetadataRewriter`: emit ` data-yanked="<reason>"` when the link is yanked (PEP 503 emits the attribute with the reason string, empty when none).
  - Update `PypiMetadataParserTest` / `PypiMetadataRewriterTest` round-trips.
- **Acceptance (itcase):** point a `pypi-proxy` at a fixture upstream serving a yanked file; `pip download <pkg>` prints the yank warning / skips the yanked version; assert the rewritten HTML contains `data-yanked`.
- **Size:** S-M.

### WS4-pypi.5 — [S] PEP 714 `core-metadata` naming — fix the JSON key + emit the HTML alias
- **Current:** JSON simple emits `data-dist-info-metadata` (`SimpleJsonRenderer.java:67-70`) — that is the **HTML attribute name**, not a valid PEP 691/714 JSON key, so clients ignore it entirely. HTML emits only the legacy `data-dist-info-metadata` (`PypiMetadataRewriter.java:53-57`, `IndexGenerator.java:363-368`); the PEP 714 rename `data-core-metadata` is never produced. The parser already reads both `core-metadata` and `dist-info-metadata` inbound (`PypiMetadataParser.java:255-273`) but collapses to one field.
- **Target:** JSON uses the correct key `core-metadata` (with `dist-info-metadata` retained as a compat alias); HTML emits both `data-core-metadata` (PEP 714) and `data-dist-info-metadata` (legacy) for the same value.
- **Plan:**
  - `SimpleJsonRenderer.render`: replace the `data-dist-info-metadata` object with `core-metadata` (object `{ "sha256": … }`), and additionally emit `dist-info-metadata` with the same value for legacy clients.
  - `PypiMetadataRewriter` + `IndexGenerator.buildHtmlAttributes` + `SliceIndex.buildHtmlAttributes`: emit both `data-core-metadata="sha256=…"` and `data-dist-info-metadata="sha256=…"`.
  - No parser change needed (already reads both forms); add renderer/rewriter test assertions.
- **Acceptance (unit + itcase):** `SimpleJsonRendererTest` asserts the JSON key is `core-metadata` (not `data-*`); HTML asserts both attributes; `pip install --require-hashes` against a package with a `.metadata` sidecar (WS4-pypi.6) resolves metadata without downloading the wheel.
- **Size:** S. (Latent until WS4-pypi.6 populates a real digest, but the key fix is independent.)

### WS4-pypi.6 — [M-L] Hosted PEP 658 `.metadata` — extract, write sidecar, serve route
- **Current:** `PypiSidecar.write` hardcodes `dist-info-metadata` to null (`:74`); no `.metadata` file is ever produced and no route serves one — a hosted `GET <file>.metadata` 404s. `pip install --require-hashes` and metadata-only resolution fail for hosted packages. (Proxy mode already threads upstream `.metadata` — this is hosted-only.) `Metadata.FromArchive.readArchive` (`Metadata.java:206-224`) already locates and reads the `METADATA`/`PKG-INFO` entry for `PackageInfo` extraction, so the bytes are already in hand during upload.
- **Target:** on hosted upload, persist the distribution's core metadata as a PEP 658 `.metadata` file, record its SHA-256 in the sidecar, and serve `GET`/`HEAD <file>.metadata`.
- **Plan:**
  - `WheelSlice`: during the existing archive read, capture the raw `METADATA`/`PKG-INFO` bytes (extend `Metadata.FromArchive` to optionally return the raw metadata bytes alongside `PackageInfo`, or add a sibling reader). Write them to `<package>/<version>/<filename>.metadata` in the same storage tree (so the artifact GET route can serve it), and compute their SHA-256.
  - `PypiSidecar.write`: accept the metadata sha256 and store it in `dist-info-metadata` instead of null; `IndexGenerator`/`SliceIndex` already project `distInfoMetadata` into `data-core-metadata`/`core-metadata` (via WS4-pypi.5).
  - `PySlice`: add GET + HEAD routes matching `.*\.(whl|tar\.gz|zip|tar\.bz2|tar\.Z|tar|egg)\.metadata` that serve the stored `.metadata` bytes as `application/octet-stream` (reuse `StorageArtifactSlice` + `HeadAsGetSlice`, mirroring the existing artifact routes at `PySlice.java:110-126,210-230`). Order the `.metadata` route **before** the artifact route so the suffix match wins.
  - Self-healing: for pre-existing hosted artifacts with no `.metadata`, `PypiSidecar` may lazily extract on first index read (bounded, like the existing `generateFromStorageMetadata` self-heal at `PypiSidecar.java:158-198`) — or leave `core-metadata` absent for legacy files (acceptable; PEP 658 is advisory). Pick the lazy-extract path only if it stays off the event loop.
- **Acceptance (itcase):** `twine upload` a wheel, then `GET <wheel-url>.metadata` returns the `METADATA` file with a matching `Content-Length`; the simple index advertises `data-core-metadata="sha256=<x>"` where `<x>` is the sha256 of that file; `pip install --require-hashes` resolves using the `.metadata` without fetching the wheel body.
- **Size:** M-L.

### WS4-pypi.7 — [M] PEP 700 — top-level `versions[]` + per-file `size`
- **Current:** `SimpleJsonRenderer.render` emits `meta.api-version=1.1` but no `versions` array, and `FileEntry` (`:84-93`) has no `size`. Both are PEP 700 (Simple API v1.1) fields.
- **Target:** the JSON simple detail page carries `"versions": [...]` (distinct sorted versions present) and each file object carries `"size": <int>`.
- **Plan:**
  - Add `long size` to `SimpleJsonRenderer.FileEntry`; emit it per file.
  - Populate `size` where entries are built: `IndexGenerator.buildEntry` (`:183-196`) and `SliceIndex.buildJsonEntry` (`:453-467`) already read the file — capture `storage.metadata(key).read(Meta.OP_SIZE)` (as `WheelSlice.putArtifactToQueue` does at `:244`) alongside the sha256 pass so no extra full-body read is added for size.
  - `SimpleJsonRenderer.render`: derive the distinct version set from the filenames (reuse `PypiMetadataParser.extractVersionFromFilename` or thread versions from `IndexGenerator`) and emit sorted `versions[]`.
- **Acceptance (unit + itcase):** `SimpleJsonRendererTest` asserts `versions[]` and per-file `size`; a `uv`/`pip` JSON fetch of the detail page parses without error and the reported `size` equals the stored artifact size.
- **Size:** M.

### WS4-pypi.8 — [S-M] Proxy/group HEAD
- **Current:** `PyProxySlice` routes only `MethodRule.GET` (`:120`); the FALLBACK returns `methodNotAllowed()` (`:138-141`), so an uncached HEAD → 405. Local `PySlice` already answers HEAD (`:210-244`); the proxy and therefore `pypi-group` (which walks members, `RepositorySlices.java:1037-1060`) do not — uv HEAD probes behave inconsistently across modes.
- **Target:** proxy (and group-via-members) answer HEAD for artifact and simple-index paths with the same status/headers as GET, minus the body.
- **Plan:**
  - Add a `MethodRule.HEAD` `RtRulePath` to `PyProxySlice` that delegates to the same `ProxySlice` GET pipeline via a HEAD-as-GET adapter (mirror `PySlice.HeadAsGetSlice` at `PySlice.java:265-286`: rewrite HEAD→GET, drain and discard the body, return status + headers incl. `Content-Length`). Place it before the FALLBACK.
  - Group mode inherits correct HEAD once each proxy member answers HEAD; verify `GroupResolver` forwards the method unchanged (no code change expected — confirm in the itcase).
- **Acceptance (itcase):** `HEAD` of a cached and an uncached wheel via `pypi-proxy` returns 200 with `Content-Length` and no body; the same via `pypi-group` returns 200; a missing file returns 404 (not 405).
- **Size:** S-M.

### WS4-pypi.9 — [M] Local legacy `/pypi/<pkg>/json` + filter the `<ver>/json` cooldown leak
- **Current:** `PySlice` has no `/pypi/<pkg>/json` route (`:108-248`) → a local repo 404s the legacy JSON API (poetry/pip-tools use it). In proxy mode `PypiJsonMetadataRequestDetector` **deliberately** does not match `/pypi/<name>/<ver>/json` (`:75-95`), so that version-level endpoint is an unfiltered upstream passthrough → a cooldown-blocked version's metadata leaks.
- **Target:** (a) local repos serve a synthesized package-level `/pypi/<pkg>/json` from the sidecar/index (info + releases + urls); (b) the proxy version-level `/pypi/<name>/<ver>/json` is cooldown-filtered (blocked → 404, consistent with the artifact-layer block), not a raw passthrough.
- **Plan:**
  - Local: add a GET route to `PySlice` matching `.*/pypi/<pkg>/json` that builds the legacy JSON blob from the persisted index + sidecars (a new small `LegacyJsonSlice`/handler reading the same data `SliceIndex` uses). Scope to package-level; version-level (`/pypi/<pkg>/<ver>/json`) may 404 locally if not needed.
  - Proxy: extend `PypiJsonMetadataRequestDetector` (or add a version-aware detector) so `/pypi/<name>/<ver>/json` is recognized and routed through the cooldown filter — when the version is blocked, return 404 rather than proxying upstream. Reuse the existing `PypiJsonMetadataFilter`/`PypiJsonHandler` version-filter logic.
- **Acceptance (itcase):** local `GET /pypi/<pkg>/json` returns a valid legacy blob resolvable by `poetry`/`pip-tools`; a proxy `GET /pypi/<pkg>/<blockedver>/json` for a cooldown-blocked version returns 404 (metadata does not leak); an unblocked version returns its metadata.
- **Size:** M.

### WS4-pypi.10 — [S] Content-negotiation q-values + `latest+json` alias
- **Current:** `SimpleApiFormat.fromHeaders` (`:41-49`) picks JSON iff the `Accept` header **substring-contains** the JSON MIME. It ignores q-values (so `application/vnd.pypi.simple.v1+json;q=0.1, text/html;q=1.0` wrongly selects JSON) and does not honor the `application/vnd.pypi.simple.latest+json` / `…latest+html` aliases or `*/*`.
- **Target:** proper `Accept` parsing: honor q-weights, the `v1+json`/`v1+html` and `latest+json`/`latest+html` aliases, and `*/*` (default HTML for backward compat).
- **Plan:**
  - Rewrite `SimpleApiFormat.fromHeaders` to parse the `Accept` header into (media-range, q) pairs, rank them, and select JSON vs HTML by highest acceptable q; treat `latest+json` as JSON and `latest+html`/`text/html`/`*/*` as HTML. Keep HTML as the tie/default.
  - Extend `SimpleApiFormatTest` with q-value ordering, alias, and `*/*` cases.
- **Acceptance (unit):** `Accept: …v1+json;q=0.1, text/html;q=1.0` → HTML; `…latest+json` → JSON; `*/*` → HTML; `…v1+json` alone → JSON.
- **Size:** S.

## 5. Acceptance criteria (release-level)

All prove semantics via a real client or invocation/state assertions — never wall-clock (CLAUDE.md doctrine):

1. **Authz (WS4-pypi.1):** a token without write on `:repo` gets 403 on yank/unyank; the sidecar is untouched. Anonymous → 401.
2. **Upload integrity (WS4-pypi.2):** a digest-mismatched `twine upload` → 400, nothing stored, `artifact_publish`/`failure`/`checksum_mismatch` audit emitted; a duplicate → 409; `--skip-existing` succeeds.
3. **Yank truthful (WS4-pypi.3):** after a hosted yank, `pip install <pkg>` skips the yanked version and the served index (HTML + JSON) shows the yank; `unyank` reverses it.
4. **Proxy yank (WS4-pypi.4):** a proxied yanked file surfaces `data-yanked` to pip.
5. **PEP 714 (WS4-pypi.5):** JSON key is `core-metadata`; HTML carries both `data-core-metadata` and `data-dist-info-metadata`.
6. **PEP 658 hosted (WS4-pypi.6):** `GET <wheel>.metadata` returns the `METADATA` file; index advertises its sha256; `--require-hashes` resolves without the wheel body.
7. **PEP 700 (WS4-pypi.7):** JSON detail page carries `versions[]` and per-file `size` matching the stored size.
8. **HEAD parity (WS4-pypi.8):** proxy and group answer HEAD (200 + `Content-Length`, no body) for cached and uncached files; missing → 404, never 405.
9. **Legacy JSON + leak (WS4-pypi.9):** local `/pypi/<pkg>/json` resolvable; proxy `<ver>/json` for a blocked version → 404.
10. **Content-neg (WS4-pypi.10):** q-value ordering and aliases select the right format.

## 6. Test requirements

- **Unit:** `SimpleJsonRendererTest` (core-metadata key, versions[], size), `PypiMetadataParserTest`/`PypiMetadataRewriterTest` (yanked round-trip; core-metadata alias), `SimpleApiFormatTest` (q-values/aliases), `WheelSliceTest` (digest mismatch → reject, overwrite → 409), `PypiSidecarTest` (dist-info-metadata sha256 populated), a `PypiHandler` authz unit against a fake `Policy`. Use `InMemoryStorage`; no new TestContainers.
- **Itcase** (`test_images/Dockerfile.pypi` already has pip + twine; add uv where an itcase exists): extend `PySliceITCase`/`PyProxySliceITCase`/`PypiITCase` for yank-visible-to-pip, twine digest/overwrite reject, `.metadata` fetch, proxy/group HEAD, and the `<ver>/json` block. Prefer driving the real client and asserting its observable behavior (install skips yanked, `--skip-existing` succeeds).
- **No absolute-latency assertions.** For cache-invalidation propagation, poll for the eventual state.

## 7. Out of scope

- twine `gpg_signature` `.asc` storage/verification (PyPI itself deprecated PGP uploads; not part of S7 — S7 is the digest check only).
- XML-RPC `search` completeness / `list_packages` / `package_releases` (P9 — pip search is dead upstream; deprecate separately).
- Group **index merge** (union of members' simple indexes) — that is a WS4-core group-contract decision, tracked separately; this spec only makes group HEAD correct.
- Proxy hardcoded `files.pythonhosted.org` CDN routing and negative-cache poisoning of cooldown 404s — those are WS5/WS6 (cooldown-cache coherence / upstream efficiency), not this spec.
- Index-generation scale (full-body SHA-256 per upload/read; O(all-artifacts) repo reindex) — WS1/WS7 scale work.
- Presigned direct-download for wheels — WS1.7.

## 8. Risks & rollback

- **WS4-pypi.1 is a live authz hole — land it first**, before any change that makes yank more effective (WS4-pypi.3). Shipping WS4-pypi.3 without WS4-pypi.1 would make an unauthorized yank *more* damaging.
- **WS4-pypi.2 overwrite → 409** changes hosted-publish semantics (previously silent clobber). This matches PyPI/twine; document it and rely on `--skip-existing`. Rollback is `git revert`.
- **WS4-pypi.6** adds a storage write + read per upload and a new route; keep the extraction off the event loop (it already runs on the upload worker path) and make the `.metadata` route suffix-match win over the artifact route (route ordering bug risk).
- **WS4-pypi.3** regen reuses the upload-path `IndexGenerator`; the known scale cost (full-body sha256 per file) applies to yank too — acceptable for an admin action, and superseded by WS1 digest memoization.
- Each sub-item is independently revertable; no feature flags (settled changes ship whole, per project policy).

## 9. Docs & observability

- **User guide** `docs/user-guide/` (PyPI page): yank/unyank now visible to pip/uv; PEP 658 `.metadata` for hosted; PEP 700 `versions[]`/`size`; PEP 714 `core-metadata`; twine `--skip-existing` on duplicate; the upload digest check.
- **Reference** `docs/rest-api-reference.md`: yank/unyank now require per-repo write authorization (403 semantics); new `.metadata` and `/pypi/<pkg>/json` routes. `docs/configuration-reference.md`: no new settings (no feature flags).
- **CHANGELOG:** `### 🔒 Security` — yank per-repo authz (S8), upload digest verification + overwrite reject (S7); `### 🔧 Bug fixes` — hosted yank/unyank now regenerate the index, proxy `/simple/` now carries `data-yanked`, JSON `core-metadata` key, `<ver>/json` cooldown leak; `### 🌟 New features` — hosted PEP 658 `.metadata`, PEP 700 `versions[]`/`size`, PEP 714 `data-core-metadata`, proxy/group HEAD, content-negotiation q-values. One attributed bullet each; no dev-log narrative.
- **Observability:** the existing yank/unyank application logs stay; add the regenerated-index outcome and the 403 authz-deny transition. WS4-pypi.2 emits the `artifact_publish`/`failure`/`checksum_mismatch` audit record (capture `AuditContext` at slice entry). If a `pantera.pypi.upload.digest_mismatch_total` counter is added, it ships with a Grafana panel in the same PR (WS7) — a metric without a panel is invisible.
